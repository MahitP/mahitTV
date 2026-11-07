package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names for a new folder, best first (in English; the home screen translates them): the brand its apps share, else
 * what they're for. Apart from HomeView so it can be tested without a TV.
 */
final class FolderNames {
    private FolderNames() {}

    /** What an app is for: the name, other names for the same, then the words that tell it (package and name). */
    private static final String[][] KINDS = {
        {"Kids", "Family", "kids|disney ?jr|pbs ?kids|nick ?jr|nickelodeon|cartoon|noggin|sesame|cocomelon|boomerang|toon"},
        {"Live TV", "Channels", "live ?tv|youtube ?tv|tvunplugged|\\bsling|pluto|freeplay|channels|tcl ?tv|tclplus|xumo|local ?now"
                + "|antenna|tvcenter|wwtv|\\bphilo\\b|directv|stb ?emu|stbemu|iptv|tivimate|smarters|^com\\.tcl\\.tv "},
        {"Sports", "espn|\\bnfl\\b|\\bnba\\b|\\bmlb\\b|\\bnhl\\b|\\bmls\\b|dazn|sports|f1 ?tv|formula ?1|\\bufc\\b|\\bwwe\\b|golf|tennis|fubo|\\bbein|fanduel"},
        {"News", "news|cnn|bbc|msnbc|bloomberg|haystack|reuters|weather"},
        {"Music", "Listen", "music|spotify|pandora|tidal|deezer|soundcloud|iheart|tunein|siriusxm|shazam|vevo|stingray|karaoke"
                + "|qobuz|napster|audiomack|radio|podcast|audible"},
        {"Games", "Play", "game|roblox|steam|xbox|geforce|luna|stadia|minecraft|asphalt|retroarch|emulat|playstation|chess"
                + "|arcade|solitaire|twitch"},
        {"Fitness", "Health", "fitness|peloton|workout|yoga|nike ?training|les ?mills|beachbody|strava|zwift|calm|headspace|meditat"},
        {"Learning", "Explore", "\\bted\\b|khan|coursera|masterclass|udemy|duolingo|curiosity|nebula|great ?courses|wondrium"
                + "|nat ?geo|nasa|learn"},
        {"Photos", "Memories", "photo|gallery|immich|flickr|picture|lightroom"},
        {"Social", "instagram|tiktok|musically|facebook|twitter|snapchat|discord|reddit|whatsapp|telegram|threads|pinterest"
                + "|zoom|messenger|skype"},
        {"Shopping", "shop|ebay|\\bqvc\\b|\\bhsn\\b|walmart|etsy"},
        {"Utilities", "Tools", "settings|file|documentsui|browser|browse|download|clean|vpn|speed ?test|airplay|media ?player"
                + "|mediacenter|manual|user ?guide|instructions|store|alexa|remote|keyboard|launcher|quick ?panel|t-?solo"
                + "|exhibit|dashboard|tcl ?home|freemytv|mahittv|tools|system|update|bluetooth|backup|terminal"},
        {"Streaming", "Movies & TV", "Watch", "netflix|prime ?video|amazonvideo|\\bavod|firebat|hulu|disney|\\bhbo|\\bmax\\b"
                + "|wbd\\.stream|peacock|paramount|cbs\\.|tubi|crunchyroll|funimation|hidive|apple ?tv|appletv|atve|plex"
                + "|jellyfin|emby|kodi|vudu|fandango|starz|showtime|britbox|mubi|acorn|discovery|\\bamc\\b|shudder|criterion"
                + "|roku|freevee|kanopy|hoopla|rakuten|viki|youtube|smarttube|video|movie|cinema|film|stream|\\btv\\b"},
    };
    private static final Pattern[] KIND_WORDS = new Pattern[KINDS.length];
    static {
        for (int i = 0; i < KINDS.length; i++) KIND_WORDS[i] = Pattern.compile(KINDS[i][KINDS[i].length - 1]);
    }

