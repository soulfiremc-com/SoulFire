/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.test.script;

import com.soulfiremc.server.script.NodeValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.soulfiremc.test.script.ScriptTestHelper.executeNode;
import static org.junit.jupiter.api.Assertions.*;

/// Tests for pure node execution: math, logic, string, list, constant, and utility nodes.
final class NodeExecutionTest {

  // ==================== Math Nodes ====================

  @ParameterizedTest
  @CsvSource({
    "5, 3, 8",
    "0, 0, 0",
    "-5, 3, -2",
    "1.5, 2.5, 4.0"
  })
  void addNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.add", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "10, 3, 7",
    "0, 5, -5",
    "5.5, 2.5, 3.0"
  })
  void subtractNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.subtract", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "5, 3, 15",
    "0, 100, 0",
    "-2, 3, -6",
    "2.5, 4, 10.0"
  })
  void multiplyNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.multiply", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "10, 2, 5",
    "7, 2, 3.5",
    "-10, 2, -5"
  })
  void divideNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.divide", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void divideByZeroReturnsZero() {
    var result = executeNode("math.divide", Map.of(
      "a", NodeValue.ofNumber(10),
      "b", NodeValue.ofNumber(0)
    ));
    assertEquals(0.0, result.get("result").asDouble(-1.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "10, 3, 1",
    "15, 5, 0",
    "7, 4, 3"
  })
  void moduloNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.modulo", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "-5, 5",
    "5, 5",
    "0, 0"
  })
  void absNodeExecutes(double input, double expected) {
    var result = executeNode("math.abs", Map.of("value", NodeValue.ofNumber(input)));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "3.7, 3",
    "3.2, 3",
    "-3.7, -4"
  })
  void floorNodeExecutes(double input, double expected) {
    var result = executeNode("math.floor", Map.of("value", NodeValue.ofNumber(input)));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "3.2, 4",
    "3.7, 4",
    "-3.7, -3"
  })
  void ceilNodeExecutes(double input, double expected) {
    var result = executeNode("math.ceil", Map.of("value", NodeValue.ofNumber(input)));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "3.4, 3",
    "3.5, 4",
    "3.6, 4",
    "-3.5, -3"
  })
  void roundNodeExecutes(double input, double expected) {
    var result = executeNode("math.round", Map.of("value", NodeValue.ofNumber(input)));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "3, 7, 3",
    "10, 5, 5",
    "5, 5, 5"
  })
  void minNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.min", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "3, 7, 7",
    "10, 5, 10",
    "5, 5, 5"
  })
  void maxNodeExecutes(double a, double b, double expected) {
    var result = executeNode("math.max", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "2, 3, 8",
    "10, 2, 100",
    "5, 0, 1"
  })
  void powNodeExecutes(double base, double exp, double expected) {
    var result = executeNode("math.pow", Map.of(
      "base", NodeValue.ofNumber(base),
      "exponent", NodeValue.ofNumber(exp)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "5, 0, 10, 5",
    "-5, 0, 10, 0",
    "15, 0, 10, 10"
  })
  void clampNodeExecutes(double value, double min, double max, double expected) {
    var result = executeNode("math.clamp", Map.of(
      "value", NodeValue.ofNumber(value),
      "min", NodeValue.ofNumber(min),
      "max", NodeValue.ofNumber(max)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  // ==================== Logic Nodes ====================

  @ParameterizedTest
  @CsvSource({
    "5, 5, ==, true",
    "5, 3, ==, false",
    "5, 3, !=, true",
    "5, 5, !=, false",
    "5, 3, >, true",
    "3, 5, >, false",
    "3, 5, <, true",
    "5, 3, <, false",
    "5, 5, >=, true",
    "5, 6, >=, false",
    "5, 5, <=, true",
    "6, 5, <=, false"
  })
  void compareNodeExecutes(double a, double b, String op, boolean expected) {
    var result = executeNode("logic.compare", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b),
      "operator", NodeValue.ofString(op)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "true, true, true",
    "true, false, false",
    "false, true, false",
    "false, false, false"
  })
  void andNodeExecutes(boolean a, boolean b, boolean expected) {
    var result = executeNode("logic.and", Map.of(
      "a", NodeValue.ofBoolean(a),
      "b", NodeValue.ofBoolean(b)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "true, true, true",
    "true, false, true",
    "false, true, true",
    "false, false, false"
  })
  void orNodeExecutes(boolean a, boolean b, boolean expected) {
    var result = executeNode("logic.or", Map.of(
      "a", NodeValue.ofBoolean(a),
      "b", NodeValue.ofBoolean(b)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "true, false",
    "false, true"
  })
  void notNodeExecutes(boolean input, boolean expected) {
    var result = executeNode("logic.not", Map.of("value", NodeValue.ofBoolean(input)));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "true, true, false",
    "true, false, true",
    "false, true, true",
    "false, false, false"
  })
  void xorNodeExecutes(boolean a, boolean b, boolean expected) {
    var result = executeNode("logic.xor", Map.of(
      "a", NodeValue.ofBoolean(a),
      "b", NodeValue.ofBoolean(b)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  // ==================== String Nodes ====================

  @Test
  void concatNodeExecutes() {
    var result = executeNode("string.concat", Map.of(
      "a", NodeValue.ofString("Hello"),
      "b", NodeValue.ofString(" World")
    ));
    assertEquals("Hello World", result.get("result").asString(""));
  }

  @Test
  void replaceNodeExecutes() {
    var result = executeNode("string.replace", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "search", NodeValue.ofString("World"),
      "replacement", NodeValue.ofString("Universe")
    ));
    assertEquals("Hello Universe", result.get("result").asString(""));
  }

  @Test
  void splitNodeExecutes() {
    var result = executeNode("string.split", Map.of(
      "text", NodeValue.ofString("a,b,c"),
      "delimiter", NodeValue.ofString(",")
    ));
    var parts = result.get("result").asStringList();

    assertEquals(3, parts.size(), "Split result should have 3 parts");
    assertEquals("a", parts.getFirst(), "First part should be 'a'");
    assertEquals("b", parts.get(1), "Second part should be 'b'");
    assertEquals("c", parts.get(2), "Third part should be 'c'");
  }

  @Test
  void substringNodeExecutes() {
    var result = executeNode("string.substring", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "start", NodeValue.ofNumber(0),
      "end", NodeValue.ofNumber(5)
    ));
    assertEquals("Hello", result.get("result").asString(""));
  }

  @Test
  void stringLengthNodeExecutes() {
    var result = executeNode("string.length", Map.of("text", NodeValue.ofString("Hello")));
    assertEquals(5, result.get("length").asInt(0));
  }

  @ParameterizedTest
  @CsvSource({
    "Hello World, Hello, true",
    "Hello World, World, false",
    "Hello World, Goodbye, false"
  })
  void startsWithNodeExecutes(String text, String prefix, boolean expected) {
    var result = executeNode("string.starts_with", Map.of(
      "text", NodeValue.ofString(text),
      "prefix", NodeValue.ofString(prefix)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "Hello World, World, true",
    "Hello World, Hello, false",
    "Hello World, Goodbye, false"
  })
  void endsWithNodeExecutes(String text, String suffix, boolean expected) {
    var result = executeNode("string.ends_with", Map.of(
      "text", NodeValue.ofString(text),
      "suffix", NodeValue.ofString(suffix)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @ParameterizedTest
  @CsvSource({
    "Hello World, World, true",
    "Hello World, Universe, false",
    "Hello World, llo, true"
  })
  void stringContainsNodeExecutes(String text, String search, boolean expected) {
    var result = executeNode("string.contains", Map.of(
      "text", NodeValue.ofString(text),
      "search", NodeValue.ofString(search)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @Test
  void toLowerCaseNodeExecutes() {
    var result = executeNode("string.to_lower_case", Map.of("text", NodeValue.ofString("HELLO")));
    assertEquals("hello", result.get("result").asString(""));
  }

  @Test
  void toUpperCaseNodeExecutes() {
    var result = executeNode("string.to_upper_case", Map.of("text", NodeValue.ofString("hello")));
    assertEquals("HELLO", result.get("result").asString(""));
  }

  @Test
  void trimNodeExecutes() {
    var result = executeNode("string.trim", Map.of("text", NodeValue.ofString("  hello  ")));
    assertEquals("hello", result.get("result").asString(""));
  }

  // ==================== List Nodes ====================

  @Test
  void listLengthNodeExecutes() {
    var result = executeNode("list.length", Map.of(
      "list", NodeValue.of(List.of(1, 2, 3, 4, 5))
    ));
    assertEquals(5, result.get("length").asInt(0));
  }

  @Test
  void getAtNodeExecutes() {
    var result = executeNode("list.get_at", Map.of(
      "list", NodeValue.of(List.of("a", "b", "c")),
      "index", NodeValue.ofNumber(1)
    ));
    assertEquals("b", result.get("item").asString(""));
  }

  @Test
  void firstNodeExecutes() {
    var result = executeNode("list.first", Map.of(
      "list", NodeValue.of(List.of("first", "second", "third"))
    ));
    assertEquals("first", result.get("item").asString(""));
  }

  @Test
  void lastNodeExecutes() {
    var result = executeNode("list.last", Map.of(
      "list", NodeValue.of(List.of("first", "second", "third"))
    ));
    assertEquals("third", result.get("item").asString(""));
  }

  @ParameterizedTest
  @CsvSource({
    "apple, true",
    "banana, true",
    "grape, false"
  })
  void listContainsNodeExecutes(String search, boolean expected) {
    var result = executeNode("list.contains", Map.of(
      "list", NodeValue.of(List.of("apple", "banana", "cherry")),
      "item", NodeValue.ofString(search)
    ));
    assertEquals(expected, result.get("result").asBoolean(false));
  }

  @Test
  void joinToStringNodeExecutes() {
    var result = executeNode("list.join", Map.of(
      "list", NodeValue.of(List.of("a", "b", "c")),
      "separator", NodeValue.ofString(", ")
    ));
    assertEquals("\"a\", \"b\", \"c\"", result.get("result").asString(""));
  }

  @Test
  void rangeNodeExecutes() {
    var result = executeNode("list.range", Map.of(
      "start", NodeValue.ofNumber(1),
      "end", NodeValue.ofNumber(5)
    ));
    var list = result.get("list").asList();

    assertEquals(4, list.size(), "Range 1..5 should have 4 elements");
    assertEquals(1, list.getFirst().asInt(0), "First element should be 1");
    assertEquals(2, list.get(1).asInt(0), "Second element should be 2");
    assertEquals(3, list.get(2).asInt(0), "Third element should be 3");
    assertEquals(4, list.get(3).asInt(0), "Fourth element should be 4");
  }

  @Test
  void listCreateNodeExecutes() {
    var result = executeNode("list.create", Map.of(
      "items", NodeValue.ofList(List.of(
        NodeValue.ofString("a"),
        NodeValue.ofString("b"),
        NodeValue.ofString("c")
      ))
    ));
    assertEquals(List.of("a", "b", "c"),
      result.get("list").asList().stream().map(v -> v.asString("")).toList());
  }

  @Test
  void setCreateNodeExecutesWithDistinctItems() {
    var result = executeNode("set.create", Map.of(
      "items", NodeValue.ofList(List.of(
        NodeValue.ofString("a"),
        NodeValue.ofString("b"),
        NodeValue.ofString("a")
      ))
    ));
    assertEquals(2, result.get("set").asSet().size());
    assertEquals(List.of("a", "b"),
      result.get("set").asSet().stream().map(v -> v.asString("")).toList());
  }

  @Test
  void collectionAddNodePreservesSetIdentity() {
    var result = executeNode("collection.add", Map.of(
      "collection", NodeValue.ofSet(List.of(NodeValue.ofString("a"), NodeValue.ofString("b"))),
      "item", NodeValue.ofString("a")
    ));
    assertEquals(2, result.get("collection").asSet().size());
    assertEquals(List.of("a", "b"),
      result.get("collection").asSet().stream().map(v -> v.asString("")).toList());
  }

  @Test
  void mapKeysAndValuesNodesExecute() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", "Alex");
    map.put("age", 25);

    var keysResult = executeNode("map.keys", Map.of("map", NodeValue.of(map)));
    assertEquals(List.of("name", "age"),
      keysResult.get("keys").asList().stream().map(v -> v.asString("")).toList());

    var valuesResult = executeNode("map.values", Map.of("map", NodeValue.of(map)));
    assertEquals(List.of("Alex", "25"),
      valuesResult.get("values").asList().stream().map(v -> v.asString("")).toList());
  }

  // ==================== Constant Nodes ====================

  @Test
  void numberConstantNodeExecutes() {
    var result = executeNode("constant.number", Map.of("value", NodeValue.ofNumber(42)));
    assertEquals(42, result.get("value").asInt(0));
  }

  @Test
  void stringConstantNodeExecutes() {
    var result = executeNode("constant.string", Map.of("value", NodeValue.ofString("test")));
    assertEquals("test", result.get("value").asString(""));
  }

  @Test
  void booleanConstantNodeExecutes() {
    var result = executeNode("constant.boolean", Map.of("value", NodeValue.ofBoolean(true)));
    assertTrue(result.get("value").asBoolean(false));
  }

  // ==================== Utility Nodes ====================

  @Test
  void toStringNodeExecutes() {
    var result = executeNode("util.to_string", Map.of("value", NodeValue.ofNumber(42)));
    assertEquals("42", result.get("result").asString(""));
  }

  @Test
  void toNumberNodeExecutes() {
    var result = executeNode("util.to_number", Map.of("value", NodeValue.ofString("42.5")));
    assertEquals(42.5, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void isNullNodeExecutesWithMissingValue() {
    var result = executeNode("util.is_null", Map.of());
    assertTrue(result.get("result").asBoolean(false));
  }

  @Test
  void isNullNodeExecutesWithJsonNull() {
    var result = executeNode("util.is_null", Map.of("value", NodeValue.ofNull()));
    assertTrue(result.get("result").asBoolean(false),
      "isNull should return true for JSON null (NodeValue.ofNull())");
  }

  @Test
  void isNullNodeExecutesWithPresentValue() {
    var result = executeNode("util.is_null", Map.of("value", NodeValue.ofString("hello")));
    assertFalse(result.get("result").asBoolean(true));
  }

  @Test
  void isEmptyNodeExecutesWithEmptyString() {
    var result = executeNode("util.is_empty", Map.of("value", NodeValue.ofString("")));
    assertTrue(result.get("result").asBoolean(false));
  }

  @Test
  void isEmptyNodeExecutesWithNonEmptyString() {
    var result = executeNode("util.is_empty", Map.of("value", NodeValue.ofString("hello")));
    assertFalse(result.get("result").asBoolean(true));
  }

  // ==================== Math Nodes: Trig & Advanced ====================

  @Test
  void sinNodeExecutes() {
    var result = executeNode("math.sin", Map.of("angle", NodeValue.ofNumber(90)));
    assertEquals(1.0, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void sinNodeZero() {
    var result = executeNode("math.sin", Map.of("angle", NodeValue.ofNumber(0)));
    assertEquals(0.0, result.get("result").asDouble(1.0), 0.001);
  }

  @Test
  void cosNodeExecutes() {
    var result = executeNode("math.cos", Map.of("angle", NodeValue.ofNumber(0)));
    assertEquals(1.0, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void cosNode90Degrees() {
    var result = executeNode("math.cos", Map.of("angle", NodeValue.ofNumber(90)));
    assertEquals(0.0, result.get("result").asDouble(1.0), 0.001);
  }

  @Test
  void tanNodeExecutes() {
    var result = executeNode("math.tan", Map.of("angle", NodeValue.ofNumber(45)));
    assertEquals(1.0, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void tanNodeZero() {
    var result = executeNode("math.tan", Map.of("angle", NodeValue.ofNumber(0)));
    assertEquals(0.0, result.get("result").asDouble(1.0), 0.001);
  }

  @Test
  void sqrtNodeExecutes() {
    var result = executeNode("math.sqrt", Map.of("value", NodeValue.ofNumber(16)));
    assertEquals(4.0, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void sqrtNodeZero() {
    var result = executeNode("math.sqrt", Map.of("value", NodeValue.ofNumber(0)));
    assertEquals(0.0, result.get("result").asDouble(1.0), 0.001);
  }

  @ParameterizedTest
  @CsvSource({
    "0, 10, 0.5, 5.0",
    "0, 10, 0, 0.0",
    "0, 10, 1, 10.0",
    "5, 15, 0.5, 10.0"
  })
  void lerpNodeExecutes(double a, double b, double t, double expected) {
    var result = executeNode("math.lerp", Map.of(
      "a", NodeValue.ofNumber(a),
      "b", NodeValue.ofNumber(b),
      "t", NodeValue.ofNumber(t)
    ));
    assertEquals(expected, result.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void lerpNodeClampsT() {
    var result = executeNode("math.lerp", Map.of(
      "a", NodeValue.ofNumber(0),
      "b", NodeValue.ofNumber(10),
      "t", NodeValue.ofNumber(2.0)
    ));
    assertEquals(10.0, result.get("result").asDouble(0.0), 0.001,
      "t > 1 should be clamped to 1");
  }

  // ==================== String Nodes: Format & IndexOf ====================

  @Test
  void formatNodeSinglePlaceholder() {
    var result = executeNode("string.format", Map.of(
      "template", NodeValue.ofString("Hello {0}!"),
      "args", NodeValue.of(List.of("World"))
    ));
    assertEquals("Hello World!", result.get("result").asString(""));
  }

  @Test
  void formatNodeMultiplePlaceholders() {
    var result = executeNode("string.format", Map.of(
      "template", NodeValue.ofString("{0} + {1} = {2}"),
      "args", NodeValue.of(List.of("1", "2", "3"))
    ));
    assertEquals("1 + 2 = 3", result.get("result").asString(""));
  }

  @Test
  void formatNodeNoArgsUnchanged() {
    var result = executeNode("string.format", Map.of(
      "template", NodeValue.ofString("No placeholders here"),
      "args", NodeValue.of(List.of())
    ));
    assertEquals("No placeholders here", result.get("result").asString(""));
  }

  @Test
  void formatNodeStringArgNoQuotes() {
    var result = executeNode("string.format", Map.of(
      "template", NodeValue.ofString("Name: {0}"),
      "args", NodeValue.of(List.of("Alice"))
    ));
    assertEquals("Name: Alice", result.get("result").asString(""),
      "String args should not include JSON quotes");
  }

  @Test
  void formatNodeNumberArg() {
    var result = executeNode("string.format", Map.of(
      "template", NodeValue.ofString("Value: {0}"),
      "args", NodeValue.of(List.of(42))
    ));
    assertEquals("Value: 42", result.get("result").asString(""));
  }

  @Test
  void indexOfNodeFound() {
    var result = executeNode("string.index_of", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "search", NodeValue.ofString("World")
    ));
    assertEquals(6, result.get("index").asInt(-1));
  }

  @Test
  void indexOfNodeNotFound() {
    var result = executeNode("string.index_of", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "search", NodeValue.ofString("Missing")
    ));
    assertEquals(-1, result.get("index").asInt(0));
  }

  @Test
  void indexOfNodeIgnoreCase() {
    var result = executeNode("string.index_of", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "search", NodeValue.ofString("hello"),
      "ignoreCase", NodeValue.ofBoolean(true)
    ));
    assertEquals(0, result.get("index").asInt(-1));
  }

  @Test
  void indexOfNodeCaseSensitive() {
    var result = executeNode("string.index_of", Map.of(
      "text", NodeValue.ofString("Hello World"),
      "search", NodeValue.ofString("hello"),
      "ignoreCase", NodeValue.ofBoolean(false)
    ));
    assertEquals(-1, result.get("index").asInt(0),
      "Case-sensitive search should not match 'hello' in 'Hello World'");
  }
}
