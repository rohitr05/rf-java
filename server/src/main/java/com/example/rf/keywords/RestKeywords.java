package com.example.rf.keywords;

import com.example.rf.keywords.core.ApiSessionStore;
import com.example.rf.keywords.core.FileUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import org.robotframework.javalib.annotation.ArgumentNames;
import org.robotframework.javalib.annotation.RobotKeyword;
import org.robotframework.javalib.annotation.RobotKeywords;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

@RobotKeywords
public class RestKeywords {
  public static final String ROBOT_LIBRARY_SCOPE = "GLOBAL";
  private final Map<String, Response> lastByAlias = new HashMap<>();

  // ---------- Session keywords ----------

  @RobotKeyword("Create API Session with base URL and optional headers map/JSON.")
  @ArgumentNames({"name", "baseUrl", "headers={}"})
  public void createApiSession(String name, String baseUrl, Object headers) {
    ApiSessionStore.put(name, baseUrl, coerceHeaders(headers));
  }

  @RobotKeyword("Create an API session without headers.")
  @ArgumentNames({"name", "baseUrl"})
  public void createApiSession(String name, String baseUrl) {
    ApiSessionStore.put(name, baseUrl, Map.of());
  }

  @RobotKeyword("Set Bearer token on an existing API session")
  @ArgumentNames({"name", "token"})
  public void setBearer(String name, String token) {
    var spec = ApiSessionStore.get(name);
    spec.header("Authorization", "Bearer " + token);
  }

  // ---------- HTTP verbs ----------

  @RobotKeyword("GET request; params optional; response saved under alias.")
  @ArgumentNames({"session", "path", "params={}", "alias=last"})
  public void get(String session, String path, Object params, String alias) {
    Response r = given()
        .spec(ApiSessionStore.get(session))
        .params(coerceParams(params))
        .when().get(path);
    lastByAlias.put(alias, r);
  }

  @RobotKeyword("POST request with body string; response saved under alias.")
  @ArgumentNames({"session", "path", "body", "alias=last"})
  public void post(String session, String path, String body, String alias) {
    Response r = given()
        .spec(ApiSessionStore.get(session))
        .header("Content-Type","application/json")
        .body(body)
        .when().post(path);
    lastByAlias.put(alias, r);
  }

  @RobotKeyword("PATCH request with body string; response saved under alias.")
  @ArgumentNames({"session", "path", "body", "alias=last"})
  public void patch(String session, String path, String body, String alias) {
    Response r = given()
        .spec(ApiSessionStore.get(session))
        .header("Content-Type","application/json")
        .body(body)
        .when().patch(path);
    lastByAlias.put(alias, r);
  }

  // ---------- Assertions & utilities ----------

  @RobotKeyword("Assert response HTTP status equals expected.")
  @ArgumentNames({"alias","expectedStatus"})
  public void statusShouldBe(String alias, int expected) {
    var r = must(alias);
    if (r.statusCode() != expected) {
      throw new AssertionError("Expected " + expected + " got " + r.statusCode()
          + "; timeMs=" + r.time()
          + "; body=" + r.asString());
    }
  }

  @RobotKeyword("Extract JsonPath value from response body and return as string.")
  @ArgumentNames({"alias","jsonPath"})
  public String extractJsonPath(String alias, String jsonPath) {
    String body = must(alias).asString();
    String path = normalizeJsonPath(jsonPath); // support both $.a.b and a.b
    Object v = io.restassured.path.json.JsonPath.from(body).get(path);

    // Auto-flatten single-item lists for convenience
    if (v instanceof java.util.List) {
      java.util.List<?> list = (java.util.List<?>) v;
      if (list.size() == 1) v = list.get(0);
    }
    return v == null ? "" : String.valueOf(v);
  }

  @RobotKeyword("Assert JsonPath equals expected string.")
  @ArgumentNames({"alias","jsonPath","expected"})
  public void jsonPathShouldBe(String alias, String jsonPath, String expected) {
    String got = extractJsonPath(alias, jsonPath);
    if (!String.valueOf(expected).equals(got)) {
      throw new AssertionError(
        "JsonPath '" + jsonPath + "' expected [" + expected + "] but got [" + got + "]; body=" + must(alias).asString());
    }
  }

  @RobotKeyword("Write response body to a file.")
  @ArgumentNames({"alias","filePath"})
  public void saveBody(String alias, String filePath) {
    FileUtils.writeString(filePath, must(alias).asString());
  }

  // ---------- Internals ----------

  private Response must(String alias) {
    Response r = lastByAlias.getOrDefault(alias, lastByAlias.get("last"));
    if (r == null) throw new IllegalStateException("No response under alias " + alias);
    return r;
  }

  /** Accept both '$.args.foo' and 'args.foo' for RestAssured's GPath engine. */
  private static String normalizeJsonPath(String p) {
    if (p == null) return null;
    p = p.trim();
    if (p.startsWith("$.")) return p.substring(2);
    if (p.startsWith("$"))  return p.substring(1);
    return p;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> coerceHeaders(Object headers) {
    if (headers == null) return Map.of();
    if (headers instanceof Map) {
      Map<?, ?> in = (Map<?, ?>) headers;
      Map<String, String> out = new HashMap<>();
      in.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
      return out;
    }
    String s = String.valueOf(headers).trim();
    if (s.isEmpty() || "{}".equals(s)) return Map.of();
    try {
      var om = new ObjectMapper();
      return om.readValue(s, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Headers must be Map or JSON object string. Got: " + s, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> coerceParams(Object params) {
    if (params == null) return Map.of();
    if (params instanceof Map) return (Map<String, Object>) params;
    String s = String.valueOf(params).trim();
    if (s.isEmpty() || "{}".equals(s)) return Map.of();
    try {
      var om = new ObjectMapper();
      return om.readValue(s, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Params must be Map or JSON object string. Got: " + s, e);
    }
  }
}
