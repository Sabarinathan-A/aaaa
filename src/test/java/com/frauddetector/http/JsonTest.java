package com.frauddetector.http;

import com.frauddetector.testkit.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves round-trip serialize/parse of a nested object covering string, number,
 * boolean, null, list, and nested map. Run via {@code ./build.sh test}.
 */
public final class JsonTest {

    public static void main(String[] args) {
        testRoundTrip();
        testPrimitives();
        testEscaping();
        System.out.println("JsonTest OK");
    }

    private static void testRoundTrip() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "Austin");
        nested.put("zip", 73301L);

        List<Object> tags = new ArrayList<>();
        tags.add("a");
        tags.add(2L);
        tags.add(true);
        tags.add(null);

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "Jane Doe");
        original.put("age", 42L);
        original.put("score", 3.5);
        original.put("active", true);
        original.put("deleted", false);
        original.put("middleName", null);
        original.put("tags", tags);
        original.put("address", nested);

        String json = Json.write(original);
        Map<String, Object> parsed = Json.parseObject(json);

        Assert.assertEquals("Jane Doe", parsed.get("name"), "name round-trips");
        Assert.assertEquals(42L, parsed.get("age"), "age round-trips as Long");
        Assert.assertEquals(3.5, parsed.get("score"), "score round-trips as Double");
        Assert.assertEquals(Boolean.TRUE, parsed.get("active"), "active true");
        Assert.assertEquals(Boolean.FALSE, parsed.get("deleted"), "deleted false");
        Assert.assertNull(parsed.get("middleName"), "null round-trips");
        Assert.assertTrue(parsed.containsKey("middleName"), "null key preserved");

        Object tagsOut = parsed.get("tags");
        Assert.assertTrue(tagsOut instanceof List, "tags is a list");
        List<?> tagList = (List<?>) tagsOut;
        Assert.assertEquals(4, tagList.size(), "tag list size");
        Assert.assertEquals("a", tagList.get(0), "tag[0]");
        Assert.assertEquals(2L, tagList.get(1), "tag[1]");
        Assert.assertEquals(Boolean.TRUE, tagList.get(2), "tag[2]");
        Assert.assertNull(tagList.get(3), "tag[3] null");

        Object addrOut = parsed.get("address");
        Assert.assertTrue(addrOut instanceof Map, "address is a map");
        Map<?, ?> addr = (Map<?, ?>) addrOut;
        Assert.assertEquals("Austin", addr.get("city"), "nested city");
        Assert.assertEquals(73301L, addr.get("zip"), "nested zip");
    }

    private static void testPrimitives() {
        Assert.assertEquals("null", Json.write(null), "null writes");
        Assert.assertEquals("true", Json.write(true), "true writes");
        Assert.assertEquals("7", Json.write(7L), "long writes");
        Assert.assertEquals("\"hi\"", Json.write("hi"), "string writes");

        Assert.assertNull(Json.parse("null"), "parse null");
        Assert.assertEquals(Boolean.TRUE, Json.parse("true"), "parse true");
        Assert.assertEquals(123L, Json.parse("123"), "parse long");
        Assert.assertEquals(-1.25, Json.parse("-1.25"), "parse double");
        Assert.assertEquals("hello", Json.parse("\"hello\""), "parse string");
    }

    private static void testEscaping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quote", "she said \"hi\"\nnewline\ttab");
        String json = Json.write(m);
        Map<String, Object> parsed = Json.parseObject(json);
        Assert.assertEquals("she said \"hi\"\nnewline\ttab", parsed.get("quote"), "escaping round-trips");
    }
}
