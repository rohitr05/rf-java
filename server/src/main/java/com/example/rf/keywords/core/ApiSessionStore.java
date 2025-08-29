package com.example.rf.keywords.core;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ApiSessionStore {
  private static final Map<String, RequestSpecification> SESSIONS = new ConcurrentHashMap<>();
  private ApiSessionStore() {}

  public static void put(String name, String baseUrl, Map<String, String> headers) {
    RequestSpecBuilder b = new RequestSpecBuilder().setBaseUri(baseUrl);
    if (headers != null && !headers.isEmpty()) {
      b.addHeaders(headers); // accepts a Map<String, ?>
    }
    RequestSpecification spec = b.build();
    SESSIONS.put(name, spec);
  }

  public static RequestSpecification get(String name) {
    RequestSpecification spec = SESSIONS.get(name);
    if (spec == null) throw new IllegalStateException("No API session named: " + name);
    return spec;
  }
}
