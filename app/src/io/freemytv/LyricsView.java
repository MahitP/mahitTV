package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lyrics (DESIGN-LYRICS.md): what's playing, its words lit one at a time as they're sung, in the album art's colours,
 * after the Mac app Verci. The Lyrics app (LyricsActivity) and the screensaver while music plays (Screensaver) show it.
 * - Words (Verci's "Visual"): the line being sung as a stack of big heavy words, each row its own size and a little
 *   turned, drifting slowly in space; the word being sung white on a pill of the song's colour, the sung ones in its
 *   light tint, the coming ones dim; an icon beside words a picture says (WordIcons). Lines rise in and lift away.
 * - Lens (Verci's "Fisheye"): every line in a column under a lens: the one being sung large in the middle, word by
 *   word, the others smaller and dimmer the further they are; it glides on line by line.
 * Before the first line and in long gaps, three dots fill up as the next line nears (as Apple Music does). With no
 * lyrics, the song itself: its art, title and artist. With nothing playing, how to start.
 * The song's clock is the player's position moved on by the time since (eased to it: players report coarsely).
 * Measured on show and on each new line; frames move springs only (no allocation in onDraw or doFrame).
 */
final class LyricsView extends View implements Choreographer.FrameCallback, MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener {
        /** No music for a while (the screensaver goes back to its own then). */
        void onMusicGone();
    }

    static final int WORDS = 0, LENS = 1, STYLES = 2;
    private static final int NO_MUSIC = 0, LOADING = 1, NONE = 2, READY = 3;
    private static final long GAP_DOTS_MS = 3500, GONE_MS = 25_000;

    private final boolean saver;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private boolean running, posted, watching;
    private long lastNanos;
    private float w, h, k = 1, clock;
    private int style, state = NO_MUSIC;

    // MARK: - The player and the song

    private final List<MediaController> controllers = new ArrayList<>();
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { update(); }
        @Override public void onMetadataChanged(MediaMetadata m) { update(); }
        @Override public void onSessionDestroyed() { update(); }
    };
    private MediaController player;
    private String songKey = "", title = "", artist = "";
    private long duration, posMs, posAt;
    private float speed = 1;
    private boolean playing;
    private long goneSince;
    private Bitmap art;
    private LyricsSource.Lyrics lyrics;
    private float songMs;   // the clock shown, eased to the player's
    private int lineIndex = -1;

    // MARK: - Colours (from the art)

    private int highlight = 0xFFFF4F8B, lyricInk = 0xFFFFB3C9, secondaryInk = 0x99A08AA0, glowA = 0xFFFF4F8B, glowB = 0xFF6A3CFF;
    private final Spring colorIn = new Spring(1.2f, 1f, 1);
    private int[] fromInk = new int[5], toInk = new int[5];
    private Bitmap glowBg;
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix bgMatrix = new Matrix();

    // MARK: - Drawing

    private final TextPaint word = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Matrix artMatrix = new Matrix();
    private BitmapShader artShader;
    private String titleFit = "", artistFit = "", status = "", emptyTitle = "", emptySub = "", styleName = "";
    private String songTitleFit = "", timeText = "";
    private long timeAt;
    private final Spring styleLabel = new Spring(Spring.SNAPPY_FOCUS, 0), stateIn = new Spring(Spring.SPATIAL_SCROLL, 1);
    private long styleLabelUntil;

    // Words: the stack being sung and the one lifting away.
    private Stack current, outgoing;
    private final Spring enter = new Spring(0.55f, 0.86f, 1), leave = new Spring(0.45f, 1f, 1);
    // Lens: every line laid out once, and the scroll (in lines).
    private LensLine[] lens = new LensLine[0];
    private final Spring scroll = new Spring(0.62f, 0.9f, 0);
    private float[] lit = new float[0];   // per word of the line: the pill (Words) or the light (Lens), 0–1

    LyricsView(Context c, boolean saver, Listener listener) {
        super(c);
        this.saver = saver;
        this.listener = listener;
        Ui.init(c);
        style = Math.max(0, Math.min(STYLES - 1, Prefs.lyricsStyle()));
        word.setTypeface(Ui.displayHeavy);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void start() {
        if (running) return;
        running = true;
        watch();
        wake();
    }

    void stop() {
        running = false;
        unwatch();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void watch() {
        if (watching) return;
        MediaSessionManager m = getContext().getSystemService(MediaSessionManager.class);
        try {
            m.addOnActiveSessionsChangedListener(this, NowPlaying.component(getContext()), main);
            watching = true;
            onActiveSessionsChanged(m.getActiveSessions(NowPlaying.component(getContext())));
        } catch (SecurityException e) {
            AppLog.w("lyrics: no access to what's playing");
            update();
        }
    }

    private void unwatch() {
        if (!watching) return;
        watching = false;
        try {
            getContext().getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
        } catch (RuntimeException ignored) {
        }
        for (MediaController mc : controllers) mc.unregisterCallback(callback);
        controllers.clear();
    }

    @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
        for (MediaController mc : controllers) mc.unregisterCallback(callback);
        controllers.clear();
        if (sessions != null && watching) {
            for (MediaController mc : sessions) {
                mc.registerCallback(callback, main);
                controllers.add(mc);
            }
        }
        update();
    }

    /** A music session that plays (else the last one that did, while it's paused). */
    private MediaController pick() {
        MediaController paused = null;
        for (MediaController mc : controllers) {
            if (!GlowRing.isMusic(mc)) continue;
            PlaybackState s = mc.getPlaybackState();
            int st = s == null ? PlaybackState.STATE_NONE : s.getState();
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) return mc;
            if (paused == null && st == PlaybackState.STATE_PAUSED) paused = mc;
        }
        return paused;
    }

    private void update() {
        MediaController mc = pick();
        player = mc;
        if (mc == null) {
            playing = false;
            if (state != NO_MUSIC) setState(NO_MUSIC);
            if (goneSince == 0) goneSince = SystemClock.uptimeMillis();
            wake();
            return;
        }
        PlaybackState s = mc.getPlaybackState();
        playing = s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        goneSince = playing ? 0 : goneSince == 0 ? SystemClock.uptimeMillis() : goneSince;
        if (s != null) {
            long p = s.getPosition();
            if (p >= 0) {
                posMs = p;
                posAt = s.getLastPositionUpdateTime() > 0 ? s.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
                speed = s.getPlaybackSpeed() == 0 ? 1 : s.getPlaybackSpeed();
            }
        }
        MediaMetadata m = mc.getMetadata();
        String t = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String a = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (a.isEmpty() && m != null) a = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        long d = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap b = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (b != art && b != null && !b.isRecycled()) setArt(b);
        String key = LyricsSource.key(t, a, d);
        if (!key.equals(songKey) && !t.isEmpty()) {
            songKey = key;
            title = t;
            artist = a;
            duration = d;
            lyrics = null;
            lineIndex = -1;
            songMs = posNow();
            current = outgoing = null;
            lens = new LensLine[0];
            measureChrome();
            setState(LOADING);
            final String want = key;
            LyricsSource.fetch(getContext(), t, a, d, (k2, found) -> main.post(() -> {
                if (!want.equals(songKey)) return;   // the song changed meanwhile
                lyrics = found;
                lineIndex = -1;
                if (found != null) layoutLens();
                setState(found == null ? NONE : READY);
            }));
            announceForAccessibility(title + ", " + artist);
        } else if (state == NO_MUSIC && !t.isEmpty()) {
            setState(lyrics != null ? READY : LOADING);
        }
        wake();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private long posNow() {
        if (!playing) return posMs;
        return posMs + (long) ((SystemClock.elapsedRealtime() - posAt) * speed);
    }

    private void setState(int s) {
        if (s == state) return;
        state = s;
        stateIn.snap(0);
        stateIn.target = 1;
        status = s == LOADING ? Ui.tr("Finding lyrics…") : s == NONE ? Ui.tr("No lyrics for this song") : "";
    }

    // MARK: - Art and colours

    private void setArt(Bitmap b) {
        art = b;
        artShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        final Bitmap src = b;
        WORK.execute(() -> {
            float[][] p;
            try {
                p = GlowRing.Palette.of(src);
            } catch (RuntimeException e) {
                return;
            }
            final float[][] pal = p;
            main.post(() -> applyPalette(pal[0], pal[1]));
        });
    }

    /** The song's inks: the highlight its vivid colour, the sung words its light tint, the coming ones a dim mauve. */
    private void applyPalette(float[] accent, float[] deep) {
        int a = rgb(accent), d = rgb(deep);
        int[] next = {a, blend(a, 0xFFFFFFFF, 0.58f), Ui.alpha(blend(d, 0xFF9A93A6, 0.45f), 0.62f), a, d};
        fromInk = new int[] {highlight, lyricInk, secondaryInk, glowA, glowB};
        toInk = next;
        colorIn.snap(0);
        colorIn.target = 1;
        makeGlowBg(a, d);
        wake();
    }

    /** The background's two soft glows in the song's colours, painted once small and drawn stretched (cheap). */
    private void makeGlowBg(int a, int d) {
        int bw = 240, bh = 135;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF050507);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(bw * 0.18f, bh * 0.2f, bw * 0.62f, Ui.alpha(a, 0.30f), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        p.setShader(new RadialGradient(bw * 0.86f, bh * 0.92f, bw * 0.66f, Ui.alpha(d, 0.26f), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        glowBg = b;
        bgPaint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    private void stepColors() {
        float t = Ui.clamp(colorIn.value);
        highlight = blend(fromInk[0], toInk[0], t);
        lyricInk = blend(fromInk[1], toInk[1], t);
        secondaryInk = blend(fromInk[2], toInk[2], t);
    }

    private static int rgb(float[] c) {
        return 0xFF000000 | Math.round(Ui.clamp(c[0]) * 255) << 16 | Math.round(Ui.clamp(c[1]) * 255) << 8 | Math.round(Ui.clamp(c[2]) * 255);
    }

    // MARK: - Keys (the Lyrics app; the screensaver closes on a key)

    /** OK plays or pauses, ← → the previous or next song, ↑ ↓ the style. */
    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return false;
        Click click = Click.current();
        MediaController mc = player;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (mc == null) return false;
                if (click != null) click.toggle(!playing);
                if (playing) mc.getTransportControls().pause(); else mc.getTransportControls().play();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mc == null) return false;
                if (click != null) click.open();
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) mc.getTransportControls().skipToPrevious();
                else mc.getTransportControls().skipToNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                style = (style + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : STYLES - 1)) % STYLES;
                Prefs.setLyricsStyle(style);
                if (click != null) click.move();
                styleName = style == WORDS ? Ui.tr("Words") : Ui.tr("Lens");
                styleLabel.target = 1;
                styleLabelUntil = SystemClock.uptimeMillis() + 1600;
                current = outgoing = null;
                lineIndex = -1;
                announceForAccessibility(styleName);
                wake();
                return true;
            default:
                return false;
        }
    }

    // MARK: - Frames

    private void wake() {
        if (posted || !running) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        posted = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        clock += dt;
        colorIn.step(dt);
        stepColors();
        enter.step(dt);
        leave.step(dt);
        scroll.step(dt);
        stateIn.step(dt);
        if (styleLabel.target == 1 && SystemClock.uptimeMillis() > styleLabelUntil) styleLabel.target = 0;
        styleLabel.step(dt);
        // The song's clock: on with the frames, eased to the player's (a jump of over 0.4 s is a seek: at once).
        float target = posNow();
        if (playing) songMs += dt * 1000 * speed;
        float off = target - songMs;
        if (Math.abs(off) > 400) songMs = target; else songMs += off * Math.min(1, dt * 3);
        if (state == READY) advance(dt);
        if (saver && SystemClock.uptimeMillis() - timeAt > 1000) tickTime();
        if (goneSince != 0 && listener != null && SystemClock.uptimeMillis() - goneSince > GONE_MS) {
            goneSince = 0;
            listener.onMusicGone();
        }
        invalidate();
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The line being sung now (the last one started), and the words' light. */
    private void advance(float dt) {
        LyricsSource.Line[] lines = lyrics.lines;
        int i = lineIndex;
        long t = (long) songMs;
        if (i < 0 || i >= lines.length || t < lines[i].start - 250) i = -1;
        int found = -1;
        for (int j = Math.max(0, i); j < lines.length; j++) {
            if (lines[j].start - 120 <= t) found = j; else break;   // a hair early: it reads as on time
        }
        if (found < 0 && i < 0) {
            for (int j = 0; j < lines.length; j++) if (lines[j].start - 120 <= t) found = j;
        }
        if (found != lineIndex) setLine(found);
        if (lineIndex >= 0) {
            LyricsSource.Word[] ws = lines[lineIndex].words;
            for (int n = 0; n < ws.length && n < lit.length; n++) {
                boolean on = t >= ws[n].start - 60 && t < ws[n].end;
                lit[n] += ((on ? 1 : 0) - lit[n]) * Math.min(1, dt / (on ? 0.05f : 0.12f));
            }
        }
    }

    private void setLine(int i) {
        lineIndex = i;
        int n = i >= 0 ? lyrics.lines[i].words.length : 0;
        if (lit.length < n) lit = new float[Math.max(n, 16)];
        for (int j = 0; j < lit.length; j++) lit[j] = 0;
        if (style == WORDS) {
            outgoing = current;
            leave.snap(0);
            leave.target = 1;
            current = i >= 0 ? layoutStack(lyrics.lines[i], i) : null;
            enter.snap(0);
            enter.target = 1;
        } else {
            scroll.target = Math.max(0, i);
            if (i < 0) scroll.snap(0);
        }
    }

    // MARK: - Layout (on show, a new song or a new line; never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        small.setTypeface(Ui.semibold);
        emptyTitle = Ui.tr("Play a song to see its lyrics");
        emptySub = "Spotify · YouTube Music · Apple Music";
        Symbols.warm(getContext(), "lyrics", 120 * k);
        measureChrome();
        if (lyrics != null) layoutLens();
        current = outgoing = null;
        lineIndex = -1;
    }

    /** The screensaver's clock, once a second (not a string a frame). */
    private void tickTime() {
        timeAt = SystemClock.uptimeMillis();
        timeText = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date());
    }

    private void measureChrome() {
        if (w == 0) return;
        word.setTextSize(78 * k);
        float tx = w * 0.18f + 460 * k + 72 * k;
        songTitleFit = TextUtils.ellipsize(title, word, w - tx - 120 * k, TextUtils.TruncateAt.END).toString();
        small.setTextSize(34 * k);
        titleFit = TextUtils.ellipsize(title, small, 900 * k, TextUtils.TruncateAt.END).toString();
        small.setTextSize(28 * k);
        artistFit = TextUtils.ellipsize(artist, small, 900 * k, TextUtils.TruncateAt.END).toString();
    }

    /** Words: the line as rows of one or two words, each row as wide as its turn says, the whole stack fitting. */
    private static final class Stack {
        int count;
        float[] x, y, size, rot;   // per row: centre x, baseline y, text size, turn (deg)
        int[] rowOf;               // per word: its row
        float[] wx, ww;            // per word: x within the screen (left) and width
        float[] iconX;             // per word: its icon's centre x, or -1
        String[] text, icon;
        float top, bottom;
    }

    private static final float[] ROW_WIDTHS = {0.50f, 0.64f, 0.44f, 0.58f, 0.48f};
    private static final float[] ROW_TURN = {-2.6f, 1.8f, -1.2f, 2.2f, -1.8f};

    private Stack layoutStack(LyricsSource.Line line, int seed) {
        LyricsSource.Word[] ws = line.words;
        int n = ws.length;
        // Rows: up to 9 letters a row (13 when the line is long), never splitting a word.
        int max = n > 7 ? 13 : 9;
        int[] rowOf = new int[n];
        int rows = 0, letters = 0;
        for (int i = 0; i < n; i++) {
            int len = ws[i].text.length();
            if (i > 0 && letters + 1 + len > max) {
                rows++;
                letters = 0;
            }
            letters += (letters == 0 ? 0 : 1) + len;
            rowOf[i] = rows;
        }
        rows++;
        Stack s = new Stack();
        s.count = n;
        s.rowOf = rowOf;
        s.text = new String[n];
        s.icon = new String[n];
        s.wx = new float[n];
        s.ww = new float[n];
        s.iconX = new float[n];
        s.x = new float[rows];
        s.y = new float[rows];
        s.size = new float[rows];
        s.rot = new float[rows];
        float space = 0.28f, iconGap = 0.18f, iconW = 0.78f;
        // Each row's width at size 1 (words, spaces, icons), then its size from its share of the screen.
        float[] unit = new float[rows];
        word.setTextSize(100);
        for (int i = 0; i < n; i++) {
            s.text[i] = ws[i].text;
            s.icon[i] = ws[i].icon != null && Symbols.get(getContext(), ws[i].icon) != null ? ws[i].icon : null;
            float wdt = word.measureText(ws[i].text) / 100f;
            unit[rowOf[i]] += wdt + (s.icon[i] != null ? iconGap + iconW : 0);
            if (i + 1 < n && rowOf[i + 1] == rowOf[i]) unit[rowOf[i]] += space;
        }
        float total = 0;
        for (int r0 = 0; r0 < rows; r0++) {
            float target = w * ROW_WIDTHS[(r0 + seed) % ROW_WIDTHS.length];
            s.size[r0] = Math.max(64 * k, Math.min(212 * k, target / Math.max(0.5f, unit[r0])));
            total += s.size[r0] * 1.08f;
        }
        float room = h * 0.70f;
        if (total > room) {
            float f = room / total;
            for (int r0 = 0; r0 < rows; r0++) s.size[r0] *= f;
            total = room;
        }
        float y = h / 2 - total / 2;
        for (int r0 = 0; r0 < rows; r0++) {
            y += s.size[r0] * 0.86f;
            s.y[r0] = y;
            y += s.size[r0] * 0.22f;
            s.x[r0] = w / 2 + w * 0.025f * (((r0 + seed) % 3) - 1);
            s.rot[r0] = ROW_TURN[(r0 + seed) % ROW_TURN.length];
        }
        s.top = h / 2 - total / 2;
        s.bottom = s.top + total;
        // Each word's place along its row, the row centred.
        for (int r0 = 0; r0 < rows; r0++) {
            float x = s.x[r0] - unit[r0] * s.size[r0] / 2;
            for (int i = 0; i < n; i++) {
                if (rowOf[i] != r0) continue;
                word.setTextSize(s.size[r0]);
                float wdt = word.measureText(s.text[i]);
                s.wx[i] = x;
                s.ww[i] = wdt;
                x += wdt;
                if (s.icon[i] != null) {
                    s.iconX[i] = x + (iconGap + iconW / 2) * s.size[r0];
                    x += (iconGap + iconW) * s.size[r0];
                } else {
                    s.iconX[i] = -1;
                }
                x += space * s.size[r0];
            }
        }
        return s;
    }

    /** Lens: each line wrapped at 78 % of the screen at the base size, its words' places kept. */
    private static final class LensLine {
        float[] wx, wy, ww;   // per word: left, baseline (from the line's top) and width at BASE
        float height, width;
    }

    private static final float LENS_BASE = 84;

    private void layoutLens() {
        if (w == 0 || lyrics == null) return;
        LyricsSource.Line[] lines = lyrics.lines;
        lens = new LensLine[lines.length];
        float base = LENS_BASE * k, maxW = w * 0.78f, lh = base * 1.18f, sp = word.measureText(" ");
        word.setTextSize(base);
        sp = word.measureText(" ");
        for (int i = 0; i < lines.length; i++) {
            LyricsSource.Word[] ws = lines[i].words;
            LensLine l = new LensLine();
            l.wx = new float[ws.length];
            l.wy = new float[ws.length];
            l.ww = new float[ws.length];
            // Rows first, then each row centred.
            float x = 0, y = base * 0.9f, rowStart = 0;
            int first = 0;
            for (int j = 0; j < ws.length; j++) {
                float ww = word.measureText(ws[j].text);
                if (x > 0 && x + ww > maxW) {
                    centre(l, first, j, x - sp);
                    l.width = Math.max(l.width, x - sp);
                    x = 0;
                    y += lh;
                    first = j;
                }
                l.wx[j] = x;
                l.wy[j] = y;
                l.ww[j] = ww;
                x += ww + sp;
            }
            centre(l, first, ws.length, x - sp);
            l.width = Math.max(l.width, x - sp);
            l.height = y + base * 0.3f;
            lens[i] = l;
        }
    }

    private static void centre(LensLine l, int from, int to, float rowW) {
        for (int j = from; j < to; j++) l.wx[j] -= rowW / 2;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        drawBackground(c);
        float si = Ui.clamp(stateIn.value);
        switch (state) {
            case NO_MUSIC: drawEmpty(c, si); break;
            case READY:
                if (style == WORDS) drawWords(c); else drawLens(c);
                drawChrome(c, 1);
                break;
            default: drawSong(c, si); break;
        }
        float sl = Ui.clamp(styleLabel.value);
        if (sl > 0.01f) drawStyleLabel(c, sl);
    }

    /** Near black, with the song's two glows drifting very slowly. */
    private void drawBackground(Canvas c) {
        if (glowBg == null) {
            c.drawColor(0xFF050507);
            return;
        }
        float sx = w * 1.12f / glowBg.getWidth(), sy = h * 1.12f / glowBg.getHeight();
        bgMatrix.setScale(sx, sy);
        bgMatrix.postTranslate(-w * 0.06f + w * 0.03f * (float) Math.sin(clock * 0.07),
                -h * 0.06f + h * 0.03f * (float) Math.cos(clock * 0.05));
        bgPaint.getShader().setLocalMatrix(bgMatrix);
        c.drawRect(0, 0, w, h, bgPaint);
    }

    private void drawWords(Canvas c) {
        float drift = (float) Math.sin(clock * 0.21) * 1.1f;
        float dx = 16 * k * (float) Math.sin(clock * 0.17), dy = 10 * k * (float) Math.sin(clock * 0.13);
        if (outgoing != null && leave.value < 0.999f) {
            float q = Ui.clamp(leave.value);
            drawStack(c, outgoing, false, 1 - q, -70 * k * q + dy, dx, drift, 1 - 0.05f * q);
        }
        if (current != null) {
            float p = Ui.clamp(enter.value);
            drawStack(c, current, true, p, 80 * k * (1 - enter.value) + dy, dx, drift, 0.95f + 0.05f * enter.value);
        } else if (lyrics != null) {
            drawGapDots(c, w / 2, h / 2, 18 * k);
        }
        if (current != null && gapBeforeNext() > GAP_DOTS_MS && lineEnded()) {
            // The line is over and the next is far off: it fades, and the dots take its place.
            drawGapDots(c, w / 2, current.bottom + 90 * k, 14 * k);
        }
    }

    private void drawStack(Canvas c, Stack s, boolean live, float alpha, float ty, float tx, float drift, float scale) {
        if (alpha <= 0.01f) return;
        long t = (long) songMs;
        LyricsSource.Word[] ws = live && lineIndex >= 0 ? lyrics.lines[lineIndex].words : null;
        float fade = live && lineEnded() && gapBeforeNext() > GAP_DOTS_MS ? 0.35f : 1;
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale, w / 2, h / 2);
        c.rotate(drift, w / 2, h / 2);
        for (int i = 0; i < s.count; i++) {
            int row = s.rowOf[i];
            float size = s.size[row], base = s.y[row];
            float sung = ws != null && i < ws.length && t >= ws[i].end ? 1 : 0;
            float on = live && i < lit.length ? lit[i] : 0;
            if (!live) sung = 1;   // lifting away: all sung
            c.save();
            c.rotate(s.rot[row], s.x[row], base);
            // The pill behind the word being sung: the song's colour, grown in on a quick spring.
            if (on > 0.01f) {
                float px = 0.11f * size, pyTop = 0.80f * size, pyBot = 0.20f * size, grow = 0.86f + 0.14f * on;
                float cx = s.wx[i] + s.ww[i] / 2, cy = base - (pyTop - pyBot) / 2;
                float hw = (s.ww[i] / 2 + px) * grow, hh = (pyTop + pyBot) / 2 * grow;
                r.set(cx - hw, cy - hh, cx + hw, cy + hh);
                fill.setColor(Ui.alpha(highlight, on * alpha * fade));
                c.drawRoundRect(r, 0.16f * size, 0.16f * size, fill);
            }
            int ink = PasscodePad.blend(secondaryInk, lyricInk, sung);
            ink = PasscodePad.blend(ink, 0xFFFFFFFF, on);
            word.setTextSize(size);
            word.setColor(Ui.alpha(ink, alpha * fade));
            c.drawText(s.text[i], s.wx[i], base, word);
            if (s.icon[i] != null && s.iconX[i] >= 0) {
                int iconInk = PasscodePad.blend(secondaryInk, highlight, Math.max(sung, on));
                Symbols.draw(getContext(), c, s.icon[i], s.iconX[i], base - size * 0.36f, size * 0.62f,
                        Ui.alpha(iconInk, alpha * fade));
            }
            c.restore();
        }
        c.restore();
    }

    private void drawLens(Canvas c) {
        LyricsSource.Line[] lines = lyrics.lines;
        float s0 = scroll.value, cy = h * 0.46f, base = LENS_BASE * k;
        long t = (long) songMs;
        int from = Math.max(0, (int) Math.floor(s0) - 4), to = Math.min(lines.length - 1, (int) Math.ceil(s0) + 5);
        for (int i = from; i <= to; i++) {
            LensLine l = lens.length > i ? lens[i] : null;
            if (l == null) continue;
            float d = i - s0, ad = Math.abs(d);
            // The lens: big and bright in the middle, smaller and dimmer away from it, packed closer at the edges.
            float scale = 1f / (1f + 0.38f * d * d) * (i == lineIndex ? 1.08f : 1f);
            scale = Math.max(0.42f, scale);
            float y = cy + Math.signum(d) * (float) (1 - Math.exp(-ad * 0.85)) * h * 0.52f;
            float alpha = Math.max(0, 1 - 0.27f * ad);
            if (alpha <= 0.02f) continue;
            c.save();
            c.translate(w / 2, y);
            c.scale(scale, scale);
            c.translate(0, -l.height / 2);
            LyricsSource.Word[] ws = lines[i].words;
            word.setTextSize(base);
            for (int j = 0; j < ws.length; j++) {
                int ink;
                if (i < lineIndex || i == lineIndex && t >= ws[j].end) {
                    ink = lyricInk;
                } else if (i == lineIndex) {
                    float on = j < lit.length ? lit[j] : 0;
                    ink = PasscodePad.blend(secondaryInk, highlight, on);
                } else {
                    ink = secondaryInk;
                }
                if (i != lineIndex) ink = Ui.alpha(ink, i < lineIndex ? 0.55f : 0.85f);
                word.setColor(Ui.alpha(ink, alpha));
                c.drawText(ws[j].text, l.wx[j], l.wy[j], word);
            }
            c.restore();
        }
        if (lineIndex < 0 || lineEnded() && gapBeforeNext() > GAP_DOTS_MS) {
            drawGapDots(c, w / 2, lineIndex < 0 ? cy : cy + base * 1.4f, 14 * k);
        }
    }

    /** The current line has finished being sung. */
    private boolean lineEnded() {
        return lineIndex >= 0 && songMs > lyrics.lines[lineIndex].end + 400;
    }

    /** Milliseconds from the end of the current line (or now, before the first) to the next line. */
    private long gapBeforeNext() {
        LyricsSource.Line[] lines = lyrics.lines;
        int next = lineIndex + 1;
        if (next >= lines.length) return 0;
        long from = lineIndex >= 0 ? lines[lineIndex].end : 0;
        return lines[next].start - from;
    }

    /** Three dots that fill up as the next line nears, breathing (as Apple Music's). */
    private void drawGapDots(Canvas c, float cx, float cy, float rad) {
        if (lyrics == null) return;
        LyricsSource.Line[] lines = lyrics.lines;
        int next = lineIndex + 1;
        if (next >= lines.length) return;
        long from = lineIndex >= 0 ? lines[lineIndex].end : 0, to = lines[next].start;
        float p = to > from ? Ui.clamp((songMs - from) / (float) (to - from)) : 1;
        float breathe = 1 + 0.08f * (float) Math.sin(clock * 2.4);
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * rad * 3.2f, fillUp = Ui.clamp(p * 3 - i);
            fill.setColor(PasscodePad.blend(Ui.alpha(secondaryInk, 0.5f), lyricInk, fillUp));
            c.drawCircle(x, cy, rad * breathe * (0.85f + 0.15f * fillUp), fill);
        }
    }

    /** The song (no lyrics, or finding them): its art, title and artist, large. */
    private void drawSong(Canvas c, float in) {
        float side = 460 * k, ax = w * 0.18f, ay = h / 2 - side / 2 + 24 * k * (1 - in);
        drawArt(c, ax, ay, side, 34 * k, in);
        float tx = ax + side + 72 * k;
        word.setTextSize(78 * k);
        word.setColor(Ui.alpha(0xFFFFFFFF, in));
        c.drawText(songTitleFit, tx, h / 2 - 20 * k, word);
        small.setTextSize(40 * k);
        small.setColor(Ui.alpha(lyricInk, in));
        c.drawText(artistFit, tx, h / 2 + 46 * k, small);
        small.setTextSize(28 * k);
        small.setColor(Ui.alpha(secondaryInk, in));
        c.drawText(status, tx, h / 2 + 120 * k, small);
        // Where the song is.
        if (duration > 0) {
            float bw = Math.min(700 * k, w - tx - 160 * k), p = Ui.clamp(songMs / duration), by = h / 2 + 170 * k;
            fill.setColor(Ui.alpha(secondaryInk, 0.5f * in));
            c.drawRoundRect(tx, by - 3 * k, tx + bw, by + 3 * k, 3 * k, 3 * k, fill);
            fill.setColor(Ui.alpha(lyricInk, in));
            c.drawRoundRect(tx, by - 3 * k, tx + bw * p, by + 3 * k, 3 * k, 3 * k, fill);
        }
    }

    private void drawArt(Canvas c, float x, float y, float side, float radius, float alpha) {
        if (artShader == null || art == null) {
            fill.setColor(Ui.alpha(0xFF1C1C22, alpha));
            r.set(x, y, x + side, y + side);
            c.drawRoundRect(r, radius, radius, fill);
            Symbols.draw(getContext(), c, "lyrics", x + side / 2, y + side / 2, side * 0.3f, Ui.alpha(secondaryInk, alpha));
            return;
        }
        float s = side / Math.min(art.getWidth(), art.getHeight());
        artMatrix.setScale(s, s);
        artMatrix.postTranslate(x + (side - art.getWidth() * s) / 2, y + (side - art.getHeight() * s) / 2);
        artShader.setLocalMatrix(artMatrix);
        artPaint.setShader(artShader);
        artPaint.setAlpha(Math.round(255 * alpha));
        r.set(x, y, x + side, y + side);
        c.drawRoundRect(r, radius, radius, artPaint);
    }

    /** Bottom left, small: the art, the title and the artist (and the time on the screensaver, top right). */
    private void drawChrome(Canvas c, float alpha) {
        float side = 84 * k, x = 72 * k, y = h - 72 * k - side;
        drawArt(c, x, y, side, 14 * k, 0.92f * alpha);
        small.setTextSize(30 * k);
        small.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * alpha));
        c.drawText(titleFit, x + side + 24 * k, y + side / 2 - 4 * k, small);
        small.setTextSize(25 * k);
        small.setColor(Ui.alpha(secondaryInk, alpha));
        c.drawText(artistFit, x + side + 24 * k, y + side / 2 + 32 * k, small);
        if (saver) {
            small.setTextSize(36 * k);
            small.setColor(Ui.alpha(0xFFFFFFFF, 0.78f * alpha));
            c.drawText(timeText, w - 72 * k - small.measureText(timeText), 96 * k, small);
        }
    }

    /** Nothing playing: how to start. */
    private void drawEmpty(Canvas c, float in) {
        float cy = h / 2 - 40 * k + 20 * k * (1 - in);
        Symbols.draw(getContext(), c, "lyrics", w / 2, cy - 90 * k, 120 * k, Ui.alpha(secondaryInk, in));
        word.setTextSize(58 * k);
        word.setColor(Ui.alpha(0xFFFFFFFF, in));
        c.drawText(emptyTitle, w / 2 - word.measureText(emptyTitle) / 2, cy + 40 * k, word);
        small.setTextSize(28 * k);
        small.setColor(Ui.alpha(secondaryInk, in));
        c.drawText(emptySub, w / 2 - small.measureText(emptySub) / 2, cy + 100 * k, small);
    }

    /** ↑ ↓: the style's name in a small capsule at the top, for a moment. */
    private void drawStyleLabel(Canvas c, float a) {
        small.setTextSize(30 * k);
        float tw = small.measureText(styleName), pw = tw + 64 * k, ph = 60 * k, y = 72 * k + 12 * k * (1 - a);
        r.set(w / 2 - pw / 2, y, w / 2 + pw / 2, y + ph);
        fill.setColor(Ui.alpha(0xFF2A2A30, 0.85f * a));
        c.drawRoundRect(r, ph / 2, ph / 2, fill);
        small.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(styleName, w / 2 - tw / 2, y + ph / 2 + Ui.cap(small) / 2, small);
    }

    private static int blend(int a, int b, float t) {
        return PasscodePad.blend(a, b, t);
    }
}
