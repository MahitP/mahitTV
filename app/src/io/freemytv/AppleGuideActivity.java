package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;

/**
 * FaceTime, Fitness+, Podcasts and Apple Music on a TV without them: an honest guide (they run on an iPhone or iPad and
 * reach the TV over AirPlay, where the TV has it), with a QR code to Apple's page. Nothing here pretends to be the app.
 * Opened from All Apps' Apple folder; Back or OK closes it.
 */
public class AppleGuideActivity extends Activity {
    static final String EXTRA_KEY = "guide";

    /** key → title, icon symbol, plate colours, the steps. */
    static String[] guide(String key) {
        switch (key) {
            case "facetime":
                return new String[] {"FaceTime", "videocam", "FF65DE6A", "FF1DB33A",
                        Ui.tr("Start the FaceTime call on your iPhone or iPad."),
                        Ui.tr("Open Control Center and choose Screen Mirroring, then this TV."),
                        Ui.tr("Keep your iPhone near you: its camera and microphone carry the call.")};
            case "fitness":
                return new String[] {"Fitness+", "fitness", "FF2C2C2E", "FF000000",
                        Ui.tr("Open the Fitness app on your iPhone or iPad and choose a workout."),
                        Ui.tr("Tap the AirPlay button and choose this TV."),
                        Ui.tr("Your Apple Watch keeps the rings and heart rate on your wrist.")};
            case "podcasts":
                return new String[] {"Podcasts", "podcasts", "FFD56DFB", "FF8C2AE8",
                        Ui.tr("Play an episode in Podcasts on your iPhone or iPad."),
                        Ui.tr("Tap the AirPlay button and choose this TV."),
                        Ui.tr("Scan the code to browse Apple Podcasts on the web.")};
            default:
                return new String[] {"Apple Music", "music", "FF2C2C2E", "FF000000",
                        Ui.tr("Play music in Apple Music on your iPhone or iPad."),
                        Ui.tr("Tap the AirPlay button and choose this TV."),
                        Ui.tr("Or install Apple Music for Android TV from Google Play, where it's offered.")};
        }
    }

    private String key;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if (key == null) key = "music";
        setContentView(new GuideView(this, key));
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private static final class GuideView extends View {
        private final String key;
        private final String[] g;
        private Bitmap qr, horizon;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        GuideView(Activity a, String key) {
            super(a);
            this.key = key;
            this.g = guide(key);
            try (InputStream in = a.getAssets().open("qr/" + ("music".equals(key) ? "applemusic" : key) + ".png")) {
                qr = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                qr = null;
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float k = w / 1920f;
            if (horizon == null) {
                try {
                    horizon = Horizon.get(w, h, true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    horizon = null;
                }
            }
            if (horizon != null) c.drawBitmap(horizon, null, new RectF(0, 0, w, h), bmp);
            else c.drawColor(0xFF1C2638);
            // The card: dark glass in the middle.
            r.set(260 * k, 170 * k, 1660 * k, 910 * k);
            fill.setColor(0xB81C1C1E);
            c.drawRoundRect(r, 44 * k, 44 * k, fill);
            fill.setColor(0x1FFFFFFF);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * k);
            c.drawRoundRect(r, 44 * k, 44 * k, fill);
            fill.setStyle(Paint.Style.FILL);
            // Icon plate.
            float px = 340 * k, py = 250 * k, ps = 150 * k;
            plate.setShader(new LinearGradient(0, py, 0, py + ps, (int) Long.parseLong(g[2], 16), (int) Long.parseLong(g[3], 16), Shader.TileMode.CLAMP));
            c.drawRoundRect(px, py, px + ps, py + ps, 34 * k, 34 * k, plate);
            Symbols.draw(getContext(), c, g[1], px + ps / 2, py + ps / 2, 78 * k, 0xFFFFFFFF);
            Paint title = Ui.text(Ui.displayBold, 64 * k, 0xFFFFFFFF);
            c.drawText(g[0], px + ps + 40 * k, py + 70 * k, title);
            Paint sub = Ui.text(Ui.medium, 28 * k, 0xB3FFFFFF);
            c.drawText(Ui.tr("Use your iPhone with AirPlay"), px + ps + 40 * k, py + 118 * k, sub);
            // The steps.
            TextPaint step = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xF2FFFFFF));
            Paint num = Ui.text(Ui.semibold, 26 * k, 0xFF1C1C1E);
            float y = 470 * k, tx = 410 * k, width = 820 * k;
            for (int i = 0; i < 3; i++) {
                fill.setColor(0xF2FFFFFF);
                c.drawCircle(px + 22 * k, y + 20 * k, 22 * k, fill);
                String n = String.valueOf(i + 1);
                c.drawText(n, px + 22 * k - num.measureText(n) / 2, y + 29 * k, num);
                String text = g[4 + i];
                StaticLayout l = StaticLayout.Builder.obtain(text, 0, text.length(), step, Math.round(width))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                c.save();
                c.translate(tx, y);
                l.draw(c);
                c.restore();
                y += Math.max(l.getHeight(), 44 * k) + 40 * k;
            }
            // The QR code on white, with its caption.
            float qs = 300 * k, qx = 1300 * k, qy = 300 * k;
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(qx - 20 * k, qy - 20 * k, qx + qs + 20 * k, qy + qs + 20 * k, 28 * k, 28 * k, fill);
            if (qr != null) {
                Paint sharp = new Paint();   // the code's modules stay crisp
                c.drawBitmap(qr, null, new RectF(qx, qy, qx + qs, qy + qs), sharp);
            }
            Paint cap = Ui.text(Ui.medium, 24 * k, 0xB3FFFFFF);
            String scan = Ui.tr("Scan with your iPhone");
            c.drawText(scan, qx + qs / 2 - cap.measureText(scan) / 2, qy + qs + 64 * k, cap);
            String back = Ui.tr("AirPlay needs a TV that supports it (Settings › Channels & Inputs › Apple AirPlay & HomeKit).");
            Paint foot = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
            c.drawText(back, w / 2f - foot.measureText(back) / 2, 860 * k, foot);
        }
    }
}
