package com.example.rf.keywords.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileUtils {
  private FileUtils(){}

  public static String readString(String path) {
    try { return Files.readString(Path.of(path)); }
    catch (IOException e) { throw new RuntimeException(e); }
  }

  public static void writeString(String path, String content) {
    try {
      Path p = Path.of(path);
      if (p.getParent()!=null) Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) { throw new RuntimeException(e); }
  }
}
