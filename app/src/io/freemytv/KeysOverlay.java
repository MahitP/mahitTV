package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * The volume and the brightness over every app but the home screen (which shows its own): the key service's own
 * windows (TYPE_ACCESSIBILITY_OVERLAY, which needs no other permission), on its thread, so they come with the key.
 * - The HUD: VolumeHud, as the home screen's, against the left edge.
 * - The dimmer: black over everything at 0.78 × (100 − brightness) %, as the home screen's own, so the channel keys dim
 *   the picture on any TV (many TCL panels ignore screen_brightness, which HomeMode.brightness also sets in mahitTV
 *   mode). While the home screen is in front it's hidden: the home screen dims itself.
 * Nothing is added until it's needed; the windows go with the service.
 */
final class KeysOverlay {
    private final AccessibilityService service;
    private final WindowManager wm;
    private View dimmer;
    private VolumeHud hud;
    private boolean homeInFront = true;

    KeysOverlay(AccessibilityService service) {
        this.service = service;
        wm = (WindowManager) service.getSystemService(android.content.Context.WINDOW_SERVICE);
    }

    private boolean ensure() {
        if (hud != null) return true;
        try {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            float k = m.widthPixels / 1920f;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            View d = new View(service);
            d.setBackgroundColor(0xFF000000);
            d.setAlpha(0);
            d.setVisibility(View.GONE);
            WindowManager.LayoutParams dp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            wm.addView(d, dp);
            VolumeHud h = new VolumeHud(service, null);
            int hw = Math.round(VolumeHud.VIEW_W * k), hh = Math.round(VolumeHud.VIEW_H * k);
            WindowManager.LayoutParams hp = new WindowManager.LayoutParams(hw, hh,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
            hp.gravity = Gravity.TOP | Gravity.LEFT;
            hp.x = 0;
            hp.y = Math.round(VolumeHud.CENTER_Y * k) - hh / 2;   // the middle of the left edge, as on the home screen
            wm.addView(h, hp);
            dimmer = d;
            hud = h;
            return true;
        } catch (RuntimeException e) {
            AppLog.w("keys overlay: " + e.getMessage());
            return false;
        }
    }

    /** A channel key in another app: the brightness by `delta` percent, the dimmer at once, the HUD with the sun. */
    void brightness(int delta) {
        if (!ensure()) return;
        int was = Prefs.brightness(), b = Math.max(0, Math.min(100, was + delta));
        if (b != was) {
            Prefs.setBrightness(b);
            HomeMode.brightness(service, b);   // the TV's own too, in mahitTV mode
        }
        dim();
        hud.showBrightness(b, b == was ? Integer.signum(delta) : 0);
    }

    /** A volume key in another app, once the volume changed (RemoteKeys.adjustVolume): the HUD with the speaker. */
    void volume(int percent, boolean muted, int push) {
        if (!ensure()) return;
        hud.show(percent, muted, push);
    }

    /** The window in front changed: over the home screen, neither (it has its own); anywhere else, the dimmer. */
    void home(boolean inFront) {
        homeInFront = inFront;
        if (inFront && hud != null) hud.dismiss();
        dim();
    }

    private void dim() {
        int b = Prefs.brightness();
        boolean on = !homeInFront && b < 100;
        if (on && !ensure()) return;
        if (dimmer == null) return;
        dimmer.setAlpha(0.78f * (100 - b) / 100f);
        dimmer.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** The service is going (Original, or the switch turned it off): its windows with it. */
    void gone() {
        for (View v : new View[] {dimmer, hud}) {
            if (v == null) continue;
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        dimmer = null;
        hud = null;
    }
}
