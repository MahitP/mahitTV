package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import java.util.Calendar;

/**
 * The weather card (Control Center's weather platter → OK): Apple Weather's card floating in Liquid Glass over the home
 * screen (x 140–860, y 120–960, radius 40), the home dimmed behind it and blurred through the glass. The city, now (64
 * light), the condition, high and low, humidity, wind, sunrise and sunset; the next 6 hours (Now and 5 more, the chance
 * of rain in sky blue #5AC8FA, each label once: HomeData drops the clocks-back night's repeated hour); then 7 days, each
 * with its range as a bar on the week's scale, yellow to orange (#FACC15 → #FB923C), a white dot at the temperature now
 * on Today. Dark text by day, white at night (as LockView). It springs in from the right and 0.94× on SHEET_OPEN and
 * leaves on SHEET_CLOSE with Back or OK. HomeView adds it like LockView and sends it keys while it shows.
 * Everything is measured and every string made in show()/onSizeChanged: a frame only moves springs.
 * Layout ideas adapted from tvOS-Launcher's WeatherView (Apache-2.0).
 */
final class WeatherOverlay extends View implements Choreographer.FrameCallback {
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF;
    private static final int VEIL_DAY = 0x52162130, VEIL_NIGHT = 0x66000000;
    /** The range bars: yellow to orange across the week's lowest to highest. */
    private static final int[] BAR = {0xFFFACC15, 0xFFFB923C};
    private static final int RAIN = 0xFF5AC8FA;
    /** Layout, in 1080p px: the card, then each part from its left (L) and top (T). */
    private static final float L = 140, T = 120, R = 860, B = 960, RADIUS = 40, PAD = 48;
    private static final float STATS_X = 424, HOURS_TOP = 256, CELL = 104, WEEK_TOP = 450, ROW_H = 50;
    private static final float BAR_L = 332, BAR_W = 196;
    private static final int HOURS = 6;

