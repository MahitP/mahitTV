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

    static final String[] HORIZON_IDS = {"horizon:auto", "horizon:light", "horizon:dark"};
    static final String[] SOLID_IDS = {
            "studio:black", "studio:space-gray", "studio:midnight-blue", "studio:deep-forest",
            "studio:teal", "studio:warm-stone", "studio:silver-mist"
    };
    static final int[] SOLID_COLORS = {
            0xff000000, 0xff1c1c1e, 0xff121a2a, 0xff11221c, 0xff1b3b3a, 0xff8e8e93, 0xffd1d5db
    };

    static final Aerial[] AERIALS = {
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

    static String normalize(String id) {
        if (valid(id)) return id;
        return "horizon:auto";
    }

    private static boolean valid(String id) {
        if ("live:aerial".equals(id)) return true;
        for (String horizon : HORIZON_IDS) if (horizon.equals(id)) return true;
        for (String solid : SOLID_IDS) if (solid.equals(id)) return true;
        return find(id) != null;
    }

    static Aerial find(String id) {
        if (id == null) return null;
        for (Aerial aerial : AERIALS) if (aerial.id.equals(id)) return aerial;
        return null;
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
}
