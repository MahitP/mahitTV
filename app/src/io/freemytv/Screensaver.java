package io.freemytv;

import android.graphics.drawable.BitmapDrawable;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.KeyEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    /** While a song plays (Prefs.saverLyrics, on by default): Lyrics instead, until the music has been gone a while. */
    private LyricsView lyrics;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        // Interactive: the keys come here instead of waking the TV, so ← → ↓ can change the place and show its name.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        if (Prefs.saverLyrics() && musicPlaying()) {
            lyrics = new LyricsView(this, true, this::backToSaver);
            setContentView(lyrics);
        } else {
            view = new DreamView(this);
            setContentView(view);
        }
        // Until DreamView's first frame, the window shows the Frosted Horizon instead of Android's black dream window.
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            boolean dark = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(this).isNight(System.currentTimeMillis()));
            getWindow().setBackgroundDrawable(new BitmapDrawable(getResources(), Horizon.get(dm.widthPixels, dm.heightPixels, dark)));
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("screensaver background: " + e.getMessage());
        }
    }

    /** A music app is playing now (Now Playing's access; without it, never). */
    private boolean musicPlaying() {
        try {
            android.media.session.MediaSessionManager m = getSystemService(android.media.session.MediaSessionManager.class);
            for (android.media.session.MediaController mc : m.getActiveSessions(NowPlaying.component(this))) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING && GlowRing.isMusic(mc)) return true;
            }
        } catch (SecurityException | NullPointerException ignored) {
        }
        return false;
    }

    /** The music stopped a while ago: the screensaver's own, faded in. */
    private void backToSaver() {
        if (view != null) return;
        view = new DreamView(this);
        view.setAlpha(0);
        setContentView(view);
        lyrics = null;
        view.start();
        view.animate().alpha(1).setDuration(900).start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isInteractive()) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        // The volume keys keep working over the screensaver, as anywhere else.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (lyrics != null) {   // Lyrics: any key wakes the TV
            if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
            return true;
        }
        // As tvOS 12's Aerials: ← → the previous or next place, ↓ its name (Aerials and Nature Clock only).
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (arrow && view != null && (event.getAction() == KeyEvent.ACTION_UP || view.key(code))) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK acts when it's released, so the app it opens doesn't get the release; any other key closes at once.
        if (ok ? event.getAction() == KeyEvent.ACTION_UP : event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ok && view != null && Prefs.saverWatch()) view.watch();
            finish();
        }
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (lyrics != null) lyrics.stop();
        super.onDetachedFromWindow();
    }
}
