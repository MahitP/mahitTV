package org.json;

import java.util.ArrayList;
import java.util.List;

/** Test-only stand-in for Android's JSONArray: the calls HomeData's parser makes, with the same signatures. */
public class JSONArray {
    final List<Object> values = new ArrayList<>();

    public JSONArray() {}

    public int length() { return values.size(); }

    public boolean isNull(int i) { return i < 0 || i >= values.size() || values.get(i) == JSONObject.NULL; }

    public Object opt(int i) { return i < 0 || i >= values.size() ? null : values.get(i); }

    public Object get(int i) throws JSONException {
        Object v = opt(i);
        if (v == null) throw new JSONException("no value at " + i);
        return v;
    }

    public String getString(int i) throws JSONException { return String.valueOf(get(i)); }

    public double getDouble(int i) throws JSONException {
        Object v = get(i);
        if (!(v instanceof Number)) throw new JSONException("not a number at " + i);
        return ((Number) v).doubleValue();
    }

    public int getInt(int i) throws JSONException { return (int) getDouble(i); }

    public double optDouble(int i) { return optDouble(i, Double.NaN); }

    public double optDouble(int i, double fallback) {
        Object v = opt(i);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public int optInt(int i) { return optInt(i, 0); }

    public int optInt(int i, int fallback) {
        Object v = opt(i);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public JSONObject optJSONObject(int i) {
        Object v = opt(i);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }
}
