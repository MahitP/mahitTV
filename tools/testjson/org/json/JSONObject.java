package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only stand-in for Android's JSONObject, with a small parser: tools/WeatherParseTest runs HomeData's parser on a
 * plain JVM, where android.jar's org.json only throws "Stub!". Put this ahead of android.jar on the class path.
 */
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    final Map<String, Object> values = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String json) throws JSONException {
        Parser p = new Parser(json);
        Object v = p.value();
        if (!(v instanceof JSONObject)) throw new JSONException("not an object");
        values.putAll(((JSONObject) v).values);
    }

    public boolean has(String name) { return values.containsKey(name); }

    public boolean isNull(String name) { Object v = values.get(name); return v == null || v == NULL; }

    public Object opt(String name) { return values.get(name); }

    public Object get(String name) throws JSONException {
        Object v = values.get(name);
        if (v == null) throw new JSONException("no value for " + name);
        return v;
    }

    public String getString(String name) throws JSONException { return String.valueOf(get(name)); }

    public String optString(String name) { return optString(name, ""); }

    public String optString(String name, String fallback) {
        Object v = values.get(name);
        return v == null || v == NULL ? fallback : String.valueOf(v);
    }

    public double getDouble(String name) throws JSONException {
        Object v = get(name);
        if (!(v instanceof Number)) throw new JSONException("not a number: " + name);
        return ((Number) v).doubleValue();
    }

    public int getInt(String name) throws JSONException { return (int) getDouble(name); }

    public long getLong(String name) throws JSONException { return (long) getDouble(name); }

    public double optDouble(String name) { return optDouble(name, Double.NaN); }

    public double optDouble(String name, double fallback) {
        Object v = values.get(name);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public int optInt(String name) { return optInt(name, 0); }

    public int optInt(String name, int fallback) {
        Object v = values.get(name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long optLong(String name) { return optLong(name, 0); }

    public long optLong(String name, long fallback) {
        Object v = values.get(name);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object v = get(name);
        if (!(v instanceof JSONObject)) throw new JSONException("not an object: " + name);
        return (JSONObject) v;
    }

    public JSONObject optJSONObject(String name) {
        Object v = values.get(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = get(name);
        if (!(v instanceof JSONArray)) throw new JSONException("not an array: " + name);
        return (JSONArray) v;
    }

    public JSONArray optJSONArray(String name) {
        Object v = values.get(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    /** Objects, arrays, strings (with the common escapes), numbers, true, false and null. */
    private static final class Parser {
        private final String s;
        private int at;

        Parser(String s) { this.s = s; }

        Object value() throws JSONException {
            space();
            if (at >= s.length()) throw new JSONException("end of input");
            char c = s.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
            if (s.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
            if (s.startsWith("null", at)) { at += 4; return NULL; }
            return number();
        }

        private JSONObject object() throws JSONException {
            JSONObject o = new JSONObject();
            at++;
            space();
            if (s.charAt(at) == '}') { at++; return o; }
            while (true) {
                space();
                String key = string();
                space();
                expect(':');
                o.values.put(key, value());
                space();
                if (s.charAt(at) == ',') { at++; continue; }
                expect('}');
                return o;
            }
        }

        private JSONArray array() throws JSONException {
            JSONArray a = new JSONArray();
            at++;
            space();
            if (s.charAt(at) == ']') { at++; return a; }
            while (true) {
                a.values.add(value());
                space();
                if (s.charAt(at) == ',') { at++; continue; }
                expect(']');
                return a;
            }
        }

        private String string() throws JSONException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (at < s.length()) {
                char c = s.charAt(at++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(at++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(at, at + 4), 16)); at += 4; break;
                    default: b.append(e);
                }
            }
            throw new JSONException("unterminated string");
        }

        private Number number() throws JSONException {
            int from = at;
            while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) at++;
            if (from == at) throw new JSONException("unexpected " + s.charAt(at) + " at " + at);
            return Double.parseDouble(s.substring(from, at));
        }

        private void expect(char c) throws JSONException {
            space();
            if (at >= s.length() || s.charAt(at) != c) throw new JSONException("expected " + c + " at " + at);
            at++;
        }

        private void space() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
        }
    }
}
