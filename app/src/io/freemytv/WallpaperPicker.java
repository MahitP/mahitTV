package io.freemytv;

/** Cached state for SettingsActivity's wallpaper picker; drawing reads only its immutable DrawData. */
final class WallpaperPicker {
    enum Action { OPEN_OPTIONS, PERSIST, CLOSE }
    enum Preview { HORIZON, LOADING, READY, ERROR, LIVE_LOADING, LIVE_READY, LIVE_FALLBACK }

    interface Catalogue {
        String[] categories();
        String[] options(String category);
        int color(String id);
        Wallpaper.Aerial aerial(String id);
        Wallpaper.Aerial live();
    }
    interface Callback { boolean current(); void ready(Object image); void error(); }
    interface Loader { void load(Wallpaper.Aerial aerial, Callback callback); }
    interface Saver { void save(String id); }
    interface Releaser { void release(Object image); }

    static final class DrawData {
        final String[] categories, options;
        final int category, option, color;
        final boolean optionsPane;
        final String selected, status;
        final Preview preview;
        final Object image;
        DrawData(String[] categories, String[] options, int category, int option, boolean optionsPane,
                 String selected, int color, Preview preview, String status, Object image) {
            this.categories = categories; this.options = options; this.category = category; this.option = option;
            this.optionsPane = optionsPane; this.selected = selected; this.color = color;
            this.preview = preview; this.status = status; this.image = image;
        }
    }

    private static final Catalogue DEFAULT = new Catalogue() {
        public String[] categories() { return Wallpaper.categories(); }
        public String[] options(String category) { return Wallpaper.options(category); }
        public int color(String id) {
            if (id.startsWith("studio:")) {
                String[] ids = Wallpaper.solidIds(); int[] colors = Wallpaper.solidColors();
                for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return colors[i];
            }
            return Wallpaper.palette(Wallpaper.dark(id, false))[0];
        }
        public Wallpaper.Aerial aerial(String id) { return Wallpaper.find(id); }
        public Wallpaper.Aerial live() { return Wallpaper.liveAerial(System.currentTimeMillis() / 86_400_000L); }
    };

    private final Catalogue catalogue;
    private final Loader loader;
    private final Saver saver;
    private final Releaser releaser;
    private String[] categories = new String[0], options = new String[0];
    private int category, option, loadGeneration;
    private boolean optionsPane;
    private Preview preview = Preview.HORIZON;
    private String status = "Frosted Horizon";
    private Object image;
    private DrawData draw;
    private boolean closed;

    WallpaperPicker(Loader loader, Saver saver, Releaser releaser) { this(DEFAULT, loader, saver, releaser); }
    WallpaperPicker(Catalogue catalogue, Loader loader, Saver saver, Releaser releaser) {
        this.catalogue = catalogue; this.loader = loader; this.saver = saver; this.releaser = releaser;
    }

    void open(String selected) {
        closed = false;
        categories = catalogue.categories();
        category = index(categories, Wallpaper.categoryOf(selected));
        if (category < 0) category = 0;
        options = catalogue.options(categories[category]);
        option = index(options, selected);
        if (option < 0) option = 0;
        optionsPane = false;
        refresh();
    }

    void move(int direction) {
        if (optionsPane) option = clamp(option + direction, options.length);
        else {
            category = clamp(category + direction, categories.length);
            options = catalogue.options(categories[category]);
            option = 0;
        }
        refresh();
    }

    Action ok() {
        if (!optionsPane) { optionsPane = true; refresh(); return Action.OPEN_OPTIONS; }
        saver.save(selected());
        return Action.PERSIST;
    }
    Action back() { return Action.CLOSE; }
    /** Releases this picker's preview and makes every queued callback stale. Safe to call more than once. */
    void close() {
        if (closed) return;
        closed = true;
        loadGeneration++;
        releaseImage();
        preview = Preview.HORIZON;
        status = "Frosted Horizon";
        rebuild();
    }
    void optionsPane(boolean value) { if (optionsPane != value) { optionsPane = value; refresh(); } }
    String selected() { return options.length == 0 ? "horizon:auto" : options[option]; }
    DrawData draw() { return draw; }

    private void refresh() {
        String selected = selected();
        int color = catalogue.color(selected);
        releaseImage();
        Wallpaper.Aerial aerial = "live:aerial".equals(selected) ? catalogue.live() : catalogue.aerial(selected);
        final int generation = ++loadGeneration;
        if (aerial == null) {
            preview = "live:aerial".equals(selected) ? Preview.LIVE_FALLBACK : Preview.HORIZON;
            status = "live:aerial".equals(selected) ? "Live Aerial unavailable — Frosted Horizon" : "Frosted Horizon";
        } else {
            preview = "live:aerial".equals(selected) ? Preview.LIVE_LOADING : Preview.LOADING;
            status = "live:aerial".equals(selected) ? "Loading live aerial preview…" : "Loading aerial preview…";
            final boolean live = "live:aerial".equals(selected);
            loader.load(aerial, new Callback() {
                public boolean current() { return !closed && generation == loadGeneration; }
                public void ready(Object bitmap) {
                    if (!current()) { if (bitmap != null) releaser.release(bitmap); return; }
                    image = bitmap; preview = live ? Preview.LIVE_READY : Preview.READY;
                    status = live ? "Live Aerial preview ready" : "Aerial preview ready"; rebuild();
                }
                public void error() {
                    if (!current()) return;
                    preview = live ? Preview.LIVE_FALLBACK : Preview.ERROR;
                    status = live ? "Live Aerial unavailable — Frosted Horizon" : "Aerial preview unavailable — Frosted Horizon"; rebuild();
                }
            });
        }
        draw = new DrawData(categories, options, category, option, optionsPane, selected, color, preview, status, image);
    }
    private void releaseImage() { if (image != null) { releaser.release(image); image = null; } }
    private void rebuild() { draw = new DrawData(categories, options, category, option, optionsPane, selected(), catalogue.color(selected()), preview, status, image); }
    private static int index(String[] values, String value) { for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i; return -1; }
    private static int clamp(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }
}
