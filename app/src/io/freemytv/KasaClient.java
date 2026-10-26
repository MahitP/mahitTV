package io.freemytv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kasa plugs and bulbs on the LAN (WELLY-HOME-REMOTE-SPEC.md §1.3): the legacy protocol on TCP 9999, JSON behind a
 * 4-byte length and an XOR autokey that starts at 171. Newer firmware (KLAP, port 80) refuses 9999: the device then
 * says so instead of failing quietly. One background thread; callbacks on the main thread.
 */
final class KasaClient implements SmartHome.Bridge {
    private static final int PORT = 9999, TIMEOUT_MS = 1500, MAX_REPLY = 64 * 1024;
    // The reasons a device can't be reached, in English (Ui.tr keys).
    static final String NEEDS_KLAP = "Needs KLAP (newer Kasa firmware)", NO_ANSWER = "No answer";
    private static final String SYSINFO = "{\"system\":{\"get_sysinfo\":{}}}";

    private final List<SmartHome.Device> devices;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    KasaClient(String config) {
        devices = Collections.unmodifiableList(parse(config));
    }

    /** `kasa=` in config.properties: "parents.bulb=192.168.1.21:bulb,mine.lamp=192.168.1.22:plug,…". */
    static List<SmartHome.Device> parse(String config) {
        List<SmartHome.Device> out = new ArrayList<>();
        if (config == null) return out;
        for (String entry : config.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String fixture = entry.substring(0, eq).trim(), target = entry.substring(eq + 1).trim();
            int colon = target.lastIndexOf(':');
            String host = colon < 0 ? target : target.substring(0, colon).trim();
            String kind = colon < 0 ? "plug" : target.substring(colon + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (host.isEmpty() || fixture.isEmpty()) continue;
            out.add(new SmartHome.Device(fixture, host, kind.equals("bulb") ? "bulb" : "plug"));
        }
        return out;
    }

    @Override public List<SmartHome.Device> devices() {
        return devices;
    }

    @Override public SmartHome.Device device(String fixture) {
        for (SmartHome.Device d : devices) if (d.fixture.equals(fixture)) return d;
        return null;
    }

    @Override public void set(SmartHome.Device d, boolean on, SmartHome.Callback cb) {
        worker.execute(() -> {
            boolean ok;
            try {
                int state = on ? 1 : 0;
                String request = d.kind.equals("bulb")
                        ? "{\"smartlife.iot.smartbulb.lightingservice\":{\"transition_light_state\":{\"on_off\":" + state
                                + ",\"transition_period\":300}}}"
                        : "{\"system\":{\"set_relay_state\":{\"state\":" + state + "}}}";
                JSONObject reply = new JSONObject(send(d.host, request));
                JSONObject r = d.kind.equals("bulb")
                        ? reply.optJSONObject("smartlife.iot.smartbulb.lightingservice")
                        : reply.optJSONObject("system");
                JSONObject inner = r == null ? null
                        : r.optJSONObject(d.kind.equals("bulb") ? "transition_light_state" : "set_relay_state");
                ok = inner != null && inner.optInt("err_code", 0) == 0;
                if (ok) {
                    d.on = on;
                    d.known = true;
                }
                reached(d, ok ? "" : NO_ANSWER);
            } catch (Exception e) {
                ok = false;
                failed(d, e);
            }
            post(cb, d, ok);
        });
    }

    @Override public void refresh(SmartHome.Callback cb) {
        for (SmartHome.Device d : devices) {
            worker.execute(() -> {
                boolean ok;
                try {
                    JSONObject info = new JSONObject(send(d.host, SYSINFO)).getJSONObject("system").getJSONObject("get_sysinfo");
                    if (info.has("relay_state")) {
                        d.on = info.getInt("relay_state") == 1;
                    } else {
                        JSONObject light = info.getJSONObject("light_state");
                        d.on = light.getInt("on_off") == 1;
                    }
                    d.known = true;
                    ok = true;
                    reached(d, "");
                } catch (Exception e) {
                    ok = false;
                    failed(d, e);
                }
                post(cb, d, ok);
            });
        }
    }

    private void post(SmartHome.Callback cb, SmartHome.Device d, boolean ok) {
        if (cb != null) main.post(() -> cb.done(d, ok));
    }

    private static void reached(SmartHome.Device d, String problem) {
        d.reachable = problem.isEmpty();
        d.problem = problem;
    }

    private static void failed(SmartHome.Device d, Exception e) {
        // Refused on 9999 while the device is there: its firmware moved to KLAP (port 80).
        reached(d, e instanceof ConnectException ? NEEDS_KLAP : NO_ANSWER);
        if (!(e instanceof SocketTimeoutException)) AppLog.w("Kasa " + d.fixture + ": " + e);
    }

    /** One request, one reply (both JSON), 1.5 s at most for each step. */
    static String send(String host, String json) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(encrypt(json));
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int length = in.readInt();
            if (length < 0 || length > MAX_REPLY) throw new IOException("bad reply length " + length);
            byte[] body = new byte[length];
            in.readFully(body);
            return decrypt(body);
        }
    }

    /** The length (4 bytes, big-endian), then each byte XORed with the previous encrypted one (the first with 171). */
    static byte[] encrypt(String json) {
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + plain.length];
        int n = plain.length;
        out[0] = (byte) (n >>> 24);
        out[1] = (byte) (n >>> 16);
        out[2] = (byte) (n >>> 8);
        out[3] = (byte) n;
        int key = 171;
        for (int i = 0; i < n; i++) {
            int c = (plain[i] & 255) ^ key;
            key = c;
            out[4 + i] = (byte) c;
        }
        return out;
    }

    static String decrypt(byte[] body) {
        byte[] plain = new byte[body.length];
        int key = 171;
        for (int i = 0; i < body.length; i++) {
            int c = body[i] & 255;
            plain[i] = (byte) (c ^ key);
            key = c;
        }
        return new String(plain, StandardCharsets.UTF_8);
    }
}
