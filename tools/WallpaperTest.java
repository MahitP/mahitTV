package io.freemytv;

import java.util.Arrays;

public final class WallpaperTest {
    private static int checks;

    public static void main(String[] args) {
        validIdsAreStable();
        legacyValuesMigrate();
        legacyAssetsAreExact();
        horizonModeSelectsAtmosphere();
        palettesMatchTheContract();
        horizonGeometryIsExact();
        mediaPolicyOnlyAllowsFocusedMedia();
        androidFocusPolicyIsExplicit();
        aerialCatalogueIsExact();
        urlAllowlistIsExact();
        solidsAreExact();
        System.out.println("WallpaperTest: " + checks + " checks passed");
    }

    private static void validIdsAreStable() {
        String[] ids = {
                "horizon:auto", "horizon:light", "horizon:dark",
                "aerial:yosemite", "aerial:iceland", "aerial:north-america-aurora",
                "aerial:palau-coral", "aerial:new-york", "aerial:sonoma-coast",
                "studio:black", "studio:space-gray", "studio:midnight-blue", "studio:deep-forest",
                "studio:teal", "studio:warm-stone", "studio:silver-mist", "live:aerial"
        };
        for (String id : ids) equal(id, Wallpaper.normalize(id), "valid id " + id);
        equal("horizon:auto", Wallpaper.normalize(null), "null default");
        equal("horizon:auto", Wallpaper.normalize(""), "empty default");
        equal("horizon:auto", Wallpaper.normalize("not-a-wallpaper"), "unknown default");
        equal("horizon:auto", Wallpaper.normalize("HORIZON:AUTO"), "ids are case-sensitive");
    }

    private static void legacyValuesMigrate() {
        equal("horizon:auto", Wallpaper.normalize("valley.jpg"), "valley migration");
        equal("horizon:auto", Wallpaper.normalize("coast.jpg"), "coast migration");
        equal("horizon:auto", Wallpaper.normalize("aurora.jpg"), "aurora migration");
        equal("horizon:auto", Wallpaper.normalize("earth.jpg"), "removed Earth migration");
    }

    private static void legacyAssetsAreExact() {
        equal("valley.jpg", Wallpaper.legacyAsset("aerial:yosemite"), "Yosemite legacy asset");
        equal("coast.jpg", Wallpaper.legacyAsset("aerial:iceland"), "Iceland legacy asset");
        equal("aurora.jpg", Wallpaper.legacyAsset("aerial:north-america-aurora"), "aurora legacy asset");
        equal("earth.jpg", Wallpaper.legacyAsset("aerial:new-york"), "New York legacy asset");
        equal("", Wallpaper.legacyAsset("horizon:auto"), "Horizon has no legacy asset");
        equal("", Wallpaper.legacyAsset("studio:teal"), "studio has no legacy asset");
        equal("", Wallpaper.legacyAsset("live:aerial"), "live has no legacy asset");
        equal("", Wallpaper.legacyAsset("aerial:palau-coral"), "Palau has no bundled asset");
        equal("", Wallpaper.legacyAsset("aerial:sonoma-coast"), "Sonoma has no bundled asset");
        equal("", Wallpaper.legacyAsset("missing"), "unknown has no legacy asset");
        equal("", Wallpaper.legacyAsset(null), "null has no legacy asset");
    }

    private static void horizonModeSelectsAtmosphere() {
        no(Wallpaper.dark("horizon:light", true), "fixed light ignores night");
        yes(Wallpaper.dark("horizon:dark", false), "fixed dark ignores day");
        no(Wallpaper.dark("horizon:auto", false), "auto follows day");
        yes(Wallpaper.dark("horizon:auto", true), "auto follows night");
        no(Wallpaper.dark("studio:teal", false), "non-Horizon follows day");
        yes(Wallpaper.dark("studio:teal", true), "non-Horizon follows night");
        no(Wallpaper.dark("valley.jpg", false), "legacy normalizes to auto day");
        yes(Wallpaper.dark("valley.jpg", true), "legacy normalizes to auto night");
    }

