package io.freemytv;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** A new folder's suggested names: the brand two apps share, else what they're for, best first. */
public final class FolderNamesTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> names(String... pairs) {
        String[] labels = new String[pairs.length / 2], pkgs = new String[pairs.length / 2];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = pairs[2 * i];
            pkgs[i] = pairs[2 * i + 1];
        }
        return FolderNames.suggest(Arrays.asList(labels), Arrays.asList(pkgs));
    }

    private static void first(List<String> got, String expected, String message) {
        check(!got.isEmpty() && got.get(0).equals(expected), message + ": expected " + expected + " first, got " + got);
    }

    public static void main(String[] args) {
        first(names("Netflix", "com.netflix.ninja", "Hulu", "com.hulu.livingroomplus"), "Streaming", "two film and series apps");
        first(names("YouTube", "com.google.android.youtube.tv", "YouTube Music", "com.google.android.youtube.tvmusic"), "YouTube",
                "the same brand's apps");
        first(names("Spotify", "com.spotify.tv.android", "Apple Music", "com.apple.android.music"), "Music", "two music apps");
        first(names("Roblox", "com.roblox.client", "Twitch", "tv.twitch.android.app"), "Games", "games and gaming");
        first(names("Prime Video", "com.amazon.amazonvideo.livingroom", "Amazon Music", "com.amazon.music.tv"), "Amazon",
                "the same maker's apps, named differently");
        first(names("ESPN", "com.espn.gtv", "YouTube TV", "com.google.android.youtube.tvunplugged"), "Sports", "sports and live TV");
        first(names("TCL Home", "com.tcl.dashboard", "TCL TV+", "com.tcl.tv.plus"), "TCL", "a maker's own apps");
        first(names("Instagram", "com.instagram.airwave", "TikTok", "com.zhiliaoapp.musically"), "Social", "social apps");
        first(names("Netflix", "com.netflix.ninja", "Spotify", "com.spotify.tv.android"), "Entertainment", "films and music");
        first(names("Disney+", "com.disney.disneyplus", "YouTube Kids", "com.google.android.youtube.tvkids"), "Family", "for the kids");
        first(names("FaceTime", "guide:facetime", "Podcasts", "guide:podcasts"), "Apple", "Apple's guides");
        first(names("Files", "com.android.documentsui", "Settings", "com.android.tv.settings"), "Utilities", "the TV's tools");
        first(names("YouTube", "youtube.leanback.v4", "Spotify", "spotify-beehive"), "Entertainment",
                "a short word inside another (lea-nba-ck) isn't one: YouTube is no sports app");
        first(names("StbEmu", "com.mvas.stb.emu.pro", "Pluto TV", "tv.pluto.android"), "Live TV", "channels");

        for (List<String> got : Arrays.asList(names("Netflix", "com.netflix.ninja", "Hulu", "com.hulu.plus"),
                names("Aaa", "com.example.aaa", "Bbb", "org.example.bbb"), names("Spotify", "com.spotify.tv.android"))) {
            check(got.size() >= 3, "always a few to choose from: " + got);
            check(new HashSet<>(got).size() == got.size(), "never the same twice: " + got);
            for (String n : got) check(!n.trim().isEmpty() && n.length() <= 20, "short names: " + n);
        }
        check(!names("Live TV", "com.tcl.tv", "Live Sports", "com.example.live").contains("Live"), "a shared common word isn't a brand");
        System.out.println("FolderNamesTest: " + checks + " checks passed");
    }
}
