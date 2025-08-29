package com.example.rf.keywords.core;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve ${env:FOO}, ${now:iso}, and ${key} (from vars map). */
public final class TemplateUtils {
  private TemplateUtils(){}

  private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{env:([A-Za-z0-9_]+)}");

  public static String resolve(String text, Map<String,String> vars) {
    String out = text;

    // ${env:FOO}
    Matcher m = ENV_PATTERN.matcher(out);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String key = m.group(1);
      String val = EnvUtils.get(key, "");
      m.appendReplacement(sb, Matcher.quoteReplacement(val));
    }
    m.appendTail(sb);
    out = sb.toString();

    // ${now:iso}
    out = out.replace("${now:iso}", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

    // ${key} from vars
    for (var e : vars.entrySet()) out = out.replace("${"+e.getKey()+"}", e.getValue());
    return out;
  }
}
