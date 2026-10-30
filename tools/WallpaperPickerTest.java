package io.freemytv;

/** Host-side contract for the SettingsActivity wallpaper picker state and its draw cache. */
public final class WallpaperPickerTest {
    private static int checks;

    public static void main(String[] args) {
        FakeCatalogue catalogue = new FakeCatalogue();
        FakeLoader loader = new FakeLoader();
        final String[] saved = {""};
        WallpaperPicker picker = new WallpaperPicker(catalogue, loader, id -> saved[0] = id, image -> loader.released++);

        picker.open("horizon:auto");
        equal(WallpaperPicker.Action.OPEN_OPTIONS, picker.ok(), "category OK opens options");
        equal("", saved[0], "category OK does not persist");
        picker.move(1);
        equal(WallpaperPicker.Action.PERSIST, picker.ok(), "option OK persists");
        equal("horizon:light", picker.selected(), "option selection is retained");
        equal("horizon:light", saved[0], "only option OK persists the selected option");
        equal(WallpaperPicker.Action.CLOSE, picker.back(), "options back returns to wallpaper row");
        equal("horizon:light", picker.selected(), "back retains the unmodified selection");

        picker.open("aerial:yosemite");
        picker.ok();
        equal(WallpaperPicker.Preview.LOADING, picker.draw().preview, "aerial begins loading");
        loader.ready("preview");
        equal(WallpaperPicker.Preview.READY, picker.draw().preview, "aerial ready state");
        loader.error();
        equal(WallpaperPicker.Preview.ERROR, picker.draw().preview, "aerial error state");

        picker.open("live:aerial");
        picker.ok();
        equal(WallpaperPicker.Preview.LIVE_FALLBACK, picker.draw().preview, "live uses distinct Horizon fallback");
        equal(0xffb8c7d6, picker.draw().color, "live fallback retains Horizon palette");

        picker.open("aerial:yosemite");
        picker.ok();
        Object closedImage = new Object();
        WallpaperPicker.Callback beforeClose = loader.last();
        int released = loader.released;
        picker.close();
        loader.ready(beforeClose, closedImage);
        same(null, picker.draw().image, "closed picker ignores a late preview");
        equal(released + 1, loader.released, "closed picker relinquishes late preview");

        picker.open("aerial:yosemite");
        picker.ok();
        WallpaperPicker.Callback stale = loader.last();
        picker.move(1);
        WallpaperPicker.Callback current = loader.last();
        loader.ready(stale, new Object());
        same(null, picker.draw().image, "rapid selection ignores stale preview");
        loader.ready(current, "current");
        equal("current", picker.draw().image, "latest preview wins");

        int categories = catalogue.categories, options = catalogue.options, colors = catalogue.colors, aerials = catalogue.aerials;
        WallpaperPicker.DrawData first = picker.draw();
        WallpaperPicker.DrawData second = picker.draw();
        same(first, second, "draw data is cached");
        equal(categories, catalogue.categories, "draw does not call categories getter");
        equal(options, catalogue.options, "draw does not call options getter");
        equal(colors, catalogue.colors, "draw does not call color getter");
        equal(aerials, catalogue.aerials, "draw does not call aerial getter");

        WallpaperPicker production = new WallpaperPicker((aerial, callback) -> {}, id -> {}, image -> {});
        production.open("studio:teal");
        equal(0xff1b3b3a, production.draw().color, "production picker uses the exact Studio color");
        production.open("horizon:light");
        equal(0xffb8c7d6, production.draw().color, "production picker uses the Horizon palette");
        System.out.println("WallpaperPickerTest: " + checks + " checks passed");
    }

    private static final class FakeCatalogue implements WallpaperPicker.Catalogue {
        int categories, options, colors, aerials;
        public String[] categories() { categories++; return new String[]{"horizon", "aerial", "studio", "live"}; }
        public String[] options(String category) { options++; return "horizon".equals(category) ? new String[]{"horizon:auto", "horizon:light"}
                : "aerial".equals(category) ? new String[]{"aerial:yosemite", "aerial:iceland"} : "live".equals(category) ? new String[]{"live:aerial"} : new String[]{"studio:teal"}; }
        public int color(String id) { colors++; return "studio:teal".equals(id) ? 0xff1b3b3a : 0xffb8c7d6; }
        public Wallpaper.Aerial aerial(String id) { aerials++; return id.startsWith("aerial:") ? Wallpaper.find(id) : null; }
        public Wallpaper.Aerial live() { aerials++; return null; }
    }

    private static final class FakeLoader implements WallpaperPicker.Loader {
        final WallpaperPicker.Callback[] callbacks = new WallpaperPicker.Callback[16];
        int count, released;
        public void load(Wallpaper.Aerial aerial, WallpaperPicker.Callback callback) { callbacks[count++] = callback; }
        WallpaperPicker.Callback last() { return callbacks[count - 1]; }
        void ready(Object image) { last().ready(image); }
        void ready(WallpaperPicker.Callback callback, Object image) { callback.ready(image); }
        void error() { last().error(); }
    }
    private static void equal(Object want, Object got, String message) { checks++; if (!want.equals(got)) throw new AssertionError(message + ": wanted " + want + ", got " + got); }
    private static void equal(int want, int got, String message) { checks++; if (want != got) throw new AssertionError(message + ": wanted " + want + ", got " + got); }
    private static void same(Object want, Object got, String message) { checks++; if (want != got) throw new AssertionError(message); }
}
