package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;

/**
 * Welly's answer cards (WELLY-HOME-REMOTE-SPEC.md §3.4): media, score, weather, schedule, home and music. Plain fills
 * with Apple's continuous corners (never glass: they sit over the frosted wallpaper, the pills are the one glass).
 * Sizes are in the 1920 × 1080 design; `prepare` fits the text and builds each card's path and image shader once, so
 * `draw` allocates nothing.
 */
final class WellyCards {
    static final int MEDIA = 0, SCORE = 1, WEATHER = 2, SCHEDULE = 3, HOME = 4, MUSIC = 5;
    static final float HEIGHT = 250, RADIUS = 28;
    private static final int FILL = 0xF02C2C2E, FILL_FOCUS = 0xFF3A3A3C, WHITE = 0xFFF5F5F7;

    static final class Card {
        int type;
        // media
        Recs.Pick pick;
        volatile Bitmap image;
        // media, music, home: the main line and the one under it
        String title = "", meta = "";
        // score
        String league = "", home = "", away = "", homeScore = "", awayScore = "", status = "";
        boolean live;
        // weather
        String place = "", temp = "", condition = "", highLow = "";
        int code = -1;
        boolean day = true;
        String[] hourLabel = new String[0], hourTemp = new String[0];
        int[] hourCode = new int[0];
        // schedule: {time, show, network}
        String[][] rows = new String[0][];
        // home
        String fixture = "", room = "";
        boolean on, ok = true;
        String problem = "";
        // music
        boolean playing;

        // Made by prepare(), for draw().
        final Path path = new Path();
        float w;
        BitmapShader shader;
        Bitmap shaderOf;
        String fitTitle = "", fitMeta = "", fitPlace = "", fitStatus = "";
        String[][] fitRows = new String[0][];
        int homeColor, awayColor;

        float width() {
            switch (type) {
                case MEDIA: return 400;
                case SCORE: return 300;
                case WEATHER: return 440;
                case SCHEDULE: return 540;
                case HOME: return 270;
                default: return 420;
            }
        }
    }

