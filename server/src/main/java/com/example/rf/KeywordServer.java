// KeywordServer.java — final, correct version
package com.example.rf;

import org.robotframework.remoteserver.RemoteServer;
import com.example.rf.keywords.RestKeywords;
import com.example.rf.keywords.JsonKeywords;
import com.example.rf.keywords.SqlKeywords;
import com.example.rf.keywords.ExcelKeywords;
import com.example.rf.keywords.FixKeywords;

public final class KeywordServer {
  public static void main(String[] args) throws Exception {
    final int port  = Integer.parseInt(System.getProperty("rf.port",
                       System.getenv().getOrDefault("RF_PORT", "8270")));
    final String host = System.getProperty("rf.host",
                       System.getenv().getOrDefault("RF_HOST", "0.0.0.0"));

    RemoteServer.configureLogging();
    RemoteServer server = new RemoteServer(host, port);

    // IMPORTANT: register INSTANCES (not Class objects)
    server.putLibrary("/rest",  new RestKeywords());
    server.putLibrary("/json",  new JsonKeywords());
    server.putLibrary("/sql",   new SqlKeywords());
    server.putLibrary("/excel", new ExcelKeywords());
    server.putLibrary("/fix",   new FixKeywords());

    System.out.printf("Keyword server started at http://%s:%d%n", host, port);
    server.start();
  }
}
