package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The home screen's acoustic feedback engine. Every sound is synthesized by the app (16-bit PCM, written once as WAVs
 * in the cache, then played from memory by SoundPool: nothing is decoded when one plays):
 * <ul>
 * <li>`move()`, the focus tick: a 28 ms glass-and-wood tick (three modes, 1.24 / 2.71 / 4.38 kHz, over a 0.6 ms
 * transient), panned to where the focus landed on screen (`focusAt`), its pitch and level varied a little from one tick
 * to the next so a run of them never sounds mechanical, lighter when the focus is moving fast (a held arrow) and never
 * more than ~28 a second.</li>
 * <li>`open()`, select: a soft 196 Hz press under a G6 glass ping, 150 ms.</li>
 * <li>`bump()`: a muted knock at a dead end, on the same frame as the views' bump (ANIMATIONS-SPEC.md §5.4).</li>
 * <li>`toggle(on)`: two blips, C6 → G6 rising for on, falling for off.</li>
 * <li>`holdStart()`, `holdCancel()`, `holdCommit()`: a low mechanical tension (110 Hz and a ratcheting grain, rising
 * in pitch and level to the hold's threshold), faded out if OK is let go first, and a snap when the hold commits.</li>
 * <li>`sheet(open, pan)`: an air-release whisper, noise through a low-pass sweeping up (in) or down (out), for Control
 * Center, Inputs, All Apps and folders.</li>
 * </ul>
 * The views call these on the key event that starts the spring, so sound and motion begin on the same frame; the tick
 * waits only for the end of that event (the view places the focus first, then `focusAt` pans the tick there). SoundPool's
 * own calls can wait on the audio server, so they run on the sound's thread, never on the UI thread. All of it follows
 * Settings → Navigation sounds and plays under the system volume, only on the home screen, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    /** Each cue's level (0–1, before the system volume): the tick is the quietest, select and the knock a little over. */
    private static final float TICK = 0.13f, SELECT = 0.20f, BUMP = 0.15f, TOGGLE = 0.16f, TENSION = 0.16f, COMMIT = 0.20f;
    private static final float SHEET = 0.14f, ERROR = 0.22f, CHIME = 0.36f;
    /** How wide the panning goes (1: hard left to hard right): a hint of place, never a jump between speakers. */
    private static final float PAN_WIDTH = 0.6f;
    /** The tick's variations (pitch, level), in turn: a run of ticks never sounds like one sample repeated. */
    private static final float[] RATES = {1f, 1.012f, 0.994f, 1.006f, 0.988f}, GAINS = {1f, 0.94f, 0.97f, 0.92f, 0.98f};
    /** Ticks closer than this are dropped; closer than FAST, lighter (a held arrow scrubbing). */
    private static final long MIN_GAP_MS = 35, FAST_MS = 90;

    private static Click current;
    private volatile SoundPool pool;
    private int tick, select, bump, toggleOn, toggleOff, tension, commit, sheetIn, sheetOut, chime, error;
    /** SoundPool's own thread (see the class comment), and the UI thread for what waits for the end of a key event. */
    private final HandlerThread thread = new HandlerThread("click", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler audio, main = new Handler(Looper.getMainLooper());
    private float pan = 0.5f, fade;
    private boolean tickPending;
    private long lastTickAt;
    private int variation, tensionStream;

    Click(Context c) {
        thread.start();
        audio = new Handler(thread.getLooper());
        try {
            pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            for (String old : new String[] {"click-move.wav", "click-open.wav", "click-bump.wav", "click-error.wav"}) {
                new File(c.getCacheDir(), old).delete();   // the first single-sine clicks
            }
            tick = pool.load(cue(c, "ui-tick.wav", 0.028f, Click::tick), 1);
            select = pool.load(cue(c, "ui-select.wav", 0.15f, Click::select), 1);
            bump = pool.load(cue(c, "ui-bump.wav", 0.07f, Click::knock), 1);
            toggleOn = pool.load(cue(c, "ui-toggle-on.wav", 0.09f, m -> blips(m, 1046.5, 1568.0)), 1);
            toggleOff = pool.load(cue(c, "ui-toggle-off.wav", 0.09f, m -> blips(m, 1568.0, 1046.5)), 1);
            tension = pool.load(cue(c, "ui-tension.wav", 0.56f, Click::tension), 1);
            commit = pool.load(cue(c, "ui-commit.wav", 0.12f, Click::commit), 1);
            sheetIn = pool.load(cue(c, "ui-sheet-in.wav", 0.26f, m -> air(m, 260, 1700, 0.12)), 1);
            sheetOut = pool.load(cue(c, "ui-sheet-out.wav", 0.20f, m -> air(m, 1500, 220, 0.03)), 1);
            chime = pool.load(chord(c), 1);
            error = pool.load(cue(c, "ui-error.wav", 0.18f, Click::error), 1);
            current = this;
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    // MARK: - Playing

    /** The focus moved: a tick, at the end of this key event (once `focusAt` has placed it), in the focus's place. */
    void move() {
        if (tickPending) return;
        tickPending = true;
        main.post(flushTick);
    }

    /** Where the focus is now, 0 (left edge) to 1 (right edge): the pending tick plays there. */
    void focusAt(float x) {
        pan = Math.max(0, Math.min(1, x));
    }

    private final Runnable flushTick = () -> {
        tickPending = false;
        float at = pan;
        pan = 0.5f;   // a tick from a view that doesn't place it plays in the middle
        if (pool == null || !Prefs.sounds()) return;
        long now = SystemClock.uptimeMillis(), gap = now - lastTickAt;
        if (gap < MIN_GAP_MS) return;
        lastTickAt = now;
        float level = TICK * GAINS[variation] * (gap < FAST_MS ? 0.62f : 1f);
        play(tick, level, at, RATES[variation]);
        variation = (variation + 1) % RATES.length;
    };

    /** Select: opening an app, pressing OK on a card or a control. */
    void open() {
        play(select, SELECT, 0.5f, 1);
    }

    /** Focus can't go further that way. */
    void bump() {
        play(bump, BUMP, 0.5f, 1);
    }

    /** A switch went on or off. */
    void toggle(boolean on) {
        play(on ? toggleOn : toggleOff, TOGGLE, 0.5f, 1);
    }

    /** OK went down where holding it does something: the tension builds toward the hold's threshold. */
    void holdStart() {
        if (pool == null) return;
        boolean on = Prefs.sounds();
        audio.post(() -> {
            holdStop();
            SoundPool p = pool;
            if (on && p != null) tensionStream = p.play(tension, TENSION, TENSION, 1, 0, 1f);
        });
    }

    /** Let go before the hold: the tension fades out over 40 ms (a stop mid-wave would click). */
    void holdCancel() {
        if (pool == null) return;
        audio.post(() -> {
            if (tensionStream == 0) return;
            audio.removeCallbacks(fadeStep);
            fade = 1;
            audio.post(fadeStep);
        });
    }

    /** The hold took: the tension stops and a snap answers it. */
    void holdCommit() {
        if (pool == null) return;
        audio.post(this::holdStop);
        play(commit, COMMIT, 0.5f, 1);
    }

    /** On the sound's thread, like everything that touches the tension's stream. */
    private void holdStop() {
        audio.removeCallbacks(fadeStep);
        SoundPool p = pool;
        if (tensionStream != 0 && p != null) p.stop(tensionStream);
        tensionStream = 0;
    }

    private final Runnable fadeStep = this::fadeTension;

    private void fadeTension() {
        SoundPool p = pool;
        if (tensionStream == 0 || p == null) return;
        fade -= 0.25f;
        if (fade <= 0) {
            p.stop(tensionStream);
            tensionStream = 0;
            return;
        }
        p.setVolume(tensionStream, TENSION * fade, TENSION * fade);
        audio.postDelayed(fadeStep, 10);
    }

    /** A sheet or panel slides in (or out), from `pan` (0 left … 1 right: Control Center comes from the top right). */
    void sheet(boolean open, float pan) {
        play(open ? sheetIn : sheetOut, SHEET, pan, 1);
    }

    /** mahitTV's startup sound (Intro, from its first frame: it carries its own timing, stereo). */
    void chime() {
        send(chime, CHIME, CHIME, 2, 1f);
    }

    /** A dull thud: a wrong PIN. */
    void error() {
        play(error, ERROR, 0.5f, 1);
    }

    /** Plays `id` at `level`, panned (equal power, PAN_WIDTH wide; the middle at full level), at `rate`. */
    private void play(int id, float level, float at, float rate) {
        double p = (at - 0.5) * PAN_WIDTH, angle = (p + 1) * Math.PI / 4;
        send(id, (float) Math.min(1, level * Math.sqrt(2) * Math.cos(angle)),
                (float) Math.min(1, level * Math.sqrt(2) * Math.sin(angle)), 1, rate);
    }

    static Click current() {
        return current;
    }

    /** The knock, from any view of the home screen. */
    static void boundary() {
        Click c = current;
        if (c != null) c.bump();
    }

    private void send(int id, float left, float right, int priority, float rate) {
        if (pool == null || id == 0 || !Prefs.sounds()) return;
        audio.post(() -> {
            SoundPool p = pool;
            if (p != null) p.play(id, left, right, priority, 0, rate);
        });
    }

    void release() {
        main.removeCallbacks(flushTick);
        audio.removeCallbacks(fadeStep);
        SoundPool p = pool;
        pool = null;
        if (p != null) audio.post(p::release);   // after the plays already queued, never during one
        thread.quitSafely();
        if (current == this) current = null;
    }

    /** The startup's sound is stereo at CD rate; the clicks stay mono at RATE. */
    private static final int CHIME_RATE = 44_100;

    /**
     * The startup's acoustic signature ("Anamorphic Horizon & Optical Liquid Glass Ignition"), 3.2 s of 16-bit 44.1 kHz
     * stereo, synced to Intro's acts from its first frame:
     * <ol>
     * <li>A warm sub-bass horizon swell, C2 + G2 (65.41 / 98.00 Hz: sines with a touch of their third harmonic, a
     * triangle's warmth without its buzz), from 0.05 s with a 260 ms raised-cosine attack as the seam opens, gone by
     * 2.60 s.</li>
     * <li>The crystalline Cmaj9(#11) bloom (C4 E4 G4 B4 D5, then a quiet F♯5 on top) as the glyphs condense: rolled 45 ms apart from 0.48 s, each a
     * chorus of two voices detuned ∓1.8 cents into the left and right channels, 88% sine and 12% a 2:1 FM bell (its
     * index dying away like struck glass), into a 2.2 s reverb; it fades out from 2.90 s.</li>
     * <li>The prismatic shimmer (E5 G5 B5 D6) as the −28° beam crosses the word, 1.15–1.85 s: velvet sine pings 18 dB
     * under the chord, panned left to right as the light travels.</li>
     * </ol>
     * The reverb is a small Schroeder room (four damped combs per channel, offset for width, then two all-passes) whose
     * combs ring down 60 dB in 2.2 s. Peak-normalised to −3 dBFS. Cached once in the app's cache folder.
     */
    private static String chord(Context c) throws IOException {
        new File(c.getCacheDir(), "chime-mahittv.wav").delete();         // the golden intro's chord
        new File(c.getCacheDir(), "chime-mahittv-glass.wav").delete();   // the first glass chord (mono)
        new File(c.getCacheDir(), "chime-mahittv-horizon.wav").delete();   // the chord without its #11
        File f = new File(c.getCacheDir(), "chime-mahittv-lydian.wav");
        if (f.exists() && f.length() > 44) return f.getPath();
        final int rate = CHIME_RATE;
        final double seconds = 3.2;
        int n = (int) Math.round(rate * seconds);
        float[] dryL = new float[n], dryR = new float[n], sendL = new float[n], sendR = new float[n];

        // Layer 1: the sub swell (dry only: a reverb would muddy it).
        double[][] sub = {{65.41, 0.30}, {98.00, 0.22}};
        for (double[] v : sub) {
            double step = 2 * Math.PI * v[0] / rate;
            int from = (int) (0.05 * rate), to = (int) (2.60 * rate);
            for (int i = from; i < to; i++) {
                double t = (double) (i - from) / rate, env = raised(t / 0.26) * (1 - raised(((double) i / rate - 1.95) / 0.65));
                double ph = step * (i - from), s = Math.sin(ph) + 0.08 * Math.sin(3 * ph);
                float y = (float) (v[1] * env * s);
                dryL[i] += y;
                dryR[i] += y;
            }
        }

        // Layer 2: the Cmaj9(#11) glass bloom, chorused across the channels, into the reverb. The #11 is last and quietest:
        // it opens the chord up without turning its tritone over the C into tension.
        double[] notes = {261.63, 329.63, 392.00, 493.88, 587.33, 739.99}, levels = {0.11, 0.10, 0.10, 0.085, 0.075, 0.05};
        double down = Math.pow(2, -1.8 / 1200), up = Math.pow(2, 1.8 / 1200);
        for (int j = 0; j < notes.length; j++) {
            double start = 0.48 + 0.045 * j;
            bell(dryL, sendL, rate, notes[j] * down, start, levels[j]);
            bell(dryR, sendR, rate, notes[j] * up, start, levels[j]);
        }

        // Layer 3: the shimmer, left to right with the beam, 18 dB under the chord (≈ 0.126 of its level).
        double[] pings = {659.25, 783.99, 987.77, 1174.66}, at = {1.15, 1.33, 1.51, 1.69};
        for (int j = 0; j < pings.length; j++) {
            double pan = -0.8 + 1.6 * j / (pings.length - 1), angle = (pan + 1) * Math.PI / 4;
            ping(dryL, dryR, sendL, sendR, rate, pings[j], at[j], 0.055, Math.cos(angle), Math.sin(angle));
        }

        // The room: a 2.2 s tail on the glass, wider than the dry sound.
        float[] wetL = room(sendL, rate, new int[] {1557, 1617, 1491, 1422}, 2.2);
        float[] wetR = room(sendR, rate, new int[] {1580, 1640, 1514, 1445}, 2.2);
        float dryPeak = 1e-6f, wetPeak = 1e-6f;
        for (int i = 0; i < n; i++) {
            dryPeak = Math.max(dryPeak, Math.max(Math.abs(sendL[i]), Math.abs(sendR[i])));
            wetPeak = Math.max(wetPeak, Math.max(Math.abs(wetL[i]), Math.abs(wetR[i])));
        }
        float wet = 0.55f * dryPeak / wetPeak;   // the tail sits a little over half the bloom's peak
        float peak = 1e-6f;
        for (int i = 0; i < n; i++) {
            double fade = 1 - raised(((double) i / rate - 2.90) / (seconds - 2.90));
            dryL[i] = (float) ((dryL[i] + wet * wetL[i]) * fade);
            dryR[i] = (float) ((dryR[i] + wet * wetR[i]) * fade);
            peak = Math.max(peak, Math.max(Math.abs(dryL[i]), Math.abs(dryR[i])));
        }
        float gain = 0.708f / peak;   // −3 dBFS
        ByteBuffer out = header(n, rate, 2);
        for (int i = 0; i < n; i++) {
            out.putShort(pcm(dryL[i] * gain));
            out.putShort(pcm(dryR[i] * gain));
        }
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(out.array());
        }
        return f.getPath();
    }

    /** One glass bell voice into `dry` and the reverb's `send`: a 35 ms bloom, τ 1.1 s, 12% of a 2:1 FM bell. */
    private static void bell(float[] dry, float[] send, int rate, double hz, double start, double level) {
        int from = (int) Math.round(start * rate);
        double step = 2 * Math.PI * hz / rate;
        for (int i = from; i < dry.length; i++) {
            double t = (double) (i - from) / rate, env = raised(t / 0.035) * Math.exp(-t / 1.1);
            if (t > 0.1 && env < 1e-4) break;
            double ph = step * (i - from), index = 0.4 + 2.2 * Math.exp(-t / 0.35);
            double s = 0.88 * Math.sin(ph) + 0.12 * Math.sin(ph + index * Math.sin(2 * ph));
            float y = (float) (level * env * s);
            dry[i] += y;
            send[i] += y;
        }
    }

    /** A velvet sine ping (its octave at 15%), panned (gains l, r), into the dry mix and the reverb. */
    private static void ping(float[] dryL, float[] dryR, float[] sendL, float[] sendR, int rate, double hz, double start,
            double level, double l, double r) {
        int from = (int) Math.round(start * rate);
        double step = 2 * Math.PI * hz / rate;
        for (int i = from; i < dryL.length; i++) {
            double t = (double) (i - from) / rate, env = raised(t / 0.006) * Math.exp(-t / 0.30);
            if (t > 0.05 && env < 1e-4) break;
            double ph = step * (i - from), y = level * env * (Math.sin(ph) + 0.15 * Math.sin(2 * ph));
            dryL[i] += (float) (y * l);
            dryR[i] += (float) (y * r);
            sendL[i] += (float) (y * l * 1.3);
            sendR[i] += (float) (y * r * 1.3);
        }
    }

    /** A Schroeder room on `in`: four damped combs (`delays`, 60 dB down in `rt60` s), 18 ms pre-delay, two all-passes. */
    private static float[] room(float[] in, int rate, int[] delays, double rt60) {
        int n = in.length, pre = (int) (0.018 * rate);
        float[] out = new float[n];
        for (int d : delays) {
            float g = (float) Math.pow(10, -3.0 * d / (rt60 * rate)), damp = 0.25f, low = 0;
            float[] line = new float[d];
            int p = 0;
            for (int i = 0; i < n; i++) {
                float x = i >= pre ? in[i - pre] : 0, y = line[p];
                low = y * (1 - damp) + low * damp;
                line[p] = x + low * g;
                out[i] += y;
                if (++p == d) p = 0;
            }
        }
        for (int d : new int[] {556, 441}) {
            float[] line = new float[d];
            int p = 0;
            for (int i = 0; i < n; i++) {
                float b = line[p], x = out[i];
                line[p] = x + b * 0.5f;
                out[i] = b - x * 0.5f;
                if (++p == d) p = 0;
            }
        }
        return out;
    }

    /** 0 → 1 over x ∈ [0, 1] on half a cosine (no click at either end). */
    private static double raised(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return 0.5 - 0.5 * Math.cos(Math.PI * x);
    }

    private static short pcm(float v) {
        return (short) Math.round(Math.max(-1, Math.min(1, v)) * Short.MAX_VALUE);
    }

    private static ByteBuffer header(int n) throws IOException {
        return header(n, RATE, 1);
    }

    /** A 16-bit PCM WAV header for `n` frames of `channels` at `rate`, in a buffer sized for the samples after it. */
    private static ByteBuffer header(int n, int rate, int channels) throws IOException {
        int bytes = n * 2 * channels;
        ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + bytes).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * 2 * channels).putShort((short) (2 * channels)).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(bytes);
        return b;
    }

    // MARK: - The cues, synthesized (once, into the cache)

    /** Fills a mono buffer of a cue's length with its samples (any level: `cue` normalizes it). */
    private interface Synth {
        void fill(float[] m);
    }

    /** A cue's WAV in the cache (made on the first run): faded in over 0.15 ms and out over 1 ms, peak at −3 dBFS. */
    private static String cue(Context c, String name, float seconds, Synth synth) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        float[] m = new float[n];
        synth.fill(m);
        int fadeIn = RATE / 6667, fadeOut = RATE / 1000;   // 0.15 ms in (the transient stays crisp), 1 ms out
        float peak = 1e-6f;
        for (int i = 0; i < n; i++) {
            m[i] *= Math.min(1, Math.min((i + 1f) / fadeIn, (float) (n - i) / fadeOut));
            peak = Math.max(peak, Math.abs(m[i]));
        }
        float gain = 0.708f / peak;
        ByteBuffer b = header(n);
        for (int i = 0; i < n; i++) b.putShort(pcm(m[i] * gain));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }

    /** A struck mode: a sine at `hz` from `start` s, `amp`, decaying with time constant `tau` s (0.3 ms attack). */
    private static void mode(float[] m, double start, double hz, double amp, double tau) {
        int from = (int) Math.round(start * RATE);
        double w = 2 * Math.PI * hz / RATE;
        for (int i = Math.max(0, from); i < m.length; i++) {
            double t = (double) (i - from) / RATE, env = raised(t / 0.0003) * Math.exp(-t / tau);
            if (t > 0.01 && env < 1e-4) break;
            m[i] += (float) (amp * env * Math.sin(w * (i - from)));
        }
    }

    /** A noise burst from `start`, `amp`, decaying with `tau`, through a one-pole low-pass at `cut` Hz (0: none). */
    private static void grain(float[] m, double start, double amp, double tau, double cut) {
        int from = (int) Math.round(start * RATE);
        double a = cut > 0 ? Math.exp(-2 * Math.PI * cut / RATE) : 0, y = 0;
        int seed = 0x2545F491;
        for (int i = Math.max(0, from); i < m.length; i++) {
            double t = (double) (i - from) / RATE, env = Math.exp(-t / tau);
            if (env < 1e-4) break;
            seed = seed * 1664525 + 1013904223;   // a fixed noise: the same cue on every TV
            double x = (seed >>> 8) / (double) (1 << 24) * 2 - 1;
            y = cut > 0 ? (1 - a) * x + a * y : x;
            m[i] += (float) (amp * env * y);
        }
    }

    /** The focus tick: glass on wood, three modes over a sub-millisecond transient. */
    private static void tick(float[] m) {
        grain(m, 0, 0.30, 0.0005, 0);
        mode(m, 0, 1240, 1.0, 0.007);
        mode(m, 0, 2710, 0.42, 0.0045);
        mode(m, 0, 4380, 0.16, 0.0025);
    }

    /** Select: a soft press (196 Hz body) and, 6 ms later, the G6 glass ping with its fifth. */
    private static void select(float[] m) {
        grain(m, 0, 0.18, 0.0008, 3000);
        mode(m, 0, 196, 0.55, 0.022);
        mode(m, 0.006, 1568, 0.62, 0.055);
        mode(m, 0.006, 2352, 0.22, 0.032);
        mode(m, 0.006, 3136, 0.08, 0.018);
    }

    /** The dead end: a muted knock, low and short, with a dull thud of air under it. */
    private static void knock(float[] m) {
        grain(m, 0, 0.35, 0.006, 900);
        mode(m, 0, 220, 0.9, 0.016);
        mode(m, 0, 331, 0.4, 0.010);
    }

    /** Two glass blips 28 ms apart (a toggle: rising for on, falling for off). */
    private static void blips(float[] m, double first, double second) {
        mode(m, 0, first, 0.8, 0.018);
        mode(m, 0, first * 2, 0.12, 0.010);
        mode(m, 0.028, second, 0.8, 0.022);
        mode(m, 0.028, second * 2, 0.12, 0.012);
    }

    /** The hold's tension: 110 Hz and its partials rising ~12% in pitch and swelling to the threshold (0.45 s), under
     * a low ratcheting grain (18 Hz), then easing off. */
    private static void tension(float[] m) {
        double phase = 0, a = Math.exp(-2 * Math.PI * 380 / RATE), y = 0, y2 = 0;
        int seed = 0x1234567;
        for (int i = 0; i < m.length; i++) {
            double t = (double) i / RATE, rise = Math.min(1, t / 0.45);
            double env = raised(rise) * (1 - raised((t - 0.46) / 0.10)), hz = 110 * (1 + 0.12 * rise * rise);
            phase += 2 * Math.PI * hz / RATE;
            double tone = Math.sin(phase) + 0.35 * Math.sin(2 * phase) + 0.12 * Math.sin(3 * phase);
            seed = seed * 1664525 + 1013904223;
            y = (1 - a) * ((seed >>> 8) / (double) (1 << 24) * 2 - 1) + a * y;
            y2 = (1 - a) * y + a * y2;   // two poles: a low grain, felt more than heard
            double ratchet = 0.5 + 0.5 * Math.sin(2 * Math.PI * (14 + 8 * rise) * t);
            m[i] = (float) (env * (0.55 * tone + 2.4 * y2 * ratchet * ratchet));
        }
    }

    /** The hold commits: a lower, firmer select (a snap into place). */
    private static void commit(float[] m) {
        grain(m, 0, 0.25, 0.0008, 2500);
        mode(m, 0, 147, 0.7, 0.03);
        mode(m, 0.004, 880, 0.55, 0.04);
        mode(m, 0.004, 1320, 0.3, 0.03);
    }

    /** Air released through glass: noise under a low-pass sweeping from `from` to `to` Hz, peaking at `peak` s. */
    private static void air(float[] m, double from, double to, double peak) {
        int n = m.length;
        double y1 = 0, y2 = 0;
        int seed = 0x7F4A7C15;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE, u = (double) i / n;
            double cut = from * Math.pow(to / from, u), a = Math.exp(-2 * Math.PI * cut / RATE);
            seed = seed * 1664525 + 1013904223;
            double x = (seed >>> 8) / (double) (1 << 24) * 2 - 1;
            y1 = (1 - a) * x + a * y1;
            y2 = (1 - a) * y1 + a * y2;   // two poles: a soft, whispered slope
            double env = t < peak ? raised(t / peak) : Math.exp(-(t - peak) / ((n / (double) RATE - peak) * 0.35));
            m[i] = (float) (env * y2);
        }
        // A faint glass edge on the way in (a sheet arriving), none on the way out.
        if (to > from) mode(m, peak, 2093, 0.03, 0.06);
    }

    /** A wrong PIN: a dull low thud, falling a little. */
    private static void error(float[] m) {
        grain(m, 0, 0.3, 0.02, 600);
        mode(m, 0, 150, 1.0, 0.045);
        mode(m, 0.07, 120, 0.7, 0.05);
    }
}
