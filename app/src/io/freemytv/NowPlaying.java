package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {
    /** Only a new title counts (bars, TalkBack): not a subtitle some players rewrite every second, nor the screen showing again. */
    static boolean newTitle(String title, String line, boolean showing, String shown) {
        return title != null && line != null && showing && !title.equals(shown);
    }


    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;
    /** How often a playing title's position goes into Continue Watching. */
    private static final long PROGRESS_MS = 30_000;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;
        /** Continue Watching: the app playing and when its position was last written (at most every PROGRESS_MS). */
        private MediaController progressOf;
        private long progressAt;
        private final Runnable progressTick = () -> progress(true);
        /** Control Center's playback card: the session it follows (Transport.pick), and what it says. */
        private MediaController media;
        volatile String mediaTitle, mediaDetail;
        /** The playing app's package and name (the dynamic dot opens it on a long press). */
        volatile String mediaPkg, mediaApp;
        volatile boolean mediaPlaying;
        /** The dynamic dot's picture: the album art (a square of at most ART px), else the app's icon. Main thread. */
        Bitmap mediaArt;
        private Bitmap artFrom;
        private final Map<String, Bitmap> icons = new HashMap<>();
        private long mediaActions;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            if (progressOf != null) report(progressOf);   // where it was when the home screen went away
            progressOf = null;
            handler.removeCallbacks(progressTick);
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            MediaController playingNow = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    playingNow = mc;
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    break;
                }
            }
            playing(playingNow);
            title = nowTitle;
            boolean mediaChanged = updateMedia();
            if (Objects.equals(now, text) && !mediaChanged) return;
            text = now;
            changed.run();
        }

        /** The session the card follows and what it says; true if any of it changed. */
        private boolean updateMedia() {
            int[] states = new int[controllers.size()];
            for (int i = 0; i < states.length; i++) {
                PlaybackState s = controllers.get(i).getPlaybackState();
                states[i] = s == null ? PlaybackState.STATE_NONE : s.getState();
            }
            int at = Transport.pick(states);
            MediaController mc = at < 0 ? null : controllers.get(at);
            String newTitle = null, newDetail = null;
            boolean nowPlaying = false;
            long actions = 0;
            if (mc != null) {
                nowPlaying = Transport.isPlaying(states[at]);
                actions = mc.getPlaybackState().getActions();
                String line = describe(mc);
                MediaMetadata m = mc.getMetadata();
                CharSequence t = m == null ? null : m.getDescription().getTitle();
                newTitle = !TextUtils.isEmpty(t) ? t.toString().trim() : line == null ? mc.getPackageName() : line;
                newDetail = line == null || line.equals(newTitle) ? "" : line.startsWith(newTitle + " · ") ? line.substring(newTitle.length() + 3) : line;
            }
            Bitmap art = art(mc);
            boolean same = mc == media && Objects.equals(newTitle, mediaTitle) && Objects.equals(newDetail, mediaDetail)
                    && nowPlaying == mediaPlaying && actions == mediaActions && art == mediaArt;
            mediaArt = art;
            media = mc;
            mediaPkg = mc == null ? null : mc.getPackageName();
            mediaApp = mediaPkg == null ? null : labels.get(mediaPkg);
            mediaTitle = newTitle;
            mediaDetail = newDetail;
            mediaPlaying = nowPlaying;
            mediaActions = actions;
            return !same;
        }

        static final int ART = 128;

        /**
         * The player's art for this song, cut to a square once per new picture (players report every second; the same
         * picture comes back as the same object), else the app's icon, kept per app.
         */
        private Bitmap art(MediaController mc) {
            if (mc == null) return null;
            MediaMetadata m = mc.getMetadata();
            Bitmap src = null;
            if (m != null) {
                src = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (src == null) src = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (src == null) src = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (src == null && m.getDescription() != null) src = m.getDescription().getIconBitmap();
            }
            if (src != null && src.getWidth() > 0 && src.getHeight() > 0) {
                if (src == artFrom && mediaArt != null) return mediaArt;
                artFrom = src;
                try {
                    int side = Math.min(src.getWidth(), src.getHeight());
                    Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                    Bitmap out = Bitmap.createScaledBitmap(square, Math.min(ART, side), Math.min(ART, side), true);
                    if (square != src && square != out) square.recycle();
                    return out;
                } catch (RuntimeException e) {   // a recycled or odd bitmap from the player: the icon instead
                    artFrom = null;
                }
            }
            String pkg = mc.getPackageName();
            if (icons.containsKey(pkg)) return icons.get(pkg);
            Bitmap icon = null;
            try {
                Drawable d = context.getPackageManager().getApplicationIcon(pkg);
                icon = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(icon);
                c.drawColor(0xFF1C1C1E);
                d.setBounds(ART / 8, ART / 8, ART * 7 / 8, ART * 7 / 8);
                d.draw(c);
            } catch (PackageManager.NameNotFoundException e) {
                icon = null;
            }
            icons.put(pkg, icon);
            return icon;
        }

        boolean hasMedia() {
            return media != null;
        }

        /** OK on the card. TransportControls are calls to the player: off the UI thread. */
        void playPause() {
            MediaController mc = media;
            if (mc == null) return;
            boolean pause = mediaPlaying;
            WRITER.execute(() -> {
                if (pause) mc.getTransportControls().pause(); else mc.getTransportControls().play();
            });
        }

        /** Whether the player takes a skip that way: Netflix and other video apps often don't. */
        boolean canSkip(int dx) {
            return (mediaActions & (dx > 0 ? PlaybackState.ACTION_SKIP_TO_NEXT : PlaybackState.ACTION_SKIP_TO_PREVIOUS)) != 0;
        }

        /** ← or → on the card; false when the player can't, for the dead-end knock. */
        boolean skip(int dx) {
            MediaController mc = media;
            if (mc == null || !canSkip(dx)) return false;
            WRITER.execute(() -> {
                if (dx > 0) mc.getTransportControls().skipToNext(); else mc.getTransportControls().skipToPrevious();
            });
            return true;
        }

        /**
         * Continue Watching (WatchLog.playing): the playing app's title and position, at most every PROGRESS_MS while
         * it plays and once when it pauses or stops. The work is a small JSON write, off the main thread.
         */
        private void playing(MediaController mc) {
            if (mc == progressOf) {
                if (mc != null) progress(false);
                return;
            }
            if (progressOf != null) report(progressOf);   // paused, stopped or replaced: its last position
            progressOf = mc;
            progressAt = 0;
            handler.removeCallbacks(progressTick);
            if (mc != null) progress(false);
        }

        private void progress(boolean tick) {
            MediaController mc = progressOf;
            if (mc == null || !on) return;
            long now = SystemClock.elapsedRealtime();
            if (tick || now - progressAt >= PROGRESS_MS) {
                progressAt = now;
                report(mc);
            }
            handler.removeCallbacks(progressTick);
            handler.postDelayed(progressTick, PROGRESS_MS);
        }

        private void report(MediaController mc) {
            PlaybackState s = mc.getPlaybackState();
            MediaMetadata m = mc.getMetadata();
            if (s == null) return;
            long position = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0 && position >= 0) {
                position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            CharSequence title = m == null ? null : m.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && m != null && m.getDescription() != null) title = m.getDescription().getTitle();
            final String pkg = mc.getPackageName(), name = title == null ? null : title.toString().trim();
            final long pos = position, dur = duration;
            final Context c = context.getApplicationContext();
            WRITER.execute(() -> WatchLog.playing(c, pkg, name, pos, dur));
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