    private static void palettesMatchTheContract() {
        array(new int[]{0xff0d131f, 0xff1c2638, 0xff151e2d, 0xff080b12}, Wallpaper.palette(true), "dark palette");
        array(new int[]{0xffb8c7d6, 0xfff5ebd6, 0xffdce6ef, 0xff7e9bb5}, Wallpaper.palette(false), "light palette");
        int[] changed = Wallpaper.palette(true);
        changed[0] = 0;
        equal(0xff0d131f, Wallpaper.palette(true)[0], "palette is isolated from callers");
    }

    private static void horizonGeometryIsExact() {
        float[][] expected = {
                {-0.08f, 0.69f, 0.83f},
                {0.53f, 0.48f, 0.78f},
                {1.06f, 1.04f, 1.04f}
        };
        float[][] geometry = Wallpaper.horizonGeometry();
        equal(expected.length, geometry.length, "Horizon layer count");
        for (int i = 0; i < expected.length; i++) {
            floats(expected[i], geometry[i], "Horizon geometry " + i);
        }
        equal(0.12f, Wallpaper.horizonVignette(false), "light Horizon vignette");
        equal(0.18f, Wallpaper.horizonVignette(true), "dark Horizon vignette");

        geometry[0][0] = 42;
        geometry[1] = new float[] {42, 42, 42};
        floats(expected[0], Wallpaper.horizonGeometry()[0], "Horizon geometry rows are isolated");
        floats(expected[1], Wallpaper.horizonGeometry()[1], "Horizon geometry array is isolated");
    }

    private static void mediaPolicyOnlyAllowsFocusedMedia() {
        yes(Wallpaper.media("shelf", true), "media shelf");
        yes(Wallpaper.media("hero", true), "media hero");
        no(Wallpaper.media("shelf", false), "non-media shelf");
        no(Wallpaper.media("hero", false), "non-media hero");
        no(Wallpaper.media("dock", true), "other zone");
        no(Wallpaper.media(null, true), "null zone");
    }

    private static void androidFocusPolicyIsExplicit() {
        yes(Wallpaper.videoApp("com.netflix.ninja"), "Netflix is video");
        yes(Wallpaper.videoApp("com.disney.disneyplus"), "Disney+ is video");
        yes(Wallpaper.videoApp("com.wbd.stream"), "Max is video");
        yes(Wallpaper.videoApp("com.amazon.amazonvideo.livingroom"), "Prime Video is video");
        yes(Wallpaper.videoApp("com.google.android.youtube.tv"), "YouTube is video");
        yes(Wallpaper.videoApp("com.apple.atve.androidtv.appletv"), "Apple TV is video");
        no(Wallpaper.videoApp("com.apple.android.music"), "Apple Music is not TMDB video");
        no(Wallpaper.videoApp("com.spotify.tv.android"), "Spotify is not TMDB video");
        no(Wallpaper.videoApp("io.freemytv"), "Settings is not video");
        no(Wallpaper.videoApp(null), "null package is not video");
        yes(Wallpaper.musicApp("com.apple.android.music"), "Apple Music is music");
        yes(Wallpaper.musicApp("com.spotify.tv.android"), "Spotify is music");
        no(Wallpaper.musicApp("com.netflix.ninja"), "Netflix is not music");
        yes(Wallpaper.media("shelf", Wallpaper.videoApp("com.netflix.ninja")), "video shelf gets media");
        yes(Wallpaper.media("hero", Wallpaper.videoApp("com.netflix.ninja")), "video hero gets media");
        no(Wallpaper.media("grid", Wallpaper.videoApp("com.netflix.ninja")), "grid never gets media");
        no(Wallpaper.media("shelf", Wallpaper.videoApp("io.freemytv")), "Settings shelf never gets media");
        yes(Wallpaper.current(7, 7), "matching generation is current");
        no(Wallpaper.current(6, 7), "stale generation is rejected");
    }

