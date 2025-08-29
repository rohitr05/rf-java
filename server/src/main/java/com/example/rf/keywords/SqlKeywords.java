package com.example.rf.keywords;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.robotframework.javalib.annotation.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RobotKeywords
public class SqlKeywords {
  public static final String ROBOT_LIBRARY_SCOPE = "GLOBAL";
  private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

  @RobotKeyword("Create a named SQL connection pool.")
  @ArgumentNames({"name","jdbcUrl","user","password"})
  public void connect(String name, String jdbcUrl, String user, String password) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(jdbcUrl);
    cfg.setUsername(user);
    cfg.setPassword(password);
    cfg.setMaximumPoolSize(5);
    pools.put(name, new HikariDataSource(cfg));
  }

  @RobotKeyword("Execute SELECT and return list of dict rows.")
  @ArgumentNames({"name","sql"})
  public List<Map<String,Object>> select(String name, String sql) {
    try (Connection c = pools.get(name).getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      List<Map<String,Object>> out = new ArrayList<>();
      var md = rs.getMetaData();
      int cols = md.getColumnCount();
      while (rs.next()) {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i=1;i<=cols;i++) row.put(md.getColumnLabel(i), rs.getObject(i));
        out.add(row);
      }
      return out;
    } catch (SQLException e) { throw new RuntimeException(e); }
  }

  @RobotKeyword("Execute UPDATE/INSERT/DELETE and return update count.")
  @ArgumentNames({"name","sql"})
  public int execute(String name, String sql) {
    try (Connection c = pools.get(name).getConnection();
         Statement st = c.createStatement()) {
      return st.executeUpdate(sql);
    } catch (SQLException e) { throw new RuntimeException(e); }
  }

  @RobotKeyword("Close connection pool.")
  @ArgumentNames({"name"})
  public void close(String name) {
    HikariDataSource ds = pools.remove(name);
    if (ds!=null) ds.close();
  }
}
