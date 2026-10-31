package org.json;

/** Test-only stand-in for Android's org.json (android.jar ships stubs that throw on a plain JVM). */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }
}
