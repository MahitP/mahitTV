package io.freemytv;

import android.content.Context;

import java.util.List;

/**
 * The Home tab's lights (WELLY-HOME-REMOTE-SPEC.md §4.2): fixtures in house.json bound to devices by `kasa=` in
 * config.properties. A Bridge talks to one ecosystem: Kasa on the LAN now, Google Home / Matter later. Network work is
 * off the UI thread; callbacks arrive on the main thread.
 */
final class SmartHome {
    private SmartHome() {}

    /** One light: a fixture id from house.json ("mine.lamp"), the device's address and kind, and what we last knew. */
    static final class Device {
        final String fixture, host, kind;   // kind: "bulb" or "plug"
        volatile boolean on, known, reachable = true;
        volatile String problem = "";       // "" or why it can't be reached ("needs KLAP", "no answer")

        Device(String fixture, String host, String kind) {
            this.fixture = fixture;
            this.host = host;
            this.kind = kind;
        }
    }

    interface Callback {
        /** The device's state after the call (on the main thread); ok = the device answered. */
        void done(Device d, boolean ok);
    }

    interface Bridge {
        List<Device> devices();
        Device device(String fixture);       // null when the fixture has no device
        void set(Device d, boolean on, Callback cb);
        void refresh(Callback cb);           // reads every device's state
    }

    private static Bridge bridge;

    /** The bridge for this TV's config (one per process). */
    static synchronized Bridge get(Context c) {
        if (bridge == null) bridge = make(c.getApplicationContext());
        return bridge;
    }

    /** Kasa on the LAN (KasaClient, legacy port 9999), for the fixtures `kasa=` names; none without it. */
    private static Bridge make(Context c) {
        return new KasaClient(Config.KASA);
    }
}
