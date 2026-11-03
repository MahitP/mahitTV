package io.freemytv;

import java.net.URI;

/** The wallpaper catalogue and the policy shared with the TV web app. */
final class Wallpaper {
    static final class Aerial {
        final String id;
        final String asset;
        final String title;
        final String video;
        final String preview;

        Aerial(String id, String asset, String title, String video, String preview) {
            this.id = id;
            this.asset = asset;
            this.title = title;
            this.video = video;
            this.preview = preview;
        }
    }

    private static final String[] HORIZON_IDS = {"horizon:auto", "horizon:light", "horizon:dark"};
    /** Picker categories deliberately stay small and ordered: the TV UI mirrors this exact sequence. */
    private static final String[] CATEGORIES = {"horizon", "aerial", "studio", "live"};
    private static final String[] SOLID_IDS = {
            "studio:black", "studio:space-gray", "studio:midnight-blue", "studio:deep-forest",
            "studio:teal", "studio:warm-stone", "studio:silver-mist"
    };
    private static final int[] SOLID_COLORS = {
            0xff000000, 0xff1c1c1e, 0xff121a2a, 0xff11221c, 0xff1b3b3a, 0xff8e8e93, 0xffd1d5db
    };
    // x, y and radius as fractions of the whole screen, shared with the web renderer.
    private static final float[][] HORIZON_GEOMETRY = {
            {-0.08f, 0.69f, 0.83f},
            {0.53f, 0.48f, 0.78f},
            {1.06f, 1.04f, 1.04f}
    };
    /** Packages allowed to turn the Android Top Shelf into TV/movie artwork.  Keep this a deliberate allow-list. */
    private static final String[] VIDEO_APPS = {
            "com.netflix.ninja",
            "com.disney.disneyplus", "com.disney.starplus",
            "com.wbd.stream", "com.hbo.hbonow",
            "com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod",
            "com.google.android.youtube.tv", "com.amazon.firetv.youtube",
            "com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv", "com.apple.atve.amazon.appletv",
            "com.hulu.livingroomplus", "com.hulu.plus",
            "com.cbs.ott", "com.cbs.ca",
            "com.peacocktv.peacockandroid", "com.peacock.peacockfiretv",
            "com.crunchyroll.crunchyroid", "com.tubitv", "com.google.android.youtube.tvunplugged",
            "com.mvas.stb.emu.free", "com.mvas.stb.emu.pro"
    };
    /** Music sessions have their own Now Playing surface and must never request TMDB artwork. */
    private static final String[] MUSIC_APPS = {
            "com.apple.android.music", "com.spotify.tv.android", "com.spotify.music"
    };

    private static final Aerial[] AERIALS = {
            new Aerial("aerial:yosemite", "4109D42A-D717-46A7-A9A2-FE53A82B25C0", "Yosemite Valley",
                    "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/Y011_C001_SDR_2K_AVC.mov",
                    "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/Y011_C001_900x580.png"),
            new Aerial("aerial:iceland", "8590D0C5-E344-4FAC-A39A-FD7BC652AEDA", "Iceland Coast",
                    "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/I003_C004_SDR_2K_AVC.mov",
                    "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/I003_C004_900x580.png"),
            new Aerial("aerial:north-america-aurora", "737E9E24-49BE-4104-9B72-F352DE1AD2BF", "North America Aurora",
                    "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT314_139M_170NC_NORTH_AMERICA_AURORA__COMP_v22_SDR_20181206_v12CC_SDR_2K_AVC.mov",
                    "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/GMT314_139M_900x580.png"),
            new Aerial("aerial:palau-coral", "F07CC61B-30FC-4614-BDAD-3240B61F6793", "Palau Coral Colors",
                    "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/PA_A004_C003_SDR_20190719_SDR_2K_AVC.mov",
                    "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/PA_A004_C003_900x580.png"),
            new Aerial("aerial:new-york", "3BA0CFC7-E460-4B59-A817-B97F9EBB9B89", "New York from Above",
                    "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_N008_C009_PS_v01_SDR_PS_20180925_SDR_2K_AVC.mov",
                    "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/N008_C009_900x580.png"),
            new Aerial("aerial:sonoma-coast", "FE876489-CBD5-479B-A8F0-1B67F0741CEA", "Sonoma Horizon", "",
                    "https://sylvan.apple.com/itunes-assets/Aerials126/v4/51/ff/08/51ff0824-8da5-78f0-e218-9e61264965bb/W010_C003_F01@2x.png")
    };

