package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the compact prices;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV); with Watch
 * from the screensaver on (Prefs.saverWatch), OK first opens the title in its app, and the caption says so.
 * With your own photos on (Photos), those instead, without a caption.
 * Without photos (no TMDB key, or before the first fetch): the big clock over the Frosted Horizon (dark ink by day,
 * white at night, as the lock screen).
 * Settings → Screensaver style "nature" (Prefs.saverStyle): instead of photos, a depth-layered scene: the photo, a giant
 * clock in Antonio filled with the scene's frosted glass, and the scene's foreground cut out over the clock (the water
 * scene redraws a wetter clock below its waterline); the date and weather above it in Inter. A new scene every minute,
 * cross-fading; each frame is drawn once a minute in the background (adapted from tvOS-Launcher, Apache-2.0).
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // The first photo dissolves in over 1.2 s while the whole screen settles from 1.06× (ANIMATIONS-SPEC.md §2.5).
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_200;
    private static final float ENTRY_ZOOM = 0.06f;
    /** Animations off (Settings: animator duration scale 0): the first photo is there at once, without the zoom. */
    private boolean instant;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 200, QUOTES_GAP = 52;
    private static final double DRIFT_PERIOD_S = 600;
    // The high and low and the stale prices line, made again only when they change (drawn on every frame).
    private String range = "", staleLine = "", staleFor = "";
    private HomeData.Weather rangeWeather;
    private int rangeGeneration = -1, rangePrefs = -1, staleGeneration = -1;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final TextPaint fit = new TextPaint();
    private String playingCorner, playingBottom;
    private float playingCornerWidth, playingBottomWidth, cornerCap, bottomCap, cornerBase;
    // The bars before what's playing move on every frame (drawn over the recorded text, which stays still).
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather, prices and the clock's shadow
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    // The Horizon behind the clock without photos; the ink over it.
    private boolean night = true;
    private int ink = 0xF2FFFFFF;
    private Bitmap horizon;
    private final RectF screen = new RectF();
    private final Paint horizonPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    // Nature: the shown frame, the one fading in over it, which scene, and the scene's pictures (the worker's only).
    private boolean nature, rendering, natureStale = true;   // stale: the minute, the date or the weather changed
    private Bitmap natureShown, natureIncoming;
    private long natureIncomingAt, natureSceneAt;
    private int natureScene = -1;
    private String natureFor = "";
    private Nature.Loaded natureLoaded;
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint, stalePaint;
    private Paint cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, metaPaint, hintPaint;
    private TextPaint titlePaint;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            playing.start();   // the listener allowed after the screensaver started (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        playing = new NowPlaying.Watch(c, () -> {
            fitPlaying();
            onData();
        });
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        instant = android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        failed.clear();
        nature = "nature".equals(Prefs.saverStyle());
        night = Wallpaper.dark(Prefs.wallpaper(), data.isNight(System.currentTimeMillis()));
        applyInk();
        natureFor = "";
        natureStale = true;
        fallback = !nature && photos().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        Photos.importInbox(getContext(), () -> { if (running) prepareNext(); });
        prepareNext();
        handler.postDelayed(tick, 60_000);
        playing.start();
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        for (Bitmap b : new Bitmap[] {natureShown, natureIncoming}) if (b != null) gone.add(b);
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        natureShown = natureIncoming = null;
        rendering = false;
        natureScene = -1;
        if (!worker.isShutdown()) worker.execute(() -> {
            if (natureLoaded != null) natureLoaded.recycle();
            natureLoaded = null;
        });
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        natureStale = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
        stalePaint.setTextAlign(Paint.Align.CENTER);
        // With photos: the corner (date, clock, prices) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF);
        cornerStale = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF);
        hintPaint = Ui.text(Ui.regular, 24 * k, 0x99FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        Symbols.warmWeather(getContext(), 30 * k);   // the corner's
        Symbols.warmWeather(getContext(), 32 * k);   // under the clock
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint, barPaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        applyInk();
        natureFor = "";
        natureStale = true;
        fitPlaying();
        glassFor = "";
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
    }

    // MARK: - Photos

    /** Your photos when they are on, otherwise the picks'. */
    private List<Recs.Pick> photos() {
        return Photos.on(getContext()) ? Photos.picks(getContext()) : recs.showcase();
    }

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || nature || decoding || prepared != null) return;
        List<Recs.Pick> list = photos();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock);
            return;
        }
        File file = pick.photo != null ? (pick.photo.exists() ? pick.photo : null) : recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu(withScrims(images[0], chosen.photo == null, light[0], light[1]));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + (chosen.photo != null ? chosen.photo.getName() : chosen.title) + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
            natureStale = true;
            chromeDirty = true;
        }
        if (nature) {
            drawNature(canvas, up);
            return;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : instant ? 1 : fadeIn(up - firstAt, FIRST_MS);
        // The entry: everything settles from 1.06× as the first photo dissolves in.
        float zoom = 1 + ENTRY_ZOOM * (1 - first);
        int saved = -1;
        if (zoom > 1.0005f) {
            saved = canvas.save();
            canvas.scale(zoom, zoom, w / 2f, h / 2f);
        }
        drawScene(canvas, up, first);
        if (saved >= 0) canvas.restoreToCount(saved);
    }

    private void drawScene(Canvas canvas, long up, float first) {
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        chrome.draw(canvas);
        if (playingCorner != null) {
            float x = RIGHT * k - playingCornerWidth - cornerCap * 0.6f - NowPlaying.barsWidth(cornerCap);
            drawBars(canvas, x, cornerBase, cornerCap, cornerQuote.getColor(), first);
        }
        drawGlass(canvas, first, e);
        drawCaptions(canvas, up);
    }

    /** What an app is playing, cut to fit the corner and the bottom line: once per title, not while recording. */
    private void fitPlaying() {
        String now = playing.text;
        if (now == null || quotePaint == null) {
            playingCorner = playingBottom = null;
            return;
        }
        fit.set(cornerQuote);
        playingCorner = TextUtils.ellipsize(now, fit, 860 * k, TextUtils.TruncateAt.END).toString();
        playingCornerWidth = cornerQuote.measureText(playingCorner);
        fit.set(quotePaint);
        playingBottom = TextUtils.ellipsize(now, fit, w - 2 * MARGIN * k - 60 * k, TextUtils.TruncateAt.END).toString();
        playingBottomWidth = quotePaint.measureText(playingBottom);
        cornerCap = Ui.cap(cornerQuote);
        bottomCap = Ui.cap(quotePaint);
        cornerBase = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
    }

    private void drawBars(Canvas c, float x, float base, float cap, int color, float alpha) {
        barPaint.setColor(Ui.alpha(color, alpha));
        NowPlaying.drawBars(c, x, base, cap, barPaint, true, barRect);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow zoom,
     * they move a little along with the photo, which makes no difference to the text.
     */
    private static Bitmap withScrims(Bitmap photo, boolean withCaption, float lightCaption, float lightClock) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = withCaption ? scrimStrength(lightCaption, 0.5f) : 0, corner = scrimStrength(lightClock, 0.42f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(0.3f), black(0.075f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient clockSide = new RadialGradient(cx, cy, w * 0.36f,
                new int[] {black(corner), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.72f, cx, cy);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(w * 0.5f, 0, w, cy + w * 0.36f * 0.72f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock, prices

    private void recordChrome() {
        Canvas c = chrome.beginRecording(w, h);
        try {
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 10 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 30 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xEBFFFFFF);
                x -= sw + 26 * k;
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate);

            Glass g = glass;
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else {
                Rect ink = new Rect();
                cornerClock.getTextBounds(clock, 0, clock.length(), ink);
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock);
            }

            // Compact prices under the clock, right-aligned.
            float base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
            float cap = Ui.cap(cornerQuote), gap = 7 * k, spacing = 26 * k;
            int[] coins = HomeData.active();
            int n = playingCorner != null ? 0 : HomeData.coinCount();   // what an app is playing takes their place
            if (playingCorner != null) c.drawText(playingCorner, right - playingCornerWidth, base, cornerQuote);
            String[] values = new String[n];
            float[] glyphs = new float[n];
            float total = spacing * (n - 1);
            for (int i = 0; i < n; i++) {
                values[i] = HomeData.compact(data.prices[coins[i]]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                total += glyphs[i] + gap + cornerQuote.measureText(values[i]);
            }
            float qx = right - total;
            for (int i = 0; i < n; i++) {
                Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], qx + glyphs[i] / 2, base - cap / 2, cap, 0xA6FFFFFF);
                qx += glyphs[i] + gap;
                c.drawText(values[i], qx, base, cornerQuote);
                qx += cornerQuote.measureText(values[i]) + spacing;
            }
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            if (!stale.isEmpty() && n > 0) c.drawText(stale, right - total - 24 * k - cornerStale.measureText(stale), base, cornerStale);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (g == null || glassShader == null || alpha <= 0.004f) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
        else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
        glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        Canvas c = s.caption.beginRecording(w, h);
        try {
            if (s.pick.photo != null) return;   // your photos have no caption
            // With the hint, the caption moves up a line: the hint takes the meta's place, 72 px from the bottom edge.
            boolean hint = Prefs.saverWatch() && s.pick.pkg != null;
            float x = MARGIN * k, metaBase = (META_BASE - (hint ? 44 : 0)) * k, titleBase = metaBase - 58 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            float capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint);
            c.drawText(Recs.periodLabel(), x, capTop - 30 * k, labelPaint);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            if (s.pick.meta != null && !s.pick.meta.isEmpty()) c.drawText(s.pick.meta, x, metaBase, metaPaint);
            if (hint) {
                c.drawText(Ui.trf("Press OK to watch on {app} · any other button to close", "app", Recs.serviceName(s.pick)),
                        x, META_BASE * k, hintPaint);
            }
        } finally {
            s.caption.endRecording();
        }
        s.caption.setPosition(0, 0, w, h);
    }

    /** The title whose caption shows, if it has an app on the TV; null for your photos and the clock. */
    private Recs.Pick watchable() {
        Slide s = incoming != null && SystemClock.uptimeMillis() - incoming.startedAt > 1300 ? incoming : current;
        return s == null || s.pick.photo != null || s.pick.pkg == null ? null : s.pick;
    }

    /** OK with Watch from the screensaver on: the title opens in its app, as its card does on the home screen. */
    boolean watch() {
        Recs.Pick p = watchable();
        if (p == null) return false;
        boolean opened = HomeApps.open(getContext(), HomeApps.app(p), null);
        if (!opened) AppLog.w("screensaver: couldn't open “" + p.title + "” in " + p.pkg);
        return opened;
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a);
            current.caption.setTranslationY(14 * k * (1 - a));
            current.caption.draw(c);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out);
            current.caption.setTranslationY(0);
            current.caption.draw(c);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in);
            incoming.caption.setTranslationY(14 * k * (1 - in));
            incoming.caption.draw(c);
        }
    }

    // MARK: - Without photos: the big clock over the Frosted Horizon

    /** The Horizon for the time of day and the ink over it: dark by day, white at night. */
    private void applyInk() {
        if (w == 0 || datePaint == null) return;
        horizon = Horizon.get(w, h, night);
        int base = night ? 0xFFFFFFFF : 0xFF1C1C1E;
        ink = base;
        datePaint.setColor(Ui.alpha(base, 0.85f));
        clockPaint.setColor(Ui.alpha(base, 0.95f));
        weatherPaint.setColor(Ui.alpha(base, 0.78f));
        weatherDim.setColor(Ui.alpha(base, 0.5f));
        quotePaint.setColor(Ui.alpha(base, 0.6f));
        stalePaint.setColor(Ui.alpha(base, 0.35f));
        dirty = true;
    }

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        Horizon.draw(canvas, horizon, screen, horizonPaint, 1);
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        float dx = (float) (40 * k * Math.sin(phase)), dy = (float) (26 * k * Math.sin(2 * phase + 0.7));
        content.setTranslationX(dx);
        content.setTranslationY(dy);
        content.setAlpha(alpha);
        content.draw(canvas);
        if (playingBottom != null) {
            float bars = NowPlaying.barsWidth(bottomCap) + bottomCap * 0.6f;
            drawBars(canvas, w / 2f - (bars + playingBottomWidth) / 2 + dx, h - 92 * k + dy, bottomCap, quotePaint.getColor(), alpha);
        }
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        // Weather: symbol, temperature, high and low, centered under the clock.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            if (wx != rangeWeather || Ui.generation != rangeGeneration || Prefs.version != rangePrefs) {
                range = HomeData.highLow(wx, "  ");
                rangeWeather = wx;
                rangeGeneration = Ui.generation;
                rangePrefs = Prefs.version;
            }
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, Ui.alpha(ink, 0.78f));
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        // Prices near the bottom edge: small white logo + value, all four on one line.
        float cap = Ui.cap(quotePaint), spacing = 56 * k, gap = 10 * k;
        int[] coins = HomeData.active();
        String[] values = new String[playingBottom != null ? 0 : HomeData.coinCount()];
        float total = spacing * (values.length - 1);
        if (playingBottom != null) {
            float bars = NowPlaying.barsWidth(bottomCap) + bottomCap * 0.6f;
            c.drawText(playingBottom, cx - (bars + playingBottomWidth) / 2 + bars, h - 92 * k, quotePaint);
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = HomeData.price(data.prices[coins[i]]);
            total += Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap) + gap + quotePaint.measureText(values[i]);
        }
        float x = cx - total / 2, base = h - 92 * k;
        for (int i = 0; i < values.length; i++) {
            float gw = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + gw / 2, base - cap / 2, cap, Ui.alpha(ink, 0.5f));
            x += gw + gap;
            c.drawText(values[i], x, base, quotePaint);
            x += quotePaint.measureText(values[i]) + spacing;
        }
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty() && values.length > 0) {
            if (!stale.equals(staleFor) || Ui.generation != staleGeneration) {
                staleLine = Ui.trf("prices from {ago}", "ago", stale);
                staleFor = stale;
                staleGeneration = Ui.generation;
            }
            c.drawText(staleLine, cx, base + 38 * k, stalePaint);
        }
    }

    // MARK: - Nature: the depth-layered scene clock

    /** A new scene every minute; the frame again when the minute, the date or the weather changes. */
    private static final long NATURE_SCENE_MS = 60_000, NATURE_FADE_MS = 2_000;

    private void drawNature(Canvas canvas, long up) {
        if (natureShown == null) Horizon.draw(canvas, horizon, screen, horizonPaint, 1);   // until the first frame
        else canvas.drawBitmap(natureShown, null, screen, horizonPaint);
        if (natureIncoming != null) {
            float e = instant ? 1 : fadeIn(up - natureIncomingAt, natureShown == null ? FIRST_MS : NATURE_FADE_MS);
            horizonPaint.setAlpha(Math.round(255 * e));
            canvas.drawBitmap(natureIncoming, null, screen, horizonPaint);
            horizonPaint.setAlpha(255);
            if (e >= 1) {
                final Bitmap old = natureShown;
                natureShown = natureIncoming;
                natureIncoming = null;
                if (old != null) handler.postDelayed(old::recycle, 1000);   // after the last frame that drew it
            }
        }
        if (!running || rendering || natureIncoming != null) return;
        boolean next = natureScene < 0 || up - natureSceneAt >= NATURE_SCENE_MS;
        if (!next && !natureStale) return;   // nothing new: no strings made on this frame
        natureStale = false;
        String header = natureHeader();
        String key = clock + "|" + header;
        if (next || !key.equals(natureFor)) renderNature(next, key, header, up);
    }

    /** "Friday, October 30  ·  61° Partly Cloudy" (or only the date without the weather). */
    private String natureHeader() {
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp) || !Prefs.weather()) return date;
        return Ui.trf("{date}  ·  {temp} {condition}", "date", date, "temp", HomeData.degrees(wx.temp),
                "condition", HomeData.condition(wx.code, wx.isDay));
    }

    /** Draws the next frame in the background (loading the next scene's pictures first when it's time for one). */
    private void renderNature(boolean next, String key, String header, long up) {
        rendering = true;
        int scene = natureScene;
        if (next) {
            scene = natureScene < 0 ? Nature.first(System.currentTimeMillis()) : (natureScene + 1) % Nature.SCENES.length;
            natureScene = scene;
            natureSceneAt = up;
        }
        natureFor = key;
        final int gen = generation, s = scene, fw = w, fh = h;
        final String time = clock;
        final Context c = getContext().getApplicationContext();
        worker.execute(() -> {
            Bitmap frame = null;
            try {
                if (natureLoaded == null || natureLoaded.index != s) {
                    if (natureLoaded != null) natureLoaded.recycle();
                    natureLoaded = Nature.load(c, s);
                }
                frame = Nature.render(natureLoaded, fw, fh, time, header);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: nature scene: " + e.getMessage());
            }
            final Bitmap ready = frame;
            handler.post(() -> {
                rendering = false;
                if (gen != generation || !running || ready == null) {
                    if (ready != null) ready.recycle();
                    return;
                }
                natureIncoming = ready;
                natureIncomingAt = clock();
                invalidate();
            });
        });
    }

    /**
     * The five scenes and their drawing (adapted from tvOS-Launcher's Scenes, Apache-2.0): positions in a 960 × 540
     * frame, scaled to the screen. No dark shade over the photos: only the faintest top fade under the date.
     */
    static final class Nature {
        private Nature() {}

        static final class Spec {
            final String id;
            final boolean water, left, darkClock, darkDate;
            final float clockTop, clockSize, clockLeft, dateTop, dateLeft;

            Spec(String id, boolean water, boolean left, boolean darkClock, boolean darkDate, float clockTop, float clockSize,
                    float clockLeft, float dateTop, float dateLeft) {
                this.id = id;
                this.water = water;
                this.left = left;
                this.darkClock = darkClock;
                this.darkDate = darkDate;
                this.clockTop = clockTop;
                this.clockSize = clockSize;
                this.clockLeft = clockLeft;
                this.dateTop = dateTop;
                this.dateLeft = dateLeft;
            }
        }

        static final Spec[] SCENES = {
            new Spec("night", false, false, false, false, 62, 178, 0, 42, 0),
            new Spec("dawn", false, false, false, false, 115, 200, 0, 88, 0),
            new Spec("fog", false, true, true, true, 86, 235, 48, 58, 52),
            new Spec("water", true, false, false, false, 134, 210, 0, 108, 0),
            new Spec("golden", false, false, false, false, 76, 195, 0, 53, 0),
        };

        /** The scene that suits the hour to start with: night, dawn, the day ones, golden hour. */
        static int first(long now) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(now);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            if (hour < 6 || hour >= 20) return 0;
            if (hour < 10) return 1;
            if (hour < 16) return hour % 2 == 0 ? 2 : 3;
            return 4;
        }

        /** One scene's pictures: the photo, its foreground cut-out (none on the water) and the baked glass textures. */
        static final class Loaded {
            final int index;
            Bitmap bg, fg, glass, wet;

            Loaded(int index) {
                this.index = index;
            }

            void recycle() {
                for (Bitmap b : new Bitmap[] {bg, fg, glass, wet}) if (b != null) b.recycle();
                bg = fg = glass = wet = null;
            }
        }

        static Loaded load(Context c, int index) throws java.io.IOException {
            Spec sp = SCENES[index];
            Loaded ls = new Loaded(index);
            ls.bg = asset(c, "scenes/scene-" + sp.id + ".jpg");
            if (!sp.water) ls.fg = asset(c, "scenes/scene-" + sp.id + "-fg.webp");
            Bitmap raw = asset(c, "scenes/scene-" + sp.id + "-glass.jpg");
            ls.glass = bake(raw, sp.darkClock, false);
            if (sp.water) ls.wet = bake(raw, false, true);
            raw.recycle();
            return ls;
        }

        private static Bitmap asset(Context c, String path) throws java.io.IOException {
            try (java.io.InputStream in = c.getAssets().open(path)) {
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                if (b == null) throw new java.io.IOException("can't decode " + path);
                return b;
            }
        }

        /** The clock's glass: the scene softly blurred (drawn tiny, then large), saturated, with a 160° sheen. */
        private static Bitmap bake(Bitmap src, boolean dark, boolean wet) {
            int w = 480, h = 270, sw = wet ? 60 : 120, sh = wet ? 34 : 68;
            Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(dark ? 0.9f : 1.25f);
            float b = dark ? 0.46f : 1.08f, o = dark ? 0 : 9;
            cm.postConcat(new android.graphics.ColorMatrix(new float[] {b, 0, 0, 0, o, 0, b, 0, 0, o, 0, 0, b, 0, o, 0, 0, 0, 1, 0}));
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            cv.drawBitmap(small, null, new RectF(0, 0, w, h), p);
            small.recycle();
            Paint sheen = new Paint();
            sheen.setShader(new LinearGradient(0, 0, w * 0.45f, h, new int[] {0x44FFFFFF, 0x1CFFFFFF, 0x0AFFFFFF, 0x1AFFFFFF},
                    new float[] {0, 0.35f, 0.65f, 1}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, sheen);
            return out;
        }

        /** One frame: photo, glass clock, foreground over it, the date line. Off the UI thread. */
        static Bitmap render(Loaded ls, int w, int h, String time, String header) {
            Spec sp = SCENES[ls.index];
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
            cover(c, ls.bg, w, h, image);
            float u = Math.min(w / 960f, h / 540f);
            if (!sp.darkDate) {   // the faintest fade under the date, nothing more
                Paint fade = new Paint();
                fade.setShader(new LinearGradient(0, 0, 0, h * 0.22f, 0x24000000, 0, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h * 0.22f, fade);
            }

            // The giant clock: Antonio, filled with the frosted scene, a frost over it and a bright rim.
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setTypeface(Ui.clockTall());
            fill.setTextSize(sp.clockSize * u);
            fill.setLetterSpacing(-0.02f);
            android.graphics.Rect bounds = new android.graphics.Rect();
            fill.getTextBounds("0847", 0, 4, bounds);
            float cap = Math.max(sp.clockSize * u * 0.68f, -bounds.top);
            float dateBase = Math.max(65 * u, (sp.dateTop + 19) * u);
            float top = Math.max((sp.clockTop + 19) * u, dateBase + 14 * u), base = top + cap;
            float tw = fill.measureText(time);
            float x = sp.left ? sp.clockLeft * u : (w - tw) / 2;
            Paint shadow = new Paint(fill);
            shadow.setColor(0x2A000000);
            Paint frost = new Paint(fill);
            frost.setShader(new LinearGradient(0, base - cap, 0, base, sp.darkClock
                    ? new int[] {0x26FFFFFF, 0x44FFFFFF, 0x76FFFFFF} : new int[] {0x34DAE0FF, 0x56ECE8FF, 0x8AFFF2FC},
                    new float[] {0, 0.52f, 1}, Shader.TileMode.CLAMP));
            Paint rim = new Paint(fill);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1.5f, 1.2f * u));
            rim.setShader(new LinearGradient(0, base - cap, 0, base, new int[] {0xE4F4F8FF, 0xB9E8ECFF, 0xFAFFFFFF},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
            BitmapShader glass = new BitmapShader(ls.glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale((float) w / ls.glass.getWidth(), (float) h / ls.glass.getHeight());
            glass.setLocalMatrix(m);
            fill.setShader(glass);
            if (sp.water) {
                float waterline = h * 0.55f;
                c.save();
                c.clipRect(0, 0, w, waterline);
                clock(c, time, x, base, u, shadow, fill, frost, rim, false);
                c.restore();
                if (ls.wet != null) {
                    BitmapShader wet = new BitmapShader(ls.wet, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Matrix mw = new Matrix();
                    mw.setScale((float) w / ls.wet.getWidth(), (float) h / ls.wet.getHeight());
                    wet.setLocalMatrix(mw);
                    fill.setShader(wet);
                }
                c.save();
                c.clipRect(0, waterline, w, h);
                clock(c, time, x, base, u, shadow, fill, frost, rim, true);
                c.restore();
            } else {
                clock(c, time, x, base, u, shadow, fill, frost, rim, false);
            }
            if (ls.fg != null) cover(c, ls.fg, w, h, image);   // the ridge, the canopy: in front of the clock

            // The date and weather above the clock, in Inter.
            Paint date = Ui.text(Ui.medium, 13.5f * u, sp.darkDate ? 0xE1161F1D : 0xF6F8F8FC);
            date.setLetterSpacing(0.01f);
            if (!sp.darkDate) date.setShadowLayer(8 * u, 0, 1.5f * u, 0x91000000);
            float dw = date.measureText(header);
            c.drawText(header, sp.left ? sp.dateLeft * u : (w - dw) / 2, dateBase, date);
            return out;
        }

        private static void clock(Canvas c, String time, float x, float base, float u, Paint shadow, Paint fill, Paint frost,
                Paint rim, boolean wet) {
            if (!wet) c.drawText(time, x, base + 4 * u, shadow);
            fill.setAlpha(wet ? 158 : 255);
            c.drawText(time, x, base, fill);
            frost.setAlpha(wet ? 110 : 200);
            c.drawText(time, x, base, frost);
            rim.setAlpha(wet ? 60 : 150);
            c.drawText(time, x, base, rim);
        }

        /** Centre-crops `b` over the whole frame. */
        private static void cover(Canvas c, Bitmap b, int w, int h, Paint p) {
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            float bw = b.getWidth() * s, bh = b.getHeight() * s;
            c.drawBitmap(b, null, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), p);
        }
    }
}