    private static void aerialCatalogueIsExact() {
        String[][] expected = {
                {"aerial:yosemite", "4109D42A-D717-46A7-A9A2-FE53A82B25C0", "Yosemite Valley", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/Y011_C001_SDR_2K_AVC.mov", "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/Y011_C001_900x580.png"},
                {"aerial:iceland", "8590D0C5-E344-4FAC-A39A-FD7BC652AEDA", "Iceland Coast", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/I003_C004_SDR_2K_AVC.mov", "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/I003_C004_900x580.png"},
                {"aerial:north-america-aurora", "737E9E24-49BE-4104-9B72-F352DE1AD2BF", "North America Aurora", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT314_139M_170NC_NORTH_AMERICA_AURORA__COMP_v22_SDR_20181206_v12CC_SDR_2K_AVC.mov", "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/GMT314_139M_900x580.png"},
                {"aerial:palau-coral", "F07CC61B-30FC-4614-BDAD-3240B61F6793", "Palau Coral Colors", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/PA_A004_C003_SDR_20190719_SDR_2K_AVC.mov", "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/PA_A004_C003_900x580.png"},
                {"aerial:new-york", "3BA0CFC7-E460-4B59-A817-B97F9EBB9B89", "New York from Above", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_N008_C009_PS_v01_SDR_PS_20180925_SDR_2K_AVC.mov", "https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/N008_C009_900x580.png"},
                {"aerial:sonoma-coast", "FE876489-CBD5-479B-A8F0-1B67F0741CEA", "Sonoma Horizon", "", "https://sylvan.apple.com/itunes-assets/Aerials126/v4/51/ff/08/51ff0824-8da5-78f0-e218-9e61264965bb/W010_C003_F01@2x.png"}
        };
        Wallpaper.Aerial[] aerials = Wallpaper.aerials();
        equal(expected.length, aerials.length, "aerial count");
        for (int i = 0; i < expected.length; i++) {
            Wallpaper.Aerial aerial = aerials[i];
            equal(expected[i][0], aerial.id, "aerial id " + i);
            equal(expected[i][1], aerial.asset, "aerial asset " + i);
            equal(expected[i][2], aerial.title, "aerial title " + i);
            equal(expected[i][3], aerial.video, "aerial video " + i);
            equal(expected[i][4], aerial.preview, "aerial preview " + i);
            aerial(expected[i], Wallpaper.find(aerial.id), "find " + aerial.id);
        }
        same(null, Wallpaper.find("horizon:auto"), "find non-aerial");
        same(null, Wallpaper.find("missing"), "find missing");
        same(null, Wallpaper.find(null), "find null");
    }

    private static void urlAllowlistIsExact() {
        for (Wallpaper.Aerial aerial : Wallpaper.aerials()) {
            if (!aerial.video.isEmpty()) yes(Wallpaper.allowed(aerial.video), "allowed video " + aerial.id);
            yes(Wallpaper.allowed(aerial.preview), "allowed preview " + aerial.id);
        }
        no(Wallpaper.allowed(null), "reject null");
        no(Wallpaper.allowed(""), "reject empty");
        no(Wallpaper.allowed("not a url"), "reject malformed");
        String good = Wallpaper.aerials()[0].preview;
        no(Wallpaper.allowed(good.replace("https://", "http://")), "reject http");
        no(Wallpaper.allowed(good.replace("sylvan.apple.com", "example.com")), "reject foreign host");
        no(Wallpaper.allowed(good.replace("sylvan.apple.com", "sylvan.apple.com.example.com")), "reject host suffix");
        no(Wallpaper.allowed("https://example.com/https://sylvan.apple.com/"), "reject host in path");
        no(Wallpaper.allowed(good + "?download=1"), "reject url suffix");
        no(Wallpaper.allowed(good.substring(0, good.length() - 1)), "reject url prefix");
        no(Wallpaper.allowed("https://sylvan.apple.com/"), "reject uncatalogued url");
    }

    private static void solidsAreExact() {
        String[] ids = {"studio:black", "studio:space-gray", "studio:midnight-blue", "studio:deep-forest", "studio:teal", "studio:warm-stone", "studio:silver-mist"};
        int[] colors = {0xff000000, 0xff1c1c1e, 0xff121a2a, 0xff11221c, 0xff1b3b3a, 0xff8e8e93, 0xffd1d5db};
        yes(Arrays.equals(ids, Wallpaper.solidIds()), "solid ids");
        array(colors, Wallpaper.solidColors(), "solid colors");
        yes(Arrays.equals(new String[]{"horizon:auto", "horizon:light", "horizon:dark"}, Wallpaper.horizonIds()), "horizon ids");

        Wallpaper.Aerial[] aerials = Wallpaper.aerials();
        String yosemitePreview = aerials[0].preview;
        aerials[0] = new Wallpaper.Aerial("aerial:changed", "changed", "Changed", "https://sylvan.apple.com/changed.mov", "https://sylvan.apple.com/changed.png");
        equal("aerial:yosemite", Wallpaper.aerials()[0].id, "aerial array is isolated");
        equal("aerial:yosemite", Wallpaper.normalize("aerial:yosemite"), "normalize uses canonical aerials");
        yes(Wallpaper.allowed(yosemitePreview), "allowlist survives exported array mutation");
        no(Wallpaper.allowed("https://sylvan.apple.com/changed.mov"), "exported record cannot widen allowlist");

        String[] changedIds = Wallpaper.solidIds();
        changedIds[0] = "studio:changed";
        int[] changedColors = Wallpaper.solidColors();
        changedColors[0] = 0;
        equal("studio:black", Wallpaper.solidIds()[0], "solid ids are isolated");
        equal(0xff000000, Wallpaper.solidColors()[0], "solid colors are isolated");
        equal("studio:black", Wallpaper.normalize("studio:black"), "normalize uses canonical solids");

        String[] changedHorizons = Wallpaper.horizonIds();
        changedHorizons[0] = "horizon:changed";
        equal("horizon:auto", Wallpaper.horizonIds()[0], "horizon ids are isolated");
        equal("horizon:auto", Wallpaper.normalize("horizon:auto"), "normalize uses canonical horizons");

        Wallpaper.Aerial found = Wallpaper.find("aerial:yosemite");
        no(found == Wallpaper.find("aerial:yosemite"), "find returns a defensive record");
    }

    private static void aerial(String[] expected, Wallpaper.Aerial actual, String message) {
        checks++;
        if (actual == null || !Arrays.equals(expected, new String[]{actual.id, actual.asset, actual.title, actual.video, actual.preview})) {
            fail(message, Arrays.toString(expected), actual == null ? null : actual.id);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) fail(message, expected, actual);
    }

    private static void equal(int expected, int actual, String message) {
        checks++;
        if (expected != actual) fail(message, expected, actual);
    }

    private static void array(int[] expected, int[] actual, String message) {
        checks++;
        if (!Arrays.equals(expected, actual)) fail(message, Arrays.toString(expected), Arrays.toString(actual));
    }

    private static void floats(float[] expected, float[] actual, String message) {
        checks++;
        if (!Arrays.equals(expected, actual)) fail(message, Arrays.toString(expected), Arrays.toString(actual));
    }

    private static void equal(float expected, float actual, String message) {
        checks++;
        if (Float.compare(expected, actual) != 0) fail(message, expected, actual);
    }

    private static void same(Object expected, Object actual, String message) {
        checks++;
        if (expected != actual) fail(message, expected, actual);
    }

    private static void yes(boolean actual, String message) {
        checks++;
        if (!actual) fail(message, true, false);
    }

    private static void no(boolean actual, String message) {
        checks++;
        if (actual) fail(message, false, true);
    }

    private static void fail(String message, Object expected, Object actual) {
        throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