    private Wallpaper() {}

    static String[] horizonIds() { return HORIZON_IDS.clone(); }

    static String categoryOf(String id) {
        if (id == null) return null;
        if (contains(HORIZON_IDS, id)) return "horizon";
        if (findCanonical(id) != null) return "aerial";
        if (contains(SOLID_IDS, id)) return "studio";
        return "live:aerial".equals(id) ? "live" : null;
    }

    /** Ordered picker IDs. Always return a fresh array so a view cannot mutate the catalogue. */
    static String[] options(String category) {
        if ("horizon".equals(category)) return HORIZON_IDS.clone();
        if ("studio".equals(category)) return SOLID_IDS.clone();
        if ("live".equals(category)) return new String[]{"live:aerial"};
        if ("aerial".equals(category)) {
            String[] ids = new String[AERIALS.length];
            for (int i = 0; i < AERIALS.length; i++) ids[i] = AERIALS[i].id;
            return ids;
        }
        return new String[0];
    }

    static String[] categories() { return CATEGORIES.clone(); }

    /** Allocation-free-at-draw-time state contract for the settings wallpaper picker. */
    static final class PickerState {
        static final int ENTER_OPTIONS = 1, APPLY = 2, BACK_TO_APPEARANCE = 3;
        static final int PREVIEW_HORIZON = 0, PREVIEW_LOADING = 1, PREVIEW_READY = 2, PREVIEW_ERROR = 3, PREVIEW_LIVE_FALLBACK = 4;
        private int category, option, preview;
        private boolean optionsPane;

