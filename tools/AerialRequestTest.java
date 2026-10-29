package io.freemytv;

/** Delayed cache/video callbacks may only affect the current background request. */
public final class AerialRequestTest {
    public static void main(String[] args) {
        AerialRequest state = new AerialRequest();
        long live = state.show(Wallpaper.liveAerial(0).video);
        no(state.videoVisible(), "new live request keeps opaque Horizon until cache validation");
        yes(state.matches(live, Wallpaper.liveAerial(0).video), "current live callback accepted");
        yes(state.videoReady(live, Wallpaper.liveAerial(0).video), "validated live enables video-visible canvas state");
        yes(state.videoVisible(), "valid live exposes the video behind HomeView");
        state.hide();
        no(state.videoVisible(), "hide/failure restores opaque canvas state");
        no(state.matches(live, Wallpaper.liveAerial(0).video), "late completion after hide is discarded");
        long next = state.show(Wallpaper.liveAerial(1).video);
        no(state.matches(live, Wallpaper.liveAerial(0).video), "media precedence invalidates previous live request");
        yes(state.matches(next, Wallpaper.liveAerial(1).video), "new request remains current");
        state.hide();
        no(state.matches(next, Wallpaper.liveAerial(1).video), "pause/hidden request cannot autoplay");
        String still = Wallpaper.aerials()[0].preview;
        long stillToken = state.show(still);
        yes(state.matches(stillToken, still), "current still decode is accepted");
        state.hide();
        no(state.matches(stillToken, still), "still decode failure/precedence returns to Horizon");
        System.out.println("AerialRequestTest: passed");
    }
    private static void yes(boolean v, String m) { if (!v) throw new AssertionError(m); }
    private static void no(boolean v, String m) { yes(!v, m); }
}
