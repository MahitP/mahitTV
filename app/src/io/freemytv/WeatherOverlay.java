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
 * The full-screen forecast (TVOS-LAUNCHER-MERGE-SPEC.md S4): the next 24 hours and 7 days over the Frosted Horizon.
 * One Liquid Glass panel: on the left the city, now (a big temperature, the condition, high and low, humidity and wind)
 * and the next 24 hours (← → scroll them); on the right 7 days with temperature-range bars, blue to orange, as Apple's
 * Weather draws them. Dark text by day, white at night (as LockView). It comes in from 1.04× on SHEET_OPEN and leaves on
 * SHEET_CLOSE with Back. HomeView adds it like LockView and sends it keys while it shows.
 * Everything is measured and every string made in show()/onSizeChanged: a frame only moves springs.
 * Layout ideas adapted from tvOS-Launcher (Apache-2.0).
 */
final class WeatherOverlay extends View implements Choreographer.FrameCallback {
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF;
    private static final int VEIL_DAY = 0x1AFFFFFF, VEIL_NIGHT = 0x24000000;
    /** The range bars: cool to warm across the week's lowest to highest. */
    private static final int[] BAR = {0xFF5AC8FA, 0xFF34C759, 0xFFFFCC00, 0xFFFF9500};
    /** Layout, in 1080p px. */
    private static final float PANEL_L = 200, PANEL_T = 130, PANEL_R = 1720, PANEL_B = 950, RADIUS = 56;
    private static final float LEFT_X = 268, LEFT_W = 780, SPLIT_X = 1092, RIGHT_X = 1136, RIGHT_R = 1656;
    private static final float CELL = 112, HOURS_TOP = 640, ROW_TOP = 278, ROW_H = 92;
    private static final int STEP = 3;   // hours scrolled by ← →

