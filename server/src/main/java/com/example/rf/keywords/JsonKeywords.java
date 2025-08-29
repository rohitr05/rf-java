package com.example.rf.keywords;

import com.example.rf.keywords.core.FileUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.robotframework.javalib.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RobotKeywords
public class JsonKeywords {
  public static final String ROBOT_LIBRARY_SCOPE = "GLOBAL";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @RobotKeyword("Read JSON file to string.")
  @ArgumentNames({"path"})
  public String readJsonFile(String path) { return FileUtils.readString(path); }

  @RobotKeyword("Write JSON string to file.")
  @ArgumentNames({"path","json"})
  public void writeJsonFile(String path, String json) { FileUtils.writeString(path, json); }

  @RobotKeyword("Pretty-print a JSON string.")
  @ArgumentNames({"json"})
  public String pretty(String json) throws Exception {
    Object tree = MAPPER.readValue(json, Object.class);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
  }

  @RobotKeyword("Get value by JsonPath, returns string.")
  @ArgumentNames({"json","jsonPath"})
  public String jsonPath(String json, String jsonPath) {
    return String.valueOf(JsonPath.parse(json).read(jsonPath));
  }

  @RobotKeyword("Shallow-merge two JSON objects; right wins.")
  @ArgumentNames({"leftJson","rightJson"})
  public String mergeObjects(String leftJson, String rightJson) throws Exception {
    Map<String,Object> a = MAPPER.readValue(leftJson, new TypeReference<Map<String,Object>>(){});
    Map<String,Object> b = MAPPER.readValue(rightJson, new TypeReference<Map<String,Object>>(){});
    Map<String,Object> m = new LinkedHashMap<>(a);
    m.putAll(b);
    return MAPPER.writeValueAsString(m);
  }
}
