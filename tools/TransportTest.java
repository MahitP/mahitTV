package io.freemytv;

/** Control Center's playback card: the session it follows, from PlaybackState's states (most recent session first). */
public final class TransportTest {
    private static final int NONE = 0, STOPPED = 1, PAUSED = 2, PLAYING = 3, BUFFERING = 6, ERROR = 7, CONNECTING = 8;
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(Transport.pick(new int[] {}) == -1, "no sessions");
        check(Transport.pick(new int[] {STOPPED, ERROR, NONE}) == -1, "the TV's idle Netflix and Bluetooth sessions show nothing");
        check(Transport.pick(new int[] {PLAYING}) == 0, "playing");
        check(Transport.pick(new int[] {PAUSED}) == 0, "paused stays reachable, to resume it");
        check(Transport.pick(new int[] {PAUSED, PLAYING}) == 1, "playing beats a more recent pause");
        check(Transport.pick(new int[] {STOPPED, PAUSED, PLAYING, PLAYING}) == 2, "the most recent that plays");
        check(Transport.pick(new int[] {PAUSED, STOPPED, PAUSED}) == 0, "the most recent pause");
        check(Transport.pick(new int[] {BUFFERING}) == 0, "a stream that buffers is playing");
        check(Transport.pick(new int[] {PAUSED, CONNECTING}) == 1, "a stream that connects is playing");
        check(Transport.isPlaying(PLAYING) && Transport.isPlaying(BUFFERING) && Transport.isPlaying(CONNECTING), "what counts as playing");
        check(!Transport.isPlaying(PAUSED) && !Transport.isPlaying(STOPPED), "what doesn't");
        System.out.println("TransportTest: " + checks + " checks passed");
    }
}
