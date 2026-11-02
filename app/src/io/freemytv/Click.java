package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

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
    private SoundPool pool;
    private int move, open, bump, chime, error;

    Click(Context c) {
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
        if (pool != null && Prefs.sounds()) pool.play(move, 0.14f, 0.14f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.sounds()) pool.play(open, 0.18f, 0.18f, 1, 0, 1f);
    }

    /** Focus can't go further that way. */
    void bump() {
        if (pool != null && Prefs.sounds()) pool.play(bump, 0.12f, 0.12f, 1, 0, 1f);
    }

    /** mahitTV's startup sound (Intro, from its first frame: it carries its own timing, stereo). */
    void chime() {
        if (pool != null && Prefs.sounds()) pool.play(chime, 0.36f, 0.36f, 2, 0, 1f);
    }

    /** A dull thud: a wrong PIN. */
    void error() {
        if (pool != null && Prefs.sounds()) pool.play(error, 0.22f, 0.22f, 1, 0, 1f);
    }

    static Click current() {
        return current;
    }

    /** The knock, from any view of the home screen. */
    static void boundary() {
        Click c = current;
        if (c != null) c.bump();
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
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
     * <li>The crystalline Cmaj9 bloom (C4 E4 G4 B4 D5) as the glyphs condense: rolled 45 ms apart from 0.48 s, each a
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
        File f = new File(c.getCacheDir(), "chime-mahittv-horizon.wav");
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

        // Layer 2: the Cmaj9 glass bloom, chorused across the channels, into the reverb.
        double[] notes = {261.63, 329.63, 392.00, 493.88, 587.33}, levels = {0.11, 0.10, 0.10, 0.085, 0.075};
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
