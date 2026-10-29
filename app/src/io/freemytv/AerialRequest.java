package io.freemytv;

/** Monotonic ownership token for asynchronous Aerial work. */
final class AerialRequest {
    private long generation;
    private String url;
    private boolean videoVisible;
    long show(String next) { videoVisible = false; url = next; return ++generation; }
    long hide() { videoVisible = false; url = null; return ++generation; }
    boolean matches(long token, String value) { return token == generation && value != null && value.equals(url); }
    boolean videoReady(long token, String value) { if (!matches(token, value)) return false; videoVisible = true; return true; }
    boolean videoVisible() { return videoVisible; }
}
