package io.freemytv;

/** Monotonic ownership token for asynchronous Aerial work. */
final class AerialRequest {
    private long generation;
    private String url;
    long show(String next) { url = next; return ++generation; }
    long hide() { url = null; return ++generation; }
    boolean matches(long token, String value) { return token == generation && value != null && value.equals(url); }
}