    private final Scene scene;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0);
    private final Spring scroll = new Spring(Spring.SPATIAL_SCROLL, 0).px(), nudge = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private boolean night = true, posted, closing;
    private long lastNanos;
    private float w, h, k = 1;
    private int first, visible = 6;
    private Bitmap horizon, nowIcon;
    private final RectF screen = new RectF(), panel = new RectF(), tmp = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), veil = new Paint(), line = new Paint();
    private final Paint barTrack = new Paint(Paint.ANTI_ALIAS_FLAG), barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint cityP, condP, tempP, detailP, headP, hourP, hourTempP, rainP, dayP, lowP, highP, emptyP;

    // What's shown, made on show(): strings and icons for every hour and day.
    private boolean hasData;
    private String city = "", condition = "", temp = "", highLow = "", details = "", hoursHead = "", weekHead = "", empty = "";
    private String[] hourLabel = new String[0], hourTemp = new String[0], hourRain = new String[0];
    private Bitmap[] hourIcon = new Bitmap[0];
    private String[] dayLabel = new String[0], dayLow = new String[0], dayHigh = new String[0], dayRain = new String[0];
    private Bitmap[] dayIcon = new Bitmap[0];
    private float[] dayFrom = new float[0], dayTo = new float[0];

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
        first = 0;
        scroll.snap(0);
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

    /** Back leaves (on SHEET_CLOSE); ← → scroll the hours; it takes every key while it shows. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down) dismiss();
            return true;
        }
        if (!down) return true;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) scrollBy(1);
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) scrollBy(-1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) bump(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1, false);
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) dismiss();
        return true;
    }

    private void dismiss() {
        if (closing || getVisibility() != VISIBLE) return;
        closing = true;
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        wake();
    }

    private void scrollBy(int dir) {
        int max = Math.max(0, hourLabel.length - visible), next = Math.max(0, Math.min(max, first + dir * STEP));
        if (next == first) {
            bump(dir, true);
            return;
        }
        first = next;
        scroll.target = first * CELL * k;
        Click c = Click.current();
        if (c != null) c.move();
        wake();
    }

    private void bump(int dir, boolean sideways) {
        if (sideways) nudge.impulse(dir * Spring.BUMP_VELOCITY);
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
        boolean moving = appear.step(dt) | scroll.step(dt) | nudge.step(dt);
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

    /** 1.04 → 1 and fading in (SHEET_OPEN), the reverse leaving. */
    private void apply() {
        float v = Ui.clamp(appear.value), z = 1.04f - 0.04f * v;
        setScaleX(z);
        setScaleY(z);
        setAlpha(v);
    }

    // MARK: - Layout (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        setPivotX(w / 2);
        setPivotY(h / 2);
        screen.set(0, 0, w, h);
        panel.set(PANEL_L * k, PANEL_T * k, PANEL_R * k, PANEL_B * k);
        cityP = Ui.text(Ui.displaySemibold, 44 * k, WHITE);
        condP = Ui.text(Ui.medium, 30 * k, WHITE);
        tempP = Ui.numbers(Ui.thin, 168 * k, WHITE);
        detailP = Ui.text(Ui.medium, 25 * k, WHITE);
        headP = Ui.text(Ui.semibold, 22 * k, WHITE);
        hourP = Ui.numbers(Ui.medium, 22 * k, WHITE);
        hourP.setTextAlign(Paint.Align.CENTER);
        hourTempP = Ui.numbers(Ui.semibold, 28 * k, WHITE);
        hourTempP.setTextAlign(Paint.Align.CENTER);
        rainP = Ui.numbers(Ui.semibold, 19 * k, 0xFF2F8FE0);
        rainP.setTextAlign(Paint.Align.CENTER);
        dayP = Ui.text(Ui.semibold, 28 * k, WHITE);
        lowP = Ui.numbers(Ui.medium, 27 * k, WHITE);
        lowP.setTextAlign(Paint.Align.RIGHT);
        highP = Ui.numbers(Ui.semibold, 27 * k, WHITE);
        highP.setTextAlign(Paint.Align.RIGHT);
        emptyP = Ui.text(Ui.medium, 30 * k, WHITE);
        emptyP.setTextAlign(Paint.Align.CENTER);
        line.setStrokeWidth(Math.max(1, 1.5f * k));
        float barL = (RIGHT_R - 250) * k, barR = (RIGHT_R - 76) * k;
        barFill.setShader(new LinearGradient(barL, 0, barR, 0, BAR, null, Shader.TileMode.CLAMP));
        visible = Math.max(1, (int) (LEFT_W / CELL));
        theme();
        if (getVisibility() == VISIBLE) measure(HomeData.get(getContext()).weather);
    }

    /** The Horizon and the ink for the time of day. */
    private void theme() {
        if (w == 0 || cityP == null) return;
        horizon = Horizon.get((int) w, (int) h, night);
        int ink = night ? WHITE : INK;
        for (Paint p : new Paint[] {cityP, tempP, hourTempP, dayP, highP, emptyP}) p.setColor(ink);
        condP.setColor(Ui.alpha(ink, 0.8f));
        detailP.setColor(Ui.alpha(ink, 0.72f));
        headP.setColor(Ui.alpha(ink, 0.55f));
        hourP.setColor(Ui.alpha(ink, 0.72f));
        lowP.setColor(Ui.alpha(ink, 0.6f));
        rainP.setColor(night ? 0xFF64D2FF : 0xFF1E7FD6);
        line.setColor(Ui.alpha(ink, 0.12f));
        barTrack.setColor(Ui.alpha(ink, night ? 0.16f : 0.12f));
        veil.setColor(night ? VEIL_NIGHT : VEIL_DAY);
    }

    /** Every string and icon for this forecast. */
    private void measure(HomeData.Weather wx) {
        if (cityP == null) return;
        city = Prefs.city();
        hoursHead = Ui.tr("Next 24 Hours");
        weekHead = Ui.tr("7-Day Forecast");
        empty = Ui.tr("The forecast isn't here yet. It comes with the internet.");
        hasData = !Double.isNaN(wx.temp);
        if (!hasData) return;
        condition = HomeData.condition(wx.code, wx.isDay);
        temp = HomeData.degrees(wx.temp);
        highLow = HomeData.highLow(wx, "   ");
        String hum = wx.humidity >= 0 ? Ui.trf("Humidity {n}%", "n", wx.humidity) : "";
        String wind = Double.isNaN(wx.wind) ? "" : Ui.trf("Wind {speed}", "speed", HomeData.wind(wx.wind));
        details = hum.isEmpty() ? wind : wind.isEmpty() ? hum : Ui.trf("{a}  ·  {b}", "a", hum, "b", wind);
        nowIcon = WeatherIcons.get(wx.code, wx.isDay, Math.round(170 * k));
        int n = wx.hours24.size();
        hourLabel = new String[n];
        hourTemp = new String[n];
        hourRain = new String[n];
        hourIcon = new Bitmap[n];
        for (int i = 0; i < n; i++) {
            hourLabel[i] = i == 0 ? Ui.tr("Now") : HomeData.hour(wx.hours24.get(i));
            hourTemp[i] = HomeData.degrees(wx.hour24Temp.get(i));
            int r = wx.hour24Rain.get(i);
            hourRain[i] = r >= 20 ? Ui.trf("{n}%", "n", r) : null;
            hourIcon[i] = WeatherIcons.get(wx.hour24Code.get(i), wx.hour24Day.get(i), Math.round(60 * k));
        }
        int d = wx.week.size();
        dayLabel = new String[d];
        dayLow = new String[d];
        dayHigh = new String[d];
        dayRain = new String[d];
        dayIcon = new Bitmap[d];
        dayFrom = new float[d];
        dayTo = new float[d];
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
            dayIcon[i] = WeatherIcons.get(wx.weekCode.get(i), true, Math.round(52 * k));
            dayFrom[i] = (float) ((wx.weekMin.get(i) - lo) / span);
            dayTo[i] = (float) ((wx.weekMax.get(i) - lo) / span);
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || cityP == null) return;
        Horizon.draw(c, horizon, screen, image, 1);
        c.drawRect(screen, veil);
        scene.glass(c, panel, RADIUS * k, night ? 0xFF1C1C22 : WHITE, night ? 0.42f : 0.5f, WHITE, 0.06f, 0.04f, 1);
        float x = LEFT_X * k;
        c.drawText(city, x, 222 * k, cityP);
        if (!hasData) {
            c.drawText(empty, panel.centerX(), panel.centerY(), emptyP);
            return;
        }
        c.drawText(condition, x, 268 * k, condP);
        c.drawText(temp, x - 6 * k, 452 * k, tempP);
        if (nowIcon != null) c.drawBitmap(nowIcon, (LEFT_X + LEFT_W - 180) * k, 270 * k, image);
        c.drawText(highLow, x, 512 * k, detailP);
        c.drawText(details, x, 552 * k, detailP);

        // The next 24 hours: a row of cells clipped to the left column, scrolled by ← →.
        c.drawText(hoursHead, x, (HOURS_TOP - 20) * k, headP);
        c.drawLine(x, (HOURS_TOP - 4) * k, (LEFT_X + LEFT_W) * k, (HOURS_TOP - 4) * k, line);
        c.save();
        c.clipRect(x - 4 * k, HOURS_TOP * k, (LEFT_X + LEFT_W + 4) * k, (PANEL_B - 30) * k);
        float off = scroll.value - Spring.rubber(nudge.value) * k;
        for (int i = 0; i < hourLabel.length; i++) {
            float cx = x + (i + 0.5f) * CELL * k - off;
            if (cx < x - CELL * k || cx > (LEFT_X + LEFT_W + CELL) * k) continue;
            c.drawText(hourLabel[i], cx, (HOURS_TOP + 44) * k, hourP);
            Bitmap b = hourIcon[i];
            if (b != null) c.drawBitmap(b, cx - b.getWidth() / 2f, (HOURS_TOP + 62) * k, image);
            c.drawText(hourTemp[i], cx, (HOURS_TOP + 166) * k, hourTempP);
            if (hourRain[i] != null) c.drawText(hourRain[i], cx, (HOURS_TOP + 200) * k, rainP);
        }
        c.restore();

        // A hairline between the columns.
        c.drawLine(SPLIT_X * k, (PANEL_T + 60) * k, SPLIT_X * k, (PANEL_B - 60) * k, line);

        // 7 days: name, symbol (and the chance of rain), low, the range bar, high.
        float rx = RIGHT_X * k, rr = RIGHT_R * k;
        c.drawText(weekHead, rx, 222 * k, headP);
        float barL = (RIGHT_R - 250) * k, barR = (RIGHT_R - 76) * k, bh = 8 * k;
        for (int i = 0; i < dayLabel.length; i++) {
            float top = ROW_TOP * k + i * ROW_H * k, mid = top + ROW_H * k / 2;
            if (i > 0) c.drawLine(rx, top, rr, top, line);
            c.drawText(dayLabel[i], rx, mid + Ui.halfCap(dayP), dayP);
            Bitmap b = dayIcon[i];
            float ix = rx + 150 * k;
            if (b != null) c.drawBitmap(b, ix, mid - b.getHeight() / 2f - (dayRain[i] != null ? 9 * k : 0), image);
            if (dayRain[i] != null) c.drawText(dayRain[i], ix + 26 * k, mid + 34 * k, rainP);
            c.drawText(dayLow[i], barL - 16 * k, mid + Ui.halfCap(lowP), lowP);
            tmp.set(barL, mid - bh / 2, barR, mid + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, barTrack);
            tmp.set(barL + (barR - barL) * dayFrom[i], mid - bh / 2, barL + (barR - barL) * Math.max(dayTo[i], dayFrom[i] + 0.04f), mid + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, barFill);
            c.drawText(dayHigh[i], rr, mid + Ui.halfCap(highP), highP);
        }
    }
}
