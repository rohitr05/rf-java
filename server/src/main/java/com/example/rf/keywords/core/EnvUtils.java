package com.example.rf.keywords.core;

import io.github.cdimascio.dotenv.Dotenv;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/** Precedence: -Dkey → OS env → .env → application.conf → default */
public final class EnvUtils {
  private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();
  private static final Config CONF   = ConfigFactory.load();

  private EnvUtils() {}

  public static String get(String key, String def) {
    String sys = System.getProperty(key);
    if (sys != null) return sys;

    String envKey = toEnvKey(key);
    String osVal = System.getenv(envKey);
    if (osVal != null) return osVal;

    String dotVal = DOTENV.get(envKey);
    if (dotVal != null) return dotVal;

    if (CONF.hasPath(key)) return CONF.getString(key);

    return def;
  }

  public static String must(String key) {
    String v = get(key, null);
    if (v == null) throw new IllegalStateException("Missing config: " + key);
    return v;
  }

  private static String toEnvKey(String key) { return key.replace('.', '_').toUpperCase(); }
}
