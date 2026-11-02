package io.freemytv;

/** Trakt wants its sign-in requests on the auth host, and a renewal must name the address the person's app registered. */
public final class TraktOauthTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (String path : new String[] {"/oauth/device/code", "/oauth/device/token", "/oauth/token", "/oauth/revoke"}) {
            check(TraktOauth.url(path).equals("https://auth.trakt.tv" + path), path + ": the auth host");
        }
        for (String path : new String[] {"/users/settings", "/sync/watched/movies", "/sync/watchlist/movies"}) {
            check(TraktOauth.url(path).equals("https://api.trakt.tv" + path), path + ": the API host");
        }
        check(TraktOauth.REDIRECT.equals("http://localhost"), "a Development app can only register localhost");
        System.out.println("TraktOauthTest: " + checks + " checks passed");
    }
}
