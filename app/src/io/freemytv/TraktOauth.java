package io.freemytv;

/** Where Trakt's requests go, apart from Trakt so it can be tested without a TV. */
final class TraktOauth {
    private TraktOauth() {}

    private static final String API = "https://api.trakt.tv", AUTH = "https://auth.trakt.tv";
    /**
     * What a renewal names: the address the person's API app has registered (config.example.properties). Signing in with
     * a code never goes there, but Trakt asks for it, and a Development app can only register localhost.
     */
    static final String REDIRECT = "http://localhost";

    static String url(String path) {
        return (path.startsWith("/oauth/") ? AUTH : API) + path;
    }
}