    /** Two kinds together: one name for both. */
    private static final String[][] BOTH = {
        {"Streaming", "Live TV", "TV"}, {"Streaming", "Kids", "Family"}, {"Streaming", "Music", "Entertainment"},
        {"Streaming", "Games", "Entertainment"}, {"Streaming", "Sports", "Watch"}, {"Sports", "Live TV", "Sports"},
        {"News", "Live TV", "News"}, {"Games", "Kids", "Fun"}, {"Music", "Fitness", "Fitness"}, {"Music", "Games", "Fun"},
    };

    /** Makers whose apps share a package prefix (com.<maker>.…), by what the prefix says. */
    private static final Map<String, String> MAKERS = new HashMap<>();
    static {
        String[][] m = {{"google", "Google"}, {"amazon", "Amazon"}, {"apple", "Apple"}, {"tcl", "TCL"}, {"disney", "Disney"},
            {"microsoft", "Microsoft"}, {"nvidia", "NVIDIA"}, {"sony", "Sony"}, {"samsung", "Samsung"}, {"guide", "Apple"}};
        for (String[] p : m) MAKERS.put(p[0], p[1]);
    }

    /** First words two apps can share without that being a brand. */
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList("the", "my", "live", "tv", "smart", "media", "video",
            "music", "quick", "user", "works", "home", "new", "free", "play", "app", "apps", "android", "web", "watch"));

    private static final String[] FALLBACK = {"Favorites", "Entertainment", "My Apps"};

    /** Names for a folder of these apps (their names and packages, in the same order; a guide's key as its package). */
    static List<String> suggest(List<String> labels, List<String> pkgs) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        String brand = brand(labels, pkgs);
        if (brand != null) out.put(brand, true);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int unknown = 0;
        for (int i = 0; i < labels.size(); i++) {
            int k = kind(labels.get(i), i < pkgs.size() ? pkgs.get(i) : null);
            if (k < 0) unknown++;
            else counts.put(k, counts.containsKey(k) ? counts.get(k) + 1 : 1);
        }
        List<Integer> kinds = new ArrayList<>(counts.keySet());
        kinds.sort((a, b) -> counts.get(b) - counts.get(a));
        if (kinds.size() >= 2) {
            String both = both(KINDS[kinds.get(0)][0], KINDS[kinds.get(1)][0]);
            if (both != null) out.put(both, true);
            for (int k : kinds) out.put(KINDS[k][0], true);
        } else if (kinds.size() == 1) {
            String[] names = KINDS[kinds.get(0)];
            for (int i = 0; i < names.length - 1; i++) out.put(names[i], true);
        }
        for (String f : FALLBACK) out.put(f, true);
        return new ArrayList<>(out.keySet());
    }

    private static String both(String a, String b) {
        for (String[] p : BOTH) if ((p[0].equals(a) && p[1].equals(b)) || (p[0].equals(b) && p[1].equals(a))) return p[2];
        return null;
    }

    /** The first kind whose words the app's package or name has, or -1. */
    private static int kind(String label, String pkg) {
        String text = ((pkg == null ? "" : pkg) + " " + (label == null ? "" : label)).toLowerCase(Locale.ROOT);
        for (int i = 0; i < KIND_WORDS.length; i++) if (KIND_WORDS[i].matcher(text).find()) return i;
        return -1;
    }

    /** The brand every app shares: the same first word of the name (YouTube, TCL), else the same maker (Amazon). */
    private static String brand(List<String> labels, List<String> pkgs) {
        if (labels.size() < 2) return null;
        String word = null;
        for (String l : labels) {
            String w = l == null ? "" : l.trim().split("\\s+")[0];
            if (word == null) word = w;
            else if (!word.equalsIgnoreCase(w)) {
                word = null;
                break;
            }
        }
        if (word != null && word.length() >= 2 && !COMMON.contains(word.toLowerCase(Locale.ROOT))) return word;
        String maker = null;
        for (String p : pkgs) {
            String m = MAKERS.get(maker(p));
            if (m == null || (maker != null && !maker.equals(m))) return null;
            maker = m;
        }
        return maker;
    }

    /** "com.amazon.music.tv" → "amazon"; "guide:facetime" → "guide". */
    private static String maker(String pkg) {
        if (pkg == null) return "";
        if (pkg.startsWith("guide:")) return "guide";
        String[] parts = pkg.split("\\.");
        return parts.length >= 2 ? parts[1] : "";
    }
}
