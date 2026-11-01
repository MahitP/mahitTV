package io.freemytv;

import java.util.Arrays;
import java.util.ArrayList;
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
        shared.service = "Disney+";
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
        check("Netflix".equals(resolved.service), "focused service field is rebound");
        check("com.disney.disneyplus".equals(shared.pkg), "shared package is unchanged");
        check("disney".equals(shared.app), "shared app is unchanged");
        check("disney://title/22".equals(shared.link), "shared deep link is unchanged");
        check(WatchArtworkPolicy.knownPick(Arrays.asList(shared),
                new Recs.MediaChoice("tv", 22, "Other kind", "com.netflix.ninja", "netflix", "Netflix")) == null,
                "TMDB ids do not cross media kinds");
        check("Drama · 2 seasons · Netflix".equals(
                WatchArtworkPolicy.rebindMeta("Drama · 2 seasons · Disney+", "Disney+", "Netflix")),
                "network metadata is rebound to the focused service once");
        check("Drama · Netflix".equals(WatchArtworkPolicy.rebindMeta("Drama · Netflix", "Netflix", "Netflix")),
                "same focused suffix is not duplicated");
        check("Series · Drama · 2 seasons · Netflix".equals(
                WatchArtworkPolicy.rebindMeta("Series · Drama · 2 seasons", "Disney+", "Netflix")),
                "a non-provider final segment is preserved before appending focus");
        check("Drama · Netflix".equals(WatchArtworkPolicy.rebindMeta("Drama", "Disney+", "Netflix")),
                "one metadata segment is preserved");
        check("Netflix".equals(WatchArtworkPolicy.rebindMeta("", "Disney+", "Netflix")),
                "empty metadata becomes the focused service");
        check("Drama".equals(WatchArtworkPolicy.rebindMeta("Drama · Disney+", "Disney+", "")),
                "empty focus removes an exact prior suffix");
        check("Drama · 2 seasons".equals(WatchArtworkPolicy.rebindMeta("Drama · 2 seasons", "Disney+", "")),
                "empty focus preserves a non-provider suffix");
        check("Drama · Netflix".equals(WatchArtworkPolicy.rebindMeta("Drama", "", "Netflix")),
                "missing prior service appends focus");

        final String[] activeProfile = {"mahit"};
        final List<Runnable> queued = new ArrayList<>(), posted = new ArrayList<>();
        final String[] readProfile = {null}, delivered = {null};
        final long[] deliveredGeneration = {-1};
        final int[] callbackCount = {0};
        WatchResolveCoordinator<String, String, String> coordinator = new WatchResolveCoordinator<>(
                () -> activeProfile[0], queued::add, posted::add,
                profile -> { readProfile[0] = profile; return profile + "-log"; },
                log -> log + "-result");
        coordinator.start(73, (generation, result) -> {
            callbackCount[0]++;
            deliveredGeneration[0] = generation;
            delivered[0] = result;
        });
        activeProfile[0] = "dhruvin";
        check(queued.size() == 1 && callbackCount[0] == 0, "resolution queues without delivering on the worker");
        queued.get(0).run();
        check("mahit".equals(readProfile[0]), "coordinator reads the synchronously captured requester");
        check(posted.size() == 1 && callbackCount[0] == 0, "worker completion is posted to main");
        posted.get(0).run();
        check(callbackCount[0] == 1 && deliveredGeneration[0] == 73 && "mahit-log-result".equals(delivered[0]),
                "main delivery is exactly once with unchanged generation");

        final List<Runnable> failedQueue = new ArrayList<>(), failedPosts = new ArrayList<>();
        final int[] failedCount = {0};
        WatchResolveCoordinator<String, String, String> failing = new WatchResolveCoordinator<>(
                () -> "mahit", failedQueue::add, failedPosts::add, profile -> profile + "-log",
                log -> { throw new Exception("details failed"); });
        failing.start(74, (generation, result) -> {
            failedCount[0]++;
            check(generation == 74 && result == null, "thrown detail failure preserves generation and returns null");
        });
        failedQueue.get(0).run();
        check(failedCount[0] == 0 && failedPosts.size() == 1, "failure is posted rather than delivered on worker");
        failedPosts.get(0).run();
        check(failedCount[0] == 1, "thrown failure delivers exactly once");

        final List<Runnable> rejectedPosts = new ArrayList<>();
        final int[] rejectedCount = {0};
        WatchResolveCoordinator<String, String, String> rejected = new WatchResolveCoordinator<>(
                () -> "mahit", command -> { throw new IllegalStateException("rejected"); }, rejectedPosts::add,
                profile -> profile + "-log", log -> log + "-result");
        rejected.start(75, (generation, result) -> {
            rejectedCount[0]++;
            check(generation == 75 && result == null, "executor rejection preserves generation and returns null");
        });
        check(rejectedCount[0] == 0 && rejectedPosts.size() == 1, "executor rejection still posts to main");
        rejectedPosts.get(0).run();
        check(rejectedCount[0] == 1, "executor rejection delivers exactly once");

        check(Recs.CACHE_VERSION == 7, "6 meant different things on each side: today's cache must not be parsed");

        // A new list reuses a title's details: the Top Shelf's must come along, or it shows a bare title for hours.
        Recs.Pick before = new Recs.Pick();
        before.id = 7;
        before.kind = "movie";
        before.title = "Kept";
        before.year = "2024";
        before.overview = "Synopsis";
        before.rating = 8.1;
        before.cert = "PG";
        before.logo = "/logo.png";
        before.badges = "4K";
        before.service = "Netflix";
        Recs.Pick again = new Recs.Pick();
        again.id = 7;
        again.kind = "movie";
        again.copyDetails(before);
        check("Kept".equals(again.title), "a title in the list before is reused");
        check("2024".equals(again.year) && "Synopsis".equals(again.overview) && again.rating == 8.1 && "PG".equals(again.cert)
                && "/logo.png".equals(again.logo) && "4K".equals(again.badges) && "Netflix".equals(again.service),
                "the Top Shelf's details come along when a title is reused");

        // Only a new title bounces the bars and is said to TalkBack: not a subtitle rewritten every second.
        check(NowPlaying.newTitle("Show", "Show · 0:01", true, null), "the first title counts");
        check(NowPlaying.newTitle("Next", "Next · 0:01", true, "Show"), "another title counts");
        check(!NowPlaying.newTitle("Show", "Show · 0:02", true, "Show"), "the same title with a new line doesn't");
        check(!NowPlaying.newTitle(null, null, true, "Show"), "nothing playing doesn't");
        check(!NowPlaying.newTitle("Next", "Next", false, "Show"), "not while the screen is hidden");

        // All Apps' system apps: a factory or service menu never, one OK there can reset the TV.
        check(SystemApps.listed(true, false, false), "Show system apps lists them");
        check(SystemApps.listed(false, true, false), "a named one is listed without it");
        check(!SystemApps.listed(false, false, false), "the others aren't");
        check(!SystemApps.listed(true, false, true), "never a factory menu with Show system apps");
        check(!SystemApps.listed(false, true, true), "never a factory menu by name either");

        System.out.println("RecsWatchTest: " + checks + " checks passed");
    }
}
