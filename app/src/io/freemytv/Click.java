package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Soft click for the home screen (moving focus, opening, and a duller knock when focus can't go further). The app
 * synthesizes the sounds itself (a short, low "tock"), writes them once as WAVs in the cache and plays them quietly
 * through SoundPool. They only play on the home screen, never inside apps. The views of the home screen play the knock
 * through `boundary()`, on the same frame as their dead-end bump (ANIMATIONS-SPEC.md §5.4).
 */
final class Click {
    private static final int RATE = 48_000;
    private static Click current;
    private volatile SoundPool pool;
    private int move, open, bump, chime, error;
    private final HandlerThread thread = new HandlerThread("click", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler handler;

    Click(Context c) {
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            move = pool.load(wav(c, "click-move.wav", 1450f, 0.009f, 0.030f), 1);
            open = pool.load(wav(c, "click-open.wav", 880f, 0.022f, 0.090f), 1);
            bump = pool.load(wav(c, "click-bump.wav", 310f, 0.014f, 0.060f), 1);
            chime = pool.load(chord(c), 1);
            error = pool.load(wav(c, "click-error.wav", 150f, 0.045f, 0.180f), 1);
            current = this;
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        play(move, 0.14f, 1);
    }

    void open() {
        play(open, 0.18f, 1);
    }

    /** Focus can't go further that way. */
    void bump() {
        play(bump, 0.12f, 1);
    }

    /** mahitTV's startup chord (Intro, from its first frame: the chord carries its own timing). */
    void chime() {
        play(chime, 0.32f, 2);
    }

    /** A dull thud: a wrong PIN. */
    void error() {
        play(error, 0.22f, 1);
    }

    static Click current() {
        return current;
    }

    /** The knock, from any view of the home screen. */
    static void boundary() {
        Click c = current;
        if (c != null) c.bump();
    }

    /** On its own thread: SoundPool's calls can wait on the audio server, and never on the UI thread. */
    private void play(int sound, float volume, int priority) {
        if (pool == null || !Prefs.sounds()) return;
        handler.post(() -> {
            SoundPool p = pool;
            if (p != null) p.play(sound, volume, volume, priority, 0, 1f);
        });
    }

    void release() {
        SoundPool p = pool;
        pool = null;
        if (p != null) handler.post(p::release);   // after the plays already queued, never during one
        thread.quitSafely();
        if (current == this) current = null;
    }

    /**
     * The startup's Cmaj9 glass resonance chord (TVOS-LAUNCHER-MERGE-SPEC.md §1.6), 2.6 s, timed to the intro: a warm
     * sub-swell (C2 + G2, sines) from 0.05 s with a 180 ms attack as the horizon ignites, then five glass bells (C4 G4
     * B4 D5 E5) strummed 18 ms apart from 0.62 s so they peak at about 0.75 s as the light sweeps the wordmark. Each bell
     * is two voices detuned ±1.5 cents (a slow chorus) with slightly inharmonic partials (1, 2, 3.01, 4.2), an 8 ms
     * attack and an exponential decay (τ 0.45 s: −34 dB at 1.8 s). Peak-normalised to −3 dBFS, 60 ms fade at the end.
     * Voices run on the sine recurrence y[n] = 2cos(w)·y[n−1] − y[n−2] (no sin() per sample).
     */
    private static String chord(Context c) throws IOException {
        new File(c.getCacheDir(), "chime-mahittv.wav").delete();   // the golden intro's chord
        File f = new File(c.getCacheDir(), "chime-mahittv-glass.wav");
        if (f.exists() && f.length() > 44) return f.getPath();
        float seconds = 2.6f;
        int n = Math.round(RATE * seconds);
        float[] mix = new float[n];
        for (double hz : new double[] {65.41, 98.00}) voice(mix, hz, 0.05, 0.180, 0.55, 0.30);
        double[] bells = {261.63, 392.00, 493.88, 587.33, 659.25};
        double[] partials = {1, 2, 3.01, 4.2}, weights = {1, 0.22, 0.07, 0.03};
        double[] detune = {0.999134, 1.000867};   // 2^(∓1.5/1200)
        for (int b = 0; b < bells.length; b++) {
            for (double d : detune) {
                for (int p = 0; p < partials.length; p++) {
                    voice(mix, bells[b] * d * partials[p], 0.62 + 0.018 * b, 0.008, 0.45, 0.10 * 0.5 * weights[p]);
                }
            }
        }
        float peak = 1e-6f;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        float gain = 0.708f / peak;   // −3 dBFS
        ByteBuffer out = header(n);
        for (int i = 0; i < n; i++) {
            double fade = Math.min(1, (seconds - (double) i / RATE) / 0.06);
            out.putShort((short) Math.round(Math.max(-1, Math.min(1, mix[i] * gain * fade)) * Short.MAX_VALUE));
        }
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(out.array());
        }
        return f.getPath();
    }

    /** Adds one sine voice into `mix`: from `start` s, a linear attack, then exponential decay τ, at `level`. */
    private static void voice(float[] mix, double hz, double start, double attack, double tau, double level) {
        if (hz >= RATE / 2.0) return;
        int from = (int) Math.round(start * RATE), rise = Math.max(1, (int) Math.round(attack * RATE));
        double w = 2 * Math.PI * hz / RATE, k = 2 * Math.cos(w), fall = Math.exp(-1 / (tau * RATE));
        double y1 = -Math.sin(w), y2 = -Math.sin(2 * w), env = 1;
        for (int i = from; i < mix.length; i++) {
            double y = k * y1 - y2;
            y2 = y1;
            y1 = y;
            int t = i - from;
            double e = t < rise ? (double) t / rise : (env *= fall);
            mix[i] += (float) (level * e * y);
        }
    }

    private static ByteBuffer header(int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        return b;
    }

    /** Sine wave plus one harmonic, 1.5 ms attack (no pop) and exponential decay. */
    private static String wav(Context c, String name, float freq, float decay, float seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = header(n);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1, t / 0.0015) * Math.exp(-t / decay);
            double s = 0.75 * Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 2.01 * t);
            b.putShort((short) Math.round(s * env * 0.7 * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
