package io.freemytv;

import java.util.Arrays;
import java.util.List;

/** Pure resolver-policy checks; no Android objects are constructed. */
public final class RecsWatchTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Recs.Pick shared = new Recs.Pick();
        shared.id = 22;
        shared.kind = "movie";
        shared.title = "Shared art";
        shared.backdrop = "/shared.jpg";
        shared.app = "disney";
        shared.pkg = "com.disney.disneyplus";
        shared.meta = "Drama · 2h 4m · Disney+";
        shared.link = "disney://title/22";
        shared.detailed = true;

        Recs.MediaChoice focused = new Recs.MediaChoice("movie", 22, "History title", "com.netflix.ninja", "netflix", "Netflix");
        Recs.Pick resolved = WatchArtworkPolicy.knownPick(Arrays.asList(shared), focused);

        check(resolved != null, "same kind:id is reused across service packages");
        check(resolved != shared, "reused pick is isolated");
        check("/shared.jpg".equals(resolved.backdrop), "known artwork is retained without network");
        check("com.netflix.ninja".equals(resolved.pkg), "focused history package remains authoritative");
        check("netflix".equals(resolved.app), "focused history app remains authoritative");
        check(resolved.link == null, "a cross-service deep link is never reused");
        check("Drama · 2h 4m · Netflix".equals(resolved.meta), "service metadata is rebound to the focused app");
        check("com.disney.disneyplus".equals(shared.pkg), "shared package is unchanged");
        check("disney".equals(shared.app), "shared app is unchanged");
        check("disney://title/22".equals(shared.link), "shared deep link is unchanged");
        check(WatchArtworkPolicy.knownPick(Arrays.asList(shared),
                new Recs.MediaChoice("tv", 22, "Other kind", "com.netflix.ninja", "netflix", "Netflix")) == null,
                "TMDB ids do not cross media kinds");
        check("Drama · 2 seasons · Netflix".equals(
                WatchArtworkPolicy.rebindMeta("Drama · 2 seasons · Disney+", "Netflix")),
                "network metadata is rebound to the focused service once");

        ProfileManager.Profile mahit = new ProfileManager.Profile("mahit", "Mahit", "manager", "", "");
        ProfileManager.Profile dhruvin = new ProfileManager.Profile("dhruvin", "Dhruvin", "standard", "", "");
        final ProfileManager.Profile[] active = {mahit};
        final Recs.MediaChoice mahitChoice = focused;
        final Recs.MediaChoice dhruvinChoice = new Recs.MediaChoice("tv", 44, "Other profile", "com.disney.disneyplus", "disney", "Disney+");
        ProfileManager.Profile captured = active[0];
        active[0] = dhruvin;
        List<Recs.MediaChoice> capturedChoices = WatchArtworkPolicy.captured(captured, new WatchArtworkPolicy.ProfileValues<List<Recs.MediaChoice>>() {
            @Override public List<Recs.MediaChoice> get(ProfileManager.Profile profile) {
                return profile.id.equals("mahit") ? Arrays.asList(mahitChoice) : Arrays.asList(dhruvinChoice);
            }
        });
        check(capturedChoices.get(0) == mahitChoice, "queued work reads the requesting profile after the active profile switches");

        System.out.println("RecsWatchTest: " + checks + " checks passed");
    }
}