        PickerState(String selected) { setSelected(selected); }
        String category() { return CATEGORIES[category]; }
        String selected() { return options(CATEGORIES[category])[option]; }
        boolean optionsPane() { return optionsPane; }
        int categoryIndex() { return category; }
        int optionIndex() { return option; }
        int preview() { return preview; }
        void setPreview(int preview) { this.preview = preview; }
        void setSelected(String selected) { setCategory(categoryOf(selected)); String[] choices = options(CATEGORIES[category]); for (int i = 0; i < choices.length; i++) if (choices[i].equals(selected)) option = i; }
        void setCategory(String id) { for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(id)) { category = i; break; } option = 0; optionsPane = false; preview = "live".equals(CATEGORIES[category]) ? PREVIEW_LIVE_FALLBACK : PREVIEW_HORIZON; }
        void moveCategory(int direction) { category = Math.max(0, Math.min(CATEGORIES.length - 1, category + direction)); option = 0; optionsPane = false; preview = "live".equals(CATEGORIES[category]) ? PREVIEW_LIVE_FALLBACK : PREVIEW_HORIZON; }
        void moveOption(int direction) { String[] choices = options(CATEGORIES[category]); option = Math.max(0, Math.min(choices.length - 1, option + direction)); }
        void optionsPane(boolean value) { optionsPane = value; }
        int activate() { if (!optionsPane) { optionsPane = true; return ENTER_OPTIONS; } return APPLY; }
        int back() { return BACK_TO_APPEARANCE; }
    }

    static String[] solidIds() { return SOLID_IDS.clone(); }

    static int[] solidColors() { return SOLID_COLORS.clone(); }

    /** Internal draw helper: callers get a scalar, never a mutable palette array. */
    static int solidColor(String id) {
        for (int i = 0; i < SOLID_IDS.length; i++) if (SOLID_IDS[i].equals(id)) return SOLID_COLORS[i];
        return SOLID_COLORS[0];
    }

    static float[][] horizonGeometry() {
        float[][] copy = new float[HORIZON_GEOMETRY.length][];
        for (int i = 0; i < HORIZON_GEOMETRY.length; i++) copy[i] = HORIZON_GEOMETRY[i].clone();
        return copy;
    }

    static float horizonVignette(boolean dark) { return dark ? 0.18f : 0.12f; }

    static Aerial[] aerials() {
        Aerial[] copy = new Aerial[AERIALS.length];
        for (int i = 0; i < AERIALS.length; i++) copy[i] = copy(AERIALS[i]);
        return copy;
    }

    static String normalize(String id) {
        if (valid(id)) return id;
        return "horizon:auto";
    }

    static boolean dark(String id, boolean weatherNight) {
        id = normalize(id);
        return "horizon:dark".equals(id) || (!"horizon:light".equals(id) && weatherNight);
    }

    static String legacyAsset(String id) {
        if ("aerial:yosemite".equals(id)) return "valley.jpg";
        if ("aerial:iceland".equals(id)) return "coast.jpg";
        if ("aerial:north-america-aurora".equals(id)) return "aurora.jpg";
        if ("aerial:new-york".equals(id)) return "earth.jpg";
        return "";
    }

    private static boolean valid(String id) {
        if ("live:aerial".equals(id)) return true;
        for (String horizon : HORIZON_IDS) if (horizon.equals(id)) return true;
        for (String solid : SOLID_IDS) if (solid.equals(id)) return true;
        return findCanonical(id) != null;
    }

    static Aerial find(String id) {
        Aerial aerial = findCanonical(id);
        return aerial == null ? null : copy(aerial);
    }

    /** A stable daily choice among playable AVC aerials; Sonoma intentionally remains a still. */
    static Aerial liveAerial(long day) {
        int playable = 0;
        for (Aerial aerial : AERIALS) if (!aerial.video.isEmpty()) playable++;
        if (playable == 0) return null;
        int target = (int) Math.floorMod(day, playable);
        for (Aerial aerial : AERIALS) if (!aerial.video.isEmpty() && target-- == 0) return copy(aerial);
        return null;
    }

    private static Aerial findCanonical(String id) {
        if (id == null) return null;
        for (Aerial aerial : AERIALS) if (aerial.id.equals(id)) return aerial;
        return null;
    }

    private static Aerial copy(Aerial aerial) {
        return new Aerial(aerial.id, aerial.asset, aerial.title, aerial.video, aerial.preview);
    }

    static boolean allowed(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || !"sylvan.apple.com".equals(uri.getHost())) return false;
        } catch (Exception e) {
            return false;
        }
        for (Aerial aerial : AERIALS) {
            if (value.equals(aerial.video) || value.equals(aerial.preview)) return true;
        }
        return false;
    }

    static int[] palette(boolean dark) {
        return dark ? new int[]{0xff0d131f, 0xff1c2638, 0xff151e2d, 0xff080b12}
                : new int[]{0xffb8c7d6, 0xfff5ebd6, 0xffdce6ef, 0xff7e9bb5};
    }

    static boolean media(String zone, boolean mediaApp) {
        return mediaApp && ("shelf".equals(zone) || "hero".equals(zone));
    }

    static boolean videoApp(String pkg) { return contains(VIDEO_APPS, pkg); }

    /** IPTV has no catalogue on TMDB: its Top Shelf shows what's in theaters. */
    static boolean theaterApp(String pkg) { return pkg != null && pkg.startsWith("com.mvas.stb.emu."); }

    /** YouTube isn't on TMDB: its Top Shelf shows videos (YouTubeFeed). */
    static boolean youTubeApp(String pkg) { return "com.google.android.youtube.tv".equals(pkg) || "com.amazon.firetv.youtube".equals(pkg); }

    static boolean musicApp(String pkg) { return contains(MUSIC_APPS, pkg); }

    /** A callback may mutate Top Shelf state only for the resolution it was started for. */
    static boolean current(long callbackGeneration, long backgroundGeneration) {
        return callbackGeneration == backgroundGeneration;
    }

    /**
     * The small state boundary shared by focus transitions and Top Shelf rendering. Profile entry clears it before
     * the next frame, so a previous title cannot survive a background change.
     */
    static final class HeroVisibility {
        private boolean media, current, outgoing, fading;

        void media() { media = true; }

        void profile() { media = current = outgoing = fading = false; }

        void track(boolean current, boolean outgoing, boolean fading) {
            this.current = current;
            this.outgoing = outgoing;
            this.fading = fading;
        }

        boolean draw() { return media; }
        boolean current() { return current; }
        boolean outgoing() { return outgoing; }
        boolean fading() { return fading; }
    }

    /** Profile identity belongs in the background callback key: an app package is shared by every profile. */
    static final class BackgroundFocus {
        private long generation;
        private String profile;

        long start(String profile) {
            this.profile = profile;
            return ++generation;
        }

        boolean current(long generation, String profile) {
            return this.generation == generation && (this.profile == null ? profile == null : this.profile.equals(profile));
        }
    }

    private static boolean contains(String[] packages, String pkg) {
        if (pkg == null) return false;
        for (String candidate : packages) if (candidate.equals(pkg)) return true;
        return false;
    }
}
