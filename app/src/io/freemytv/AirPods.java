package io.freemytv;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.Locale;

/** AirPods and Beats: which Bluetooth output is one (by its name), and whether the TV spatializes for it. */
final class AirPods {
    private AirPods() {}

    /** BLE Audio outputs (API 31): headset and speaker. */
    private static final int BLE_HEADSET = 26, BLE_SPEAKER = 27;

    static boolean matches(CharSequence name) {
        if (name == null) return false;
        String n = name.toString().toLowerCase(Locale.ROOT);
        return n.contains("airpods") || n.contains("beats");
    }

    static boolean bluetooth(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == BLE_HEADSET || t == BLE_SPEAKER;
    }

    /** The name of the AirPods or Beats among these outputs, or null. */
    static String find(AudioDeviceInfo[] devices) {
        if (devices == null) return null;
        for (AudioDeviceInfo d : devices) {
            if (d.isSink() && bluetooth(d) && matches(d.getProductName())) return d.getProductName().toString().trim();
        }
        return null;
    }

    static String connected(AudioManager am) {
        if (am == null) return null;
        try {
            return find(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True only where Android says it spatializes (Spatializer, Android 12L and newer, available and on). */
    static boolean spatial(AudioManager am) {
        if (am == null || Build.VERSION.SDK_INT < 32) return false;
        try {
            android.media.Spatializer s = am.getSpatializer();
            return s.isAvailable() && s.isEnabled();
        } catch (Throwable e) {
            return false;
        }
    }
}
