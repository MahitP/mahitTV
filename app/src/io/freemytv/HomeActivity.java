package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;

/**
 * The mahitTV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    /** Opens Recent Apps (Home held, RemoteKeys). */
    static final String EXTRA_RECENTS = "io.freemytv.RECENTS";
    /** Two Home presses this close together open Recent Apps (tvOS's double press of the TV button). */
    static final long DOUBLE_HOME_MS = 400;
    private long lastHome;
    private HomeView home;
    private WallpaperVideoView wallpaperVideo;
    private AerialCache aerialCache;
    private final AerialRequest aerialRequest = new AerialRequest();
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            // On the Frosted Horizon (day or twilight by the hour: nothing else is loaded on this path), never a void.
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            boolean dark = hour < 7 || hour >= 19;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            TextView t = new TextView(this);
            t.setText(Ui.tr("mahitTV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(dark ? Color.WHITE : 0xFF1D1D1F);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            try {
                t.setBackground(new BitmapDrawable(getResources(), Horizon.get(dm.widthPixels, dm.heightPixels, dark)));
            } catch (RuntimeException | OutOfMemoryError e) {
                t.setBackgroundColor(dark ? 0xFF1C2638 : 0xFFDCE6EF);   // the Horizon's mid-sky
            }
            setContentView(t);
            return;
        }
        Ui.init(this);
        aerialCache = new AerialCache(new File(getCacheDir(), "aerials"));
        wallpaperVideo = new WallpaperVideoView(this);
        home = new HomeView(this, aerialCache, new HomeView.AerialPlayback() {
            @Override public void show(long generation, String url) {
                final long token = aerialRequest.show(url);
                aerialCache.fetch(url, file -> runOnUiThread(() -> {
                    if (!aerialRequest.matches(token, url)) return;
                    if (file == null) { wallpaperVideo.hide(); home.aerialFailed(generation, url); }
                    // The sky stops drawing only once the video's first frame is on screen (no black gap while it starts).
                    else if (aerialRequest.videoReady(token, url)) wallpaperVideo.show(file, () -> home.aerialReady(generation, url), () -> home.aerialFailed(generation, url));
                }));
            }
            @Override public void hide(long generation) { aerialRequest.hide(); wallpaperVideo.hide(); }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(wallpaperVideo, new FrameLayout.LayoutParams(-1, -1));
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        if (getIntent().getBooleanExtra(EXTRA_RECENTS, false)) home.showRecents();
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    /** The home already redrew itself for a new profile (HomeView.applyProfile): no recreate for that change. */
    void profileApplied() {
        prefsVersion = Prefs.version;
    }

    @Override protected void onPause() {
        aerialRequest.hide();
        if (wallpaperVideo != null) wallpaperVideo.hide();
        if (home != null) home.pause();
        super.onPause();
    }

    /**
     * Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). Pressed
     * twice within DOUBLE_HOME_MS: Recent Apps.
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            home.showAllApps();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_RECENTS, false)) {
            lastHome = 0;
            home.showRecents();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = lastHome != 0 && now - lastHome < DOUBLE_HOME_MS;
        lastHome = twice ? 0 : now;
        if (twice) home.showRecents();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        if (wallpaperVideo != null) wallpaperVideo.release();
        if (aerialCache != null) aerialCache.close();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.volumeKey(event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