    private final Scene scene;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0), nudge = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private boolean night = true, posted, closing;
    private long lastNanos;
    private float w, h, k = 1;
    private Bitmap nowIcon;
    private final RectF screen = new RectF(), panel = new RectF(), tmp = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), veil = new Paint(), line = new Paint();
    private final Paint barTrack = new Paint(Paint.ANTI_ALIAS_FLAG), barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), dotRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint cityP, condP, tempP, rangeP, statLabelP, statP, headP, hourP, hourTempP, rainP, dayP, lowP, highP, emptyP;

    // What's shown, made on show(): strings and icons for every hour and day.
    private boolean hasData;
    private String city = "", condition = "", temp = "", highLow = "", hoursHead = "", weekHead = "", empty = "";
    private final String[] statLabel = new String[4], statValue = new String[4];
    private String[] hourLabel = new String[0], hourTemp = new String[0], hourRain = new String[0];
    private Bitmap[] hourIcon = new Bitmap[0];
    private String[] dayLabel = new String[0], dayLow = new String[0], dayHigh = new String[0], dayRain = new String[0];
    private Bitmap[] dayIcon = new Bitmap[0];
    private float[] dayFrom = new float[0], dayTo = new float[0];
    private float nowAt = -1;   // where the temperature now sits on Today's bar (0..1), −1 without one
    private int todayRow = -1;

    WeatherOverlay(Activity a, Scene scene) {
        super(a);
        this.scene = scene;
        setFocusable(false);
        setVisibility(GONE);
    }

    /** Comes in over the home screen with today's data. */
    void show() {
        HomeData data = HomeData.get(getContext());
        night = Wallpaper.dark(Prefs.wallpaper(), data.isNight(System.currentTimeMillis()));
        closing = false;
        nudge.snap(0);
        appear.tune(Spring.SHEET_OPEN);
        appear.snap(0);
        appear.target = 1;
        theme();
        measure(data.weather);
        setContentDescription(Ui.trf("Weather in {city}", "city", city));
        setVisibility(VISIBLE);
        bringToFront();
        apply();
        wake();
    }

    void hide() {
        closing = false;
        setVisibility(GONE);
    }

    /** Taking keys: true from show() until Back starts it leaving. */
    boolean showing() {
        return getVisibility() == VISIBLE && !closing;
    }

    /** Back or OK leaves (on SHEET_CLOSE); the arrows only nudge it. Other keys (volume, media) go on to the system. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (!handles(code)) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down) dismiss();
            return true;
        }
        if (!down) return true;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) bump(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) bump(0);
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) dismiss();
        return true;
    }

    private static boolean handles(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    private void dismiss() {
        if (closing || getVisibility() != VISIBLE) return;
        closing = true;
        Click k = Click.current();
        if (k != null) k.sheet(false, 0.5f);   // the sheet's whisper out, as Control Center's and All Apps' have
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        wake();
    }

    /** Nothing that way: the card tugs toward the press and springs back. */
    private void bump(int dir) {
        if (dir != 0) nudge.impulse(dir * Spring.BUMP_VELOCITY);
        Click.boundary();
        wake();
    }

    // MARK: - Frames

    private void wake() {
        if (posted || getVisibility() != VISIBLE) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        posted = false;
        if (getVisibility() != VISIBLE) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(1 / 30f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean moving = appear.step(dt) | nudge.step(dt);
        apply();
        invalidate();
        if (closing && appear.target == 0 && (appear.settled() || appear.value <= 0.004f)) {
            closing = false;
            setVisibility(GONE);
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The veil fades in (the view's alpha); the card comes from 60 px right and 0.94× (drawn in onDraw). */
    private void apply() {
        setAlpha(Ui.clamp(appear.value));
    }

    // MARK: - Layout (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        panel.set(L * k, T * k, R * k, B * k);
        cityP = Ui.text(Ui.displaySemibold, 28 * k, WHITE);
        condP = Ui.text(Ui.semibold, 24 * k, WHITE);
        tempP = Ui.numbers(Ui.light, 64 * k, WHITE);
        rangeP = Ui.numbers(Ui.medium, 22 * k, WHITE);
        statLabelP = Ui.text(Ui.semibold, 17 * k, WHITE);
        statP = Ui.numbers(Ui.medium, 24 * k, WHITE);
        headP = Ui.text(Ui.semibold, 19 * k, WHITE);
        headP.setLetterSpacing(0.06f);
        hourP = Ui.numbers(Ui.semibold, 19 * k, WHITE);
        hourP.setTextAlign(Paint.Align.CENTER);
        hourTempP = Ui.numbers(Ui.medium, 24 * k, WHITE);
        hourTempP.setTextAlign(Paint.Align.CENTER);
        rainP = Ui.numbers(Ui.semibold, 16 * k, RAIN);
        rainP.setTextAlign(Paint.Align.CENTER);
        dayP = Ui.text(Ui.semibold, 24 * k, WHITE);
        lowP = Ui.numbers(Ui.medium, 24 * k, WHITE);
        lowP.setTextAlign(Paint.Align.RIGHT);
        highP = Ui.numbers(Ui.medium, 24 * k, WHITE);
        highP.setTextAlign(Paint.Align.RIGHT);
        emptyP = Ui.text(Ui.medium, 28 * k, WHITE);
        emptyP.setTextAlign(Paint.Align.CENTER);
        line.setStrokeWidth(Math.max(1, k));
        float barL = (L + BAR_L) * k, barR = (L + BAR_L + BAR_W) * k;
        barFill.setShader(new LinearGradient(barL, 0, barR, 0, BAR, null, Shader.TileMode.CLAMP));
        dot.setColor(WHITE);
        dotRim.setStyle(Paint.Style.STROKE);
        dotRim.setStrokeWidth(2 * k);
        theme();
        if (getVisibility() == VISIBLE) measure(HomeData.get(getContext()).weather);
    }

    /** The ink for the time of day. */
    private void theme() {
        if (w == 0 || cityP == null) return;
        int ink = night ? WHITE : INK;
        for (Paint p : new Paint[] {cityP, condP, tempP, statP, hourTempP, dayP, highP, emptyP}) p.setColor(ink);
        rangeP.setColor(Ui.alpha(ink, 0.62f));
        statLabelP.setColor(Ui.alpha(ink, 0.55f));
        headP.setColor(Ui.alpha(ink, 0.55f));
        hourP.setColor(Ui.alpha(ink, 0.62f));
        lowP.setColor(Ui.alpha(ink, 0.55f));
        line.setColor(Ui.alpha(ink, night ? 0.12f : 0.1f));
        barTrack.setColor(Ui.alpha(ink, night ? 0.16f : 0.12f));
        dotRim.setColor(Ui.alpha(INK, 0.3f));
        veil.setColor(night ? VEIL_NIGHT : VEIL_DAY);
    }

    /** Every string and icon for this forecast. */
    private void measure(HomeData.Weather wx) {
        if (cityP == null) return;
        city = Prefs.city();
        hoursHead = Ui.tr("Hourly Forecast").toUpperCase(Ui.locale());
        weekHead = Ui.tr("7-Day Forecast").toUpperCase(Ui.locale());
        empty = Ui.tr("The forecast isn't here yet. It comes with the internet.");
        hasData = !Double.isNaN(wx.temp);
        if (!hasData) return;
        condition = HomeData.condition(wx.code, wx.isDay);
        temp = HomeData.degrees(wx.temp);
        highLow = HomeData.highLow(wx, "  ");
        statLabel[0] = Ui.tr("Humidity");
        statValue[0] = wx.humidity >= 0 ? Ui.trf("{n}%", "n", wx.humidity) : "—";
        statLabel[1] = Ui.tr("Wind");
        statValue[1] = Double.isNaN(wx.wind) ? "—" : HomeData.wind(wx.wind);
        statLabel[2] = Ui.tr("Sunrise");
        statValue[2] = wx.sunrise != 0 ? Ui.time(wx.sunrise) : "—";
        statLabel[3] = Ui.tr("Sunset");
        statValue[3] = wx.sunset != 0 ? Ui.time(wx.sunset) : "—";
        nowIcon = WeatherIcons.get(wx.code, wx.isDay, Math.round(48 * k));
        int n = Math.min(HOURS, wx.hours24.size());
        hourLabel = new String[n];
        hourTemp = new String[n];
        hourRain = new String[n];
        hourIcon = new Bitmap[n];
        for (int i = 0; i < n; i++) {
            hourLabel[i] = i == 0 ? Ui.tr("Now") : HomeData.hour(wx.hours24.get(i));
            hourTemp[i] = HomeData.degrees(wx.hour24Temp.get(i));
            int r = wx.hour24Rain.get(i);
            hourRain[i] = r >= 20 ? Ui.trf("{n}%", "n", r) : null;
            hourIcon[i] = WeatherIcons.get(wx.hour24Code.get(i), wx.hour24Day.get(i), Math.round(34 * k));
        }
        int d = wx.week.size();
        dayLabel = new String[d];
        dayLow = new String[d];
        dayHigh = new String[d];
        dayRain = new String[d];
        dayIcon = new Bitmap[d];
        dayFrom = new float[d];
        dayTo = new float[d];
        nowAt = -1;
        todayRow = -1;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < d; i++) {
            lo = Math.min(lo, wx.weekMin.get(i));
            hi = Math.max(hi, wx.weekMax.get(i));
        }
        double span = Math.max(1, hi - lo);
        Calendar today = Calendar.getInstance();
        for (int i = 0; i < d; i++) {
            long at = wx.week.get(i);
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(at);
            boolean isToday = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
            dayLabel[i] = isToday ? Ui.tr("Today") : HomeData.weekday(at);
            dayLow[i] = HomeData.degrees(wx.weekMin.get(i));
            dayHigh[i] = HomeData.degrees(wx.weekMax.get(i));
            int r = wx.weekRain.get(i);
            dayRain[i] = r >= 20 ? Ui.trf("{n}%", "n", r) : null;
            dayIcon[i] = WeatherIcons.get(wx.weekCode.get(i), true, Math.round(30 * k));
            dayFrom[i] = (float) ((wx.weekMin.get(i) - lo) / span);
            dayTo[i] = (float) ((wx.weekMax.get(i) - lo) / span);
            if (isToday) {
                nowAt = (float) Math.max(0, Math.min(1, (wx.temp - lo) / span));
                todayRow = i;
            }
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || cityP == null) return;
        c.drawRect(screen, veil);
        float v = Ui.clamp(appear.value), z = 0.94f + 0.06f * v;
        c.save();
        c.translate(60 * k * (1 - v) + Spring.rubber(nudge.value) * k * 0.4f, 0);
        c.scale(z, z, panel.centerX(), panel.centerY());
        scene.glass(c, panel, RADIUS * k, night ? 0xFF1E2330 : 0xFFF6F7FA, night ? 0.9f : 0.88f, WHITE, 0.06f, 0.04f, 1);
        float x = (L + PAD) * k, right = (R - PAD) * k;
        c.drawText(city, x, (T + 70) * k, cityP);
        if (!hasData) {
            c.drawText(empty, panel.centerX(), panel.centerY(), emptyP);
            c.restore();
            return;
        }
        // Now: the symbol and the temperature, the condition, high and low; the four stats to the right.
        if (nowIcon != null) c.drawBitmap(nowIcon, x, (T + 96) * k, image);
        c.drawText(temp, x + 60 * k, (T + 146) * k, tempP);
        c.drawText(condition, x, (T + 186) * k, condP);
        c.drawText(highLow, x, (T + 218) * k, rangeP);
        for (int i = 0; i < 4; i++) {
            float sx = (L + STATS_X + (i % 2) * 130) * k, sy = (T + 76 + (i / 2) * 70) * k;
            c.drawText(statLabel[i], sx, sy, statLabelP);
            c.drawText(statValue[i], sx, sy + 30 * k, statP);
        }
        // The next 6 hours.
        float ht = (T + HOURS_TOP) * k;
        c.drawLine(x, ht - 16 * k, right, ht - 16 * k, line);
        c.drawText(hoursHead, x, ht + 16 * k, headP);
        for (int i = 0; i < hourLabel.length; i++) {
            float cx = x + (i + 0.5f) * CELL * k;
            c.drawText(hourLabel[i], cx, ht + 58 * k, hourP);
            Bitmap b = hourIcon[i];
            if (b != null) c.drawBitmap(b, cx - b.getWidth() / 2f, ht + 70 * k, image);
            if (hourRain[i] != null) c.drawText(hourRain[i], cx, ht + 126 * k, rainP);
            c.drawText(hourTemp[i], cx, ht + 156 * k, hourTempP);
        }
        // 7 days: name, symbol (and the chance of rain), low, the range bar (the dot: now, on Today), high.
        float wt = (T + WEEK_TOP) * k;
        c.drawLine(x, wt - 16 * k, right, wt - 16 * k, line);
        c.drawText(weekHead, x, wt + 16 * k, headP);
        float barL = (L + BAR_L) * k, barR = barL + BAR_W * k, bh = 8 * k;
        for (int i = 0; i < dayLabel.length; i++) {
            float mid = wt + (34 + ROW_H / 2 + i * ROW_H) * k;
            c.drawText(dayLabel[i], x, mid + Ui.halfCap(dayP), dayP);
            Bitmap b = dayIcon[i];
            float ix = x + 104 * k;
            if (b != null) c.drawBitmap(b, ix, mid - b.getHeight() / 2f, image);
            if (dayRain[i] != null) c.drawText(dayRain[i], ix + 66 * k, mid + Ui.halfCap(rainP), rainP);
            c.drawText(dayLow[i], barL - 20 * k, mid + Ui.halfCap(lowP), lowP);
            tmp.set(barL, mid - bh / 2, barR, mid + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, barTrack);
            tmp.set(barL + (barR - barL) * dayFrom[i], mid - bh / 2,
                    barL + (barR - barL) * Math.max(dayTo[i], dayFrom[i] + 0.06f), mid + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, barFill);
            if (i == todayRow && nowAt >= 0) {
                float dx = barL + (barR - barL) * nowAt;
                c.drawCircle(dx, mid, 6 * k, dot);
                c.drawCircle(dx, mid, 7 * k, dotRim);
            }
            c.drawText(dayHigh[i], right, mid + Ui.halfCap(highP), highP);
        }
        c.restore();
    }
}