    private final Context context;
    private float k = -1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG), big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint score = new TextPaint(Paint.ANTI_ALIAS_FLAG), mono = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint row = new TextPaint(Paint.ANTI_ALIAS_FLAG), time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private float capTitle, capMeta, capCaption, capBig, capScore, capMono, capRow;

    WellyCards(Context c) {
        context = c;
    }

    /** The screen's scale (width / 1920): sizes every paint again. Never called from onDraw. */
    void setScale(float k) {
        if (k == this.k) return;
        this.k = k;
        style(title, Ui.semibold, 27, WHITE);
        style(meta, Ui.regular, 20, Ui.alpha(WHITE, 0.72f));
        style(caption, Ui.medium, 19, Ui.alpha(WHITE, 0.6f));
        style(big, Ui.light != null ? Ui.light : Ui.regular, 66, WHITE);
        style(score, Ui.semibold, 40, WHITE);
        score.setFontFeatureSettings("'tnum'");
        style(mono, Ui.semibold, 15, WHITE);
        style(row, Ui.medium, 23, WHITE);
        style(time, Ui.regular, 20, Ui.alpha(WHITE, 0.6f));
        time.setFontFeatureSettings("'tnum'");
        capTitle = Ui.cap(title);
        capMeta = Ui.cap(meta);
        capCaption = Ui.cap(caption);
        capBig = Ui.cap(big);
        capScore = Ui.cap(score);
        capMono = Ui.cap(mono);
        capRow = Ui.cap(row);
        scrim.setShader(new LinearGradient(0, HEIGHT * 0.38f * k, 0, HEIGHT * k, 0x00000000, 0xD0000000, Shader.TileMode.CLAMP));
        Symbols.warmWeather(context, 26 * k);
        Symbols.warmWeather(context, 44 * k);
        Symbols.warm(context, "play", 40 * k);
    }

    private void style(TextPaint p, android.graphics.Typeface face, float size, int color) {
        p.setTypeface(face);
        p.setTextSize(size * k);
        p.setColor(color);
        p.setSubpixelText(true);
    }

    /** Fits the text and builds the shape (and the image's shader) for the current scale. */
    void prepare(List<Card> cards) {
        for (Card c : cards) prepare(c);
    }

    void prepare(Card c) {
        if (k <= 0) return;
        c.w = c.width() * k;
        float h = HEIGHT * k, pad = 24 * k;
        Corners.continuous(c.path, c.w, h, RADIUS * k);
        float inner = c.w - 2 * pad;
        switch (c.type) {
            case MEDIA:
                c.fitTitle = fit(c.title, title, inner);
                c.fitMeta = fit(c.meta, meta, inner);
                Bitmap b = c.image;
                if (b != null && b != c.shaderOf) {
                    BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    float scale = Math.max(c.w / b.getWidth(), h / b.getHeight());
                    matrix.setScale(scale, scale);
                    matrix.postTranslate((c.w - b.getWidth() * scale) / 2, (h - b.getHeight() * scale) / 2);
                    s.setLocalMatrix(matrix);
                    c.shader = s;
                    c.shaderOf = b;
                }
                break;
            case SCORE:
                c.fitStatus = fit(c.status, caption, inner * 0.6f);
                c.homeColor = team(c.home);
                c.awayColor = team(c.away);
                break;
            case WEATHER:
                c.fitPlace = fit(c.place, caption, inner);
                c.fitMeta = fit(c.condition + "  " + c.highLow, meta, inner);
                break;
            case SCHEDULE:
                c.fitRows = new String[c.rows.length][];
                for (int i = 0; i < c.rows.length; i++) {
                    String[] r = c.rows[i];
                    String net = fit(r.length > 2 ? r[2] : "", time, inner * 0.3f);
                    float showW = inner - 118 * k - time.measureText(net) - 16 * k;
                    c.fitRows[i] = new String[] {r[0], fit(r.length > 1 ? r[1] : "", row, showW), net};
                }
                break;
            case HOME:
                c.fitPlace = fit(c.room, caption, inner);
                c.fitTitle = fit(c.title, title, inner);
                c.fitMeta = fit(!c.ok && !c.problem.isEmpty() ? c.problem : c.on ? Ui.tr("On") : Ui.tr("Off"), meta, inner - 40 * k);
                break;
            default:
                c.fitPlace = fit(c.meta, caption, inner);
                c.fitTitle = fit(c.title, title, inner - 60 * k);
                c.fitMeta = fit(c.status, meta, inner - 60 * k);
                break;
        }
    }

    private static String fit(String s, TextPaint p, float w) {
        if (s == null) return "";
        return TextUtils.ellipsize(s, p, Math.max(0, w), TextUtils.TruncateAt.END).toString();
    }

    /** A muted colour per team, the same every time (the monogram's disc). */
    private static int team(String abbr) {
        float hue = (abbr.hashCode() & 0x7fffffff) % 360;
        return Color.HSVToColor(new float[] {hue, 0.55f, 0.62f});
    }

    /**
     * Draws `c` with its top-left at the canvas origin (the caller translates and scales for the focus lift).
     * focus 0…1 brightens the fill.
     */
    void draw(Canvas cv, Card c, float focus) {
        float h = HEIGHT * k, pad = 24 * k;
        fill.setColor(blend(FILL, FILL_FOCUS, focus));
        if (c.type == MEDIA && c.shader != null) {
            image.setShader(c.shader);
            cv.drawPath(c.path, image);
            cv.drawPath(c.path, scrim);
        } else {
            cv.drawPath(c.path, fill);
        }
        switch (c.type) {
            case MEDIA:
                cv.drawText(c.fitTitle, pad, h - pad - capMeta - 14 * k, title);
                cv.drawText(c.fitMeta, pad, h - pad, meta);
                break;
            case SCORE: drawScore(cv, c, pad, h); break;
            case WEATHER: drawWeather(cv, c, pad, h); break;
            case SCHEDULE: drawSchedule(cv, c, pad); break;
            case HOME: drawHome(cv, c, pad, h); break;
            default: drawMusic(cv, c, pad, h); break;
        }
    }

    private void drawScore(Canvas cv, Card c, float pad, float h) {
        float top = pad + capCaption;
        cv.drawText(c.league, pad, top, caption);
        caption.setColor(c.live ? 0xFFFF453A : Ui.alpha(WHITE, 0.6f));
        cv.drawText(c.fitStatus, c.w - pad - caption.measureText(c.fitStatus), top, caption);
        caption.setColor(Ui.alpha(WHITE, 0.6f));
        int as = parse(c.awayScore), hs = parse(c.homeScore);
        boolean decided = !c.live && as >= 0 && hs >= 0;
        float rowH = (h - top - pad) / 2;
        teamRow(cv, c, c.away, c.awayScore, c.awayColor, top + rowH * 0.5f + 6 * k, !decided || as >= hs);
        teamRow(cv, c, c.home, c.homeScore, c.homeColor, top + rowH * 1.5f + 6 * k, !decided || hs >= as);
    }

    private void teamRow(Canvas cv, Card c, String team, String points, int color, float cy, boolean lead) {
        float pad = 24 * k, r = 22 * k;
        dot.setColor(color);
        cv.drawCircle(pad + r, cy, r, dot);
        float mw = mono.measureText(team);
        cv.drawText(team, pad + r - mw / 2, cy + capMono / 2, mono);
        title.setColor(Ui.alpha(WHITE, lead ? 1 : 0.55f));
        cv.drawText(team, pad + 2 * r + 16 * k, cy + capTitle / 2, title);
        title.setColor(WHITE);
        score.setColor(Ui.alpha(WHITE, lead ? 1 : 0.55f));
        cv.drawText(points, c.w - pad - score.measureText(points), cy + capScore / 2, score);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void drawWeather(Canvas cv, Card c, float pad, float h) {
        cv.drawText(c.fitPlace, pad, pad + capCaption, caption);
        float tempBase = pad + capCaption + 18 * k + capBig;
        cv.drawText(c.temp, pad - 2 * k, tempBase, big);
        if (c.code >= 0) Symbols.draw(context, cv, Symbols.weather(c.code, c.day), c.w - pad - 26 * k, pad + 46 * k, 44 * k, WHITE);
        cv.drawText(c.fitMeta, pad, tempBase + 14 * k + capMeta, meta);
        int n = Math.min(4, c.hourLabel.length);
        if (n == 0) return;
        float colW = (c.w - 2 * pad) / n, base = h - pad;
        for (int i = 0; i < n; i++) {
            float cx = pad + colW * i + colW / 2;
            float lw = time.measureText(c.hourLabel[i]);
            cv.drawText(c.hourLabel[i], cx - lw / 2, base - 52 * k, time);
            if (c.hourCode[i] >= 0) Symbols.draw(context, cv, Symbols.weather(c.hourCode[i], true), cx, base - 30 * k, 26 * k, WHITE);
            float tw = meta.measureText(c.hourTemp[i]);
            meta.setColor(WHITE);
            cv.drawText(c.hourTemp[i], cx - tw / 2, base, meta);
            meta.setColor(Ui.alpha(WHITE, 0.72f));
        }
    }

    private void drawSchedule(Canvas cv, Card c, float pad) {
        cv.drawText(Ui.tr("Tonight"), pad, pad + capCaption, caption);
        float y = pad + capCaption + 22 * k, rowH = 38 * k;
        for (int i = 0; i < c.fitRows.length && i < 5; i++) {
            String[] r = c.fitRows[i];
            float base = y + rowH * i + rowH / 2 + capRow / 2;
            cv.drawText(r[0], pad, base, time);
            cv.drawText(r[1], pad + 118 * k, base, row);
            cv.drawText(r[2], c.w - pad - time.measureText(r[2]), base, time);
        }
    }

    private void drawHome(Canvas cv, Card c, float pad, float h) {
        cv.drawText(c.fitPlace, pad, pad + capCaption, caption);
        cv.drawText(c.fitTitle, pad, pad + capCaption + 20 * k + capTitle, title);
        // The light itself: a warm disc when on, a hollow one when off.
        float r = 30 * k, cx = c.w - pad - r, cy = h - pad - r - 6 * k;
        if (c.on && c.ok) {
            dot.setColor(0x55FFC07A);
            cv.drawCircle(cx, cy, r * 1.35f, dot);
            dot.setColor(0xFFFFC07A);
            cv.drawCircle(cx, cy, r, dot);
        } else {
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth(3 * k);
            dot.setColor(Ui.alpha(WHITE, 0.45f));
            cv.drawCircle(cx, cy, r, dot);
            dot.setStyle(Paint.Style.FILL);
        }
        meta.setColor(!c.ok ? 0xFFFF9F0A : Ui.alpha(WHITE, 0.72f));
        cv.drawText(c.fitMeta, pad, h - pad, meta);
        meta.setColor(Ui.alpha(WHITE, 0.72f));
    }

    private void drawMusic(Canvas cv, Card c, float pad, float h) {
        cv.drawText(c.fitPlace, pad, pad + capCaption, caption);
        cv.drawText(c.fitTitle, pad, h - pad - capMeta - 14 * k, title);
        cv.drawText(c.fitMeta, pad, h - pad, meta);
        float cx = c.w - pad - 24 * k, cy = h - pad - 22 * k;
        dot.setColor(WHITE);
        if (c.playing) {   // pause bars
            cv.drawRect(cx - 12 * k, cy - 16 * k, cx - 4 * k, cy + 16 * k, dot);
            cv.drawRect(cx + 4 * k, cy - 16 * k, cx + 12 * k, cy + 16 * k, dot);
        } else {
            Symbols.draw(context, cv, "play", cx, cy, 40 * k, WHITE);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Ui.clamp(t);
        int ra = Color.alpha(a), rb = Color.alpha(b);
        return Color.argb(Math.round(ra + (rb - ra) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
