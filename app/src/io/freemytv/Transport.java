package io.freemytv;

/** Which media session Control Center's playback card follows. The states are android.media.session.PlaybackState's. */
final class Transport {
    private Transport() {}

    private static final int PAUSED = 2, PLAYING = 3, BUFFERING = 6, CONNECTING = 8;

    /** A stream that buffers or connects is still playing. */
    static boolean isPlaying(int state) {
        return state == PLAYING || state == BUFFERING || state == CONNECTING;
    }

    /** The most recent session that plays, else the most recent paused one, else −1 (an idle or failed session shows nothing). */
    static int pick(int[] states) {
        int paused = -1;
        for (int i = 0; i < states.length; i++) {
            if (isPlaying(states[i])) return i;
            if (states[i] == PAUSED && paused < 0) paused = i;
        }
        return paused;
    }
}
