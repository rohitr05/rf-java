package com.example.rf.keywords;

import org.robotframework.javalib.annotation.ArgumentNames;
import org.robotframework.javalib.annotation.RobotKeyword;
import org.robotframework.javalib.annotation.RobotKeywords;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RobotKeywords
public class FixKeywords implements Application {
    public static final String ROBOT_LIBRARY_SCOPE = "SUITE";

    private Initiator initiator;
    private SessionID sessionID;

    private final BlockingQueue<Message> inbox = new ArrayBlockingQueue<>(100);
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);

    // ---- Application callbacks ----
    @Override public void onCreate(SessionID sid) { this.sessionID = sid; }
    @Override public void onLogon(SessionID sid)  { loggedOn.set(true);  logonLatch.countDown(); }
    @Override public void onLogout(SessionID sid) { loggedOn.set(false); }
    @Override public void toAdmin(Message msg, SessionID sid) { /* no-op */ }
    @Override public void toApp(Message msg, SessionID sid) throws DoNotSend { /* no-op */ }
    @Override public void fromAdmin(Message msg, SessionID sid) { /* no-op */ }
    @Override public void fromApp(Message msg, SessionID sid) { inbox.offer(msg); }

    /**
     * Start a FIX initiator using the supplied configuration file. If the filename begins
     * with {@code classpath:} the remainder will be loaded from the classpath via the current
     * class loader. Otherwise a normal {@link FileInputStream} is used. This supports packaging
     * configuration files inside the resources folder so that tests are portable.
     *
     * @param cfgFile The path to the FIX initiator configuration file or {@code classpath:...}
     * @throws Exception if the configuration cannot be found or the initiator fails to start
     */
    @RobotKeyword("Start FIX initiator with cfg file. Supports 'classpath:' prefix for files bundled in resources.")
    @ArgumentNames({"cfgFile"})
    public void startInitiator(String cfgFile) throws Exception {
        SessionSettings settings;
        InputStream cfgStream = null;
        if (cfgFile != null && cfgFile.startsWith("classpath:")) {
            String resource = cfgFile.substring("classpath:".length());
            // remove any leading slashes so ResourceLoader can find it
            while (resource.startsWith("/") || resource.startsWith("\\")) {
                resource = resource.substring(1);
            }
            cfgStream = FixKeywords.class.getClassLoader().getResourceAsStream(resource);
            if (cfgStream == null) {
                throw new FileNotFoundException("FIX config resource not found on classpath: " + resource);
            }
            settings = new SessionSettings(cfgStream);
        } else {
            settings = new SessionSettings(new FileInputStream(cfgFile));
        }
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new ScreenLogFactory(true, true, true);
        MessageFactory msgFactory = new DefaultMessageFactory();
        initiator = new SocketInitiator(this, storeFactory, settings, logFactory, msgFactory);
        initiator.start();
        if (cfgStream != null) {
            cfgStream.close();
        }
    }

    @RobotKeyword("Wait until FIX session is logged on (timeout seconds).")
    @ArgumentNames({"timeoutSec=15"})
    public void awaitLogon(int timeoutSec) throws InterruptedException {
        if (!loggedOn.get()) {
            boolean ok = logonLatch.await(timeoutSec, TimeUnit.SECONDS);
            if (!ok) throw new AssertionError("Session did not log on within " + timeoutSec + "s");
        }
    }

    @RobotKeyword("Send NewOrderSingle (symbol, qty, side(BUY/SELL), price).")
    @ArgumentNames({"symbol","quantity","side","price"})
    public void sendNOS(String symbol, double quantity, String side, double price) throws SessionNotFound {
        NewOrderSingle nos = new NewOrderSingle(
            new ClOrdID("CL" + System.currentTimeMillis()),
            new Side("SELL".equalsIgnoreCase(side) ? Side.SELL : Side.BUY),
            new TransactTime(),
            new OrdType(OrdType.LIMIT)
        );
        nos.set(new Symbol(symbol));
        nos.set(new OrderQty(quantity));
        nos.set(new Price(price));
        Session.sendToTarget(nos, sessionID);
    }

    @RobotKeyword("Wait for ExecutionReport; returns message string.")
    @ArgumentNames({"timeoutSec=10"})
    public String awaitExecutionReport(int timeoutSec) throws InterruptedException {
        Message m = inbox.poll(timeoutSec, TimeUnit.SECONDS);
        if (m == null) throw new AssertionError("No message within timeout");
        return m.toString();
    }

    @RobotKeyword("Stop FIX initiator.")
    public void stopInitiator() {
        if (initiator != null) initiator.stop();
    }
}