package com.world2d.engine.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Null-safe JSON helpers; the project format stays human-readable and stable-ID based. */
public final class J {
    private J() {}
    public static JSONObject o(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) put(result, String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }
    public static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException ignored) { }
    }
    public static JSONObject obj(JSONObject parent, String key) {
        JSONObject object = parent.optJSONObject(key);
        if (object == null) { object = new JSONObject(); put(parent, key, object); }
        return object;
    }
    public static JSONArray arr(JSONObject parent, String key) {
        JSONArray value = parent.optJSONArray(key);
        if (value == null) { value = new JSONArray(); put(parent, key, value); }
        return value;
    }
    public static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (JSONException ex) { return new JSONObject(); }
    }
    public static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString(); }
    public static double number(JSONObject object, String key, double fallback) {
        double value = object == null ? fallback : object.optDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }
    public static String text(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }
    public static JSONObject at(JSONArray array, int index) { return array == null ? null : array.optJSONObject(index); }
    public static String pretty(JSONObject object) {
        try { return object.toString(2); } catch (JSONException ex) { return object.toString(); }
    }
}
