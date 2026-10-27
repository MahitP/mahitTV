package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Home tab (WELLY-HOME-REMOTE-SPEC.md §4): the apartment as a clay diorama, from app/assets/house.json (written by
 * tools/make_house.py; web/rooms.js draws the same). Its four lights take the focus and OK turns them on or off
 * (SmartHome). Lights off, the house is cool; a lit light warms what's near it, pools on the floor, and a lamp casts a
 * cone. The camera swings to the focused light's room on a spring (outside walls lower themselves when they face it)
 * and the other rooms fade back. Each light has a dot on a stem; the focused one carries a pill with its name and state.
 * Everything is projected again each frame into buffers made once, so drawing allocates nothing.
 * Motion follows ANIMATIONS-SPEC.md §3: the springs step in this view's own frame callback (not in onDraw), which stops
 * when everything is at rest; while the house shows, the fans turn, the screens glint, a lit dot breathes and the camera
 * drifts after a while without keys (not with animations off, nor on Android 8–11, whose GPUs redraw it slowly).
 */
final class HouseView extends View implements Choreographer.FrameCallback {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Walls are boxes this thick, cut in pieces of at most 1 m so each sorts by its own depth. */
    private static final float WALL = 0.12f, TALL = 2.6f;
    private static final float SHADE_H = 0.45f, SHADE_RB = 0.24f, SHADE_RT = 0.15f, BASE_R = 0.18f;
    private static final float TUBE_W = 0.11f, TUBE_LIT = 0.08f, TUBE_CORE = 0.035f, TUBE_CAP = 0.09f;
    private static final int BULB = 0, LAMP = 1, TUBE = 2;
    private static final int STAND = 0, ROUND = 1, PIECE = 2, LIGHT = 3, DISC_X = 4, DISC_Y = 5, FAN = 6;
    // The warmth spreads from a light this fast (m/s) out to WAVE_MAX m and pulls back in WAVE_OUT s; the filament's
    // flash (an IGNITE impulse, peaking ≈ 0.25); a fan turns once in FAN_PERIOD s; the screens glint every GLINT_PERIOD
    // s; the idle drift starts after IDLE ns. Unfocused rooms keep DIM of their colour over the dark BG, 30% less colour.
    private static final float WAVE_SPEED = 8.5f, WAVE_MAX = 7, WAVE_OUT = 0.24f, FLASH = 14.2f;
    private static final float FAN_PERIOD = 4.5f, GLINT_PERIOD = 6, DIM = 0.45f, DESAT = 0.3f;
    private static final long IDLE = 6_000_000_000L;
    private static final float[] BG = {24, 22, 28};
    private static final int DISC_POINTS = 20;
    // The pill and the dots, in 1920×1080 units: the tvOS safe area, under the tab bar.
    private static final float SAFE_L = 90, SAFE_R = 1830, SAFE_T = 128, SAFE_B = 1020, DOT_R = 8, FADE_H = 200;
    private static final int AMBER = 0xFFFFC07A, PILL = 0xCC1C1C1E, OCCLUSION = 0xFF3C2C1E, SHADOW = 0xFF28201A;

    /** house.json as read (metres; x right, y toward the viewer, z up). */
    private static final class Model {
        float width, depth, yaw, pitch;
        final float[] box = new float[4];
        int[] cool, warm;
        int glow, wall, wallTop, wood, linen, charcoal, white, baseboard, silver;
        // Rooms.
        String[] roomName;
        float[][] roomRect, roomCam;      // roomCam: yaw, pitch, zoom, cx, cy (null: the overview)
        int[] roomFloor;
        // Walls: their height, and for outside walls the outward normal and the low height.
        float[] wallH, wallLow, outX, outY;
        boolean[] hasOut;
        // Things that stand (wall pieces, furniture, lights), and flat things on the floor (rugs, mats).
        float[][] box6;
        int[] type, color, rooms, wallOf, fixtureOf, faceSign;   // faceSign: a disc's side, +1 or -1
        float[] alpha, phase;            // phase: a screen's glint offset
        boolean[] followsWall, screen;
        int[][] under;
        int[] byZ;
        float[][] flat;
        int[] flatColor, flatRooms;
        int[] shadowOf;                   // items with a contact shadow
        float[][] strip;                  // occlusion bands: x0, y0, x1, y1 (inner), x0, y0, x1, y1 (outer)
        int[] stripRooms;
        // Lights.
        String[] fixtureId, fixtureName;
        int[] fixtureRoom, kind;
        float[][] at, glowAt;
        float[] shadeBottom, length, mid, top;
        boolean[] alongX;
        // Fans: centre (x, y, z), the blades' radius, whether they face ±y (else ±x), their room.
        float[][] fanC;
        float[] fanR;
        boolean[] fanAlongY;
        int[] fanRoom;
    }

    private final SmartHome.Bridge bridge;
    private Model m;
    private boolean[] on = new boolean[0];
    private Spring[] lit = new Spring[0], grow = new Spring[0], roomGlow = new Spring[0], emph = new Spring[0];
    private Spring[] flash = new Spring[0], nudgeX = new Spring[0], nudgeY = new Spring[0], cut = new Spring[0];
    private float[] wave = new float[0], ripple = new float[0];
    private final Spring camYaw = new Spring(Spring.CAMERA_ORBIT, 0).deg(), camPitch = new Spring(Spring.CAMERA_ORBIT, 60).deg();
    private final Spring camZoom = new Spring(Spring.CAMERA_ORBIT, 1), camX = new Spring(Spring.CAMERA_ORBIT, 0);
    private final Spring camY = new Spring(Spring.CAMERA_ORBIT, 0);
    private final Spring pillX = new Spring(Spring.LIQUID_PILL, 960).px(), pillY = new Spring(Spring.LIQUID_PILL, 540).px();
    private final Spring shown = new Spring(0.2f, 1f, 0);   // the pill, while the house has the focus
    private final Spring drift = new Spring(Spring.ROOM_FADE, 0);   // the idle drift's strength, eased in and out
    private int index, pillIndex = -1;
    private boolean focused, pillPlaced, running, bumped;
    private String pillName = "", pillState = "", pillSub = "", oldName = "", oldState = "", oldSub = "";
    private float pillNameW, pillStateW, pillSubW, oldNameW, oldStateW, oldSubW, pillAge = 1;
    private long lastNanos, lastKey;
    private float clock;   // seconds, from the frame times

    // This size's numbers and buffers.
    private int w, h;
    private float k = 1, s0 = 1, overX, overY, ax, ay;
    private float cosT = 1, sinT, sinP = 1, cosP, scale = 1, ox, oy;
    private float[] wallNow = new float[0], wallTall = new float[0], depth = new float[0], navX = new float[0],
            navY = new float[0], dotX = new float[0], dotY = new float[0], dotR = new float[0];
    private int[] order = new int[0], slab = new int[0];
    private final float[] pt = new float[2], q = new float[8];
    private final Path tmp = new Path(), clip = new Path();
    private final RectF oval = new RectF(), pillRect = new RectF();
    private final Matrix matrix = new Matrix();
    private Shader houseShadow, floorPool, bloomShader, coneShader, dotHalo, topFade;
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), fade = new Paint();
    private Paint nameP, stateP, subP;
    private float nameCap, subCap;

    HouseView(Context c) {
        super(c);
        bridge = SmartHome.get(c);
        face.setStyle(Paint.Style.FILL_AND_STROKE);   // the thin stroke closes the seams between faces
        face.setStrokeJoin(Paint.Join.ROUND);
        edge.setStyle(Paint.Style.STROKE);
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        Context app = c.getApplicationContext();
        LOADER.execute(() -> {
            try {
                Model model = load(app);
                post(() -> loaded(model));
            } catch (Exception e) {
                AppLog.w("house.json: " + e);
            }
        });
    }

    private void loaded(Model model) {
        m = model;
        int n = model.fixtureId.length, rooms = model.roomName.length;
        on = new boolean[n];
        lit = new Spring[n];
        grow = new Spring[n];
        flash = new Spring[n];
        nudgeX = new Spring[n];
        nudgeY = new Spring[n];
        wave = new float[n];
        ripple = new float[n];
        dotX = new float[n];
        dotY = new float[n];
        dotR = new float[n];
        navX = new float[n];
        navY = new float[n];
        for (int i = 0; i < n; i++) {
            SmartHome.Device d = bridge.device(model.fixtureId[i]);
            on[i] = d != null && d.known && d.on;
            lit[i] = new Spring(Spring.ROOM_FADE, on[i] ? 1 : 0);
            grow[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            flash[i] = new Spring(Spring.IGNITE, 0);
            nudgeX[i] = new Spring(Spring.BOUNDARY_BUMP, 0).px();
            nudgeY[i] = new Spring(Spring.BOUNDARY_BUMP, 0).px();
            wave[i] = on[i] ? WAVE_MAX : 0;
            ripple[i] = -1;
        }
        roomGlow = new Spring[rooms];
        emph = new Spring[rooms];
        slab = new int[rooms];
        for (int r = 0; r < rooms; r++) {
            roomGlow[r] = new Spring(Spring.ROOM_FADE, 0);
            emph[r] = new Spring(Spring.ROOM_FADE, 1);
            slab[r] = r;
        }
        wallNow = new float[model.wallH.length];
        wallTall = new float[model.wallH.length];
        cut = new Spring[model.wallH.length];
        for (int i = 0; i < cut.length; i++) {
            cut[i] = new Spring(Spring.WALL_CUT, 1);
            wallNow[i] = model.wallH[i];
            wallTall[i] = 1;
        }
        int items = model.box6.length;
        depth = new float[items];
        order = new int[items];
        for (int i = 0; i < items; i++) order[i] = i;
        build();
        cameraTarget(true);
        updatePill();
        refresh();
    }

    // MARK: - What HomeView calls

    /** Whether the house has the focus (the tab bar has it otherwise): the camera frames the focused light's room. */
    void setFocused(boolean f) {
        focused = f;
        lastKey = System.nanoTime();
        cameraTarget(false);
        updatePill();
        kick();
    }

    /** The D-pad: the nearest light that way in the overview (stable while the camera moves). False: none that way. */
    boolean move(int dx, int dy) {
        if (m == null || navX.length == 0) return false;
        lastKey = System.nanoTime();
        float x0 = navX[index], y0 = navY[index];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int j = 0; j < navX.length; j++) {
            if (j == index) continue;
            float ddx = navX[j] - x0, ddy = navY[j] - y0;
            float along = dx != 0 ? ddx * dx : ddy * dy, across = Math.abs(dx != 0 ? ddy : ddx);
            if (along <= 4 * k) continue;
            float score = along + 2 * across;
            if (score < bestScore) {
                bestScore = score;
                best = j;
            }
        }
        bumped = best < 0;
        if (bumped) {
            if (dy < 0) return false;   // ↑ from the top light: the tab bar
            // A dead end: the dot gets a push the way the arrow pointed and springs back (BOUNDARY_BUMP), with the knock.
            if (dx != 0) nudgeX[index].impulse(dx * Spring.BUMP_VELOCITY);
            if (dy != 0) nudgeY[index].impulse(dy * Spring.BUMP_VELOCITY);
            Click.boundary();
            kick();
            return true;
        }
        index = best;
        cameraTarget(false);
        updatePill();
        kick();
        return true;
    }

    /** Whether the last move() hit a dead end (it bumped and played the knock: no move click). */
    boolean bumped() {
        return bumped;
    }

    /** OK: the focused light on or off, at once; back as it was, with the reason, if the device doesn't answer. */
    void toggle() {
        if (m == null) return;
        lastKey = System.nanoTime();
        int f = index;
        SmartHome.Device d = bridge.device(m.fixtureId[f]);
        boolean want = !on[f];
        ripple[f] = 0;
        if (d == null) {
            if (Config.PREVIEW) setOn(f, want);   // a preview build shows the lights without devices
            updatePill();
            kick();
            return;
        }
        setOn(f, want);
        updatePill();
        bridge.set(d, want, (device, ok) -> {
            if (!ok) setOn(f, !want);
            updatePill();
        });
    }

    /** Reads the lights' states (when the Home tab opens). */
    void refresh() {
        if (m == null) return;
        bridge.refresh((d, ok) -> {
            if (m == null) return;
            for (int i = 0; i < m.fixtureId.length; i++) {
                if (m.fixtureId[i].equals(d.fixture) && ok) setOn(i, d.on);
            }
            updatePill();
        });
    }

    /** On: the filament flashes (IGNITE) and the warmth starts spreading from the light (off, it pulls back in). */
    private void setOn(int f, boolean value) {
        if (value && !on[f] && !Spring.instant) {
            flash[f].snap(0);
            flash[f].impulse(FLASH);
            wave[f] = 0;
        }
        on[f] = value;
        lit[f].target = value ? 1 : 0;
        kick();
    }

    /** Something changed: run the frame callback (if it isn't running) and draw. */
    private void kick() {
        invalidate();
        if (running || m == null) return;
        running = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE && m != null && isShown()) {
            // The tab shows: the camera settles into place from a little higher and further (pitch +4°, zoom 0.94).
            lastKey = System.nanoTime();
            cameraTarget(true);
            if (!Spring.instant) {
                camPitch.value = camPitch.target + 4;
                camZoom.value = camZoom.target * 0.94f;
            }
            kick();
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
    }

    /** The camera on the focused light's room (the overview otherwise), the other rooms faded back. */
    private void cameraTarget(boolean snap) {
        if (m == null) return;
        int room = focused ? m.fixtureRoom[index] : -1;
        float[] c = room >= 0 ? m.roomCam[room] : null;
        float yaw = c != null ? c[0] : m.yaw, pitch = c != null ? c[1] : m.pitch, zoom = c != null ? c[2] : 1;
        float cx = c != null ? c[3] : overX, cy = c != null ? c[4] : overY;
        // The shortest way round, so the camera never spins the long way.
        float d = ((yaw - camYaw.target) % 360 + 540) % 360 - 180;
        camYaw.target += d;
        camPitch.target = pitch;
        camZoom.target = zoom;
        camX.target = cx;
        camY.target = cy;
        for (int r = 0; r < emph.length; r++) emph[r].target = !focused || room < 0 || r == room ? 1 : 0;
        if (snap) {
            camYaw.snap(camYaw.target);
            camPitch.snap(pitch);
            camZoom.snap(zoom);
            camX.snap(cx);
            camY.snap(cy);
        }
    }

    /** "My Room · Lamp · On"; a second line with the reason it can't be reached, or how to connect it. */
    private void updatePill() {
        if (m == null) return;
        int f = index;
        SmartHome.Device d = bridge.device(m.fixtureId[f]);
        if (pillIndex >= 0 && pillIndex != f && shown.value > 0.01f) {
            // Another light: the old text fades out while the new one fades in (both ride the pill's spring).
            oldName = pillName;
            oldState = pillState;
            oldSub = pillSub;
            oldNameW = pillNameW;
            oldStateW = pillStateW;
            oldSubW = pillSubW;
            pillAge = Spring.instant ? 1 : 0;
        }
        pillIndex = f;
        pillName = name(m.roomName[m.fixtureRoom[f]]) + "  ·  " + name(m.fixtureName[f]);
        pillState = "  ·  " + (on[f] ? Ui.tr("On") : Ui.tr("Off"));
        if (d == null && !Config.PREVIEW) pillSub = Ui.tr("Not connected: add its IP to kasa= in config.properties");
        else if (d != null && !d.reachable && !d.problem.isEmpty()) pillSub = problem(d.problem);
        else pillSub = "";
        if (nameP != null) {
            pillNameW = nameP.measureText(pillName);
            pillStateW = stateP.measureText(pillState);
            pillSubW = pillSub.isEmpty() ? 0 : subP.measureText(pillSub);
        }
        shown.target = focused ? 1 : 0;
        kick();
    }

    /** house.json's names, translated (spelled out so tools/translate.py finds them). */
    private static String name(String english) {
        switch (english) {
            case "Parents' Room": return Ui.tr("Parents' Room");
            case "My Room": return Ui.tr("My Room");
            case "Living Room": return Ui.tr("Living Room");
            case "Lightbulb": return Ui.tr("Lightbulb");
            case "Lamp": return Ui.tr("Lamp");
            case "Tube Light": return Ui.tr("Tube Light");
            default: return Ui.tr(english);
        }
    }

    /** Why a device can't be reached (KasaClient's reasons), translated. */
    private static String problem(String english) {
        switch (english) {
            case KasaClient.NEEDS_KLAP: return Ui.tr("Needs KLAP (newer Kasa firmware)");
            case KasaClient.NO_ANSWER: return Ui.tr("No answer");
            default: return Ui.tr(english);
        }
    }

    // MARK: - The model

    private static Model load(Context c) throws Exception {
        String text;
        try (InputStream in = c.getAssets().open("house.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONObject j = new JSONObject(text);
        Model m = new Model();
        JSONObject cam = j.getJSONObject("camera");
        m.yaw = (float) cam.getDouble("yaw");
        m.pitch = (float) cam.getDouble("pitch");
        JSONArray box = cam.getJSONArray("box");
        for (int i = 0; i < 4; i++) m.box[i] = (float) box.getDouble(i);
        JSONArray size = j.getJSONArray("size");
        m.width = (float) size.getDouble(0);
        m.depth = (float) size.getDouble(1);

        JSONObject palette = j.getJSONObject("palette"), cool = palette.getJSONObject("cool"), warm = palette.getJSONObject("warm");
        Map<String, Integer> colors = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = cool.keys(); it.hasNext(); ) keys.add(it.next());
        m.cool = new int[keys.size()];
        m.warm = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            colors.put(key, i);
            m.cool[i] = Color.parseColor(cool.getString(key));
            m.warm[i] = Color.parseColor(warm.optString(key, cool.getString(key)));
        }
        m.glow = Color.parseColor(palette.getString("glow"));
        m.wall = color(colors, "wall");
        m.wallTop = colors.containsKey("wall-top") ? colors.get("wall-top") : m.wall;
        m.wood = color(colors, "wood");
        m.linen = color(colors, "linen");
        m.charcoal = color(colors, "charcoal");
        m.white = color(colors, "white");
        m.silver = color(colors, "silver");
        m.baseboard = colors.containsKey("baseboard") ? colors.get("baseboard") : m.wall;

        JSONArray rooms = j.getJSONArray("rooms");
        int nr = rooms.length();
        Map<String, Integer> roomIndex = new HashMap<>();
        m.roomName = new String[nr];
        m.roomRect = new float[nr][];
        m.roomCam = new float[nr][];
        m.roomFloor = new int[nr];
        for (int i = 0; i < nr; i++) {
            JSONObject r = rooms.getJSONObject(i);
            roomIndex.put(r.getString("id"), i);
            m.roomName[i] = r.getString("name");
            m.roomRect[i] = floats(r.getJSONArray("rect"));
            m.roomFloor[i] = color(colors, "floor-" + r.optString("floor", "wood"));
            JSONObject rc = r.optJSONObject("camera");
            if (rc != null) {
                JSONArray center = rc.getJSONArray("center");
                m.roomCam[i] = new float[] {(float) rc.getDouble("yaw"), (float) rc.getDouble("pitch"),
                        (float) rc.getDouble("zoom"), (float) center.getDouble(0), (float) center.getDouble(1)};
            }
        }

        JSONArray walls = j.getJSONArray("walls");
        int nw = walls.length();
        Map<String, Integer> wallIndex = new HashMap<>();
        m.wallH = new float[nw];
        m.wallLow = new float[nw];
        m.outX = new float[nw];
        m.outY = new float[nw];
        m.hasOut = new boolean[nw];
        List<float[]> boxes = new ArrayList<>();
        List<int[]> info = new ArrayList<>();          // type, color, rooms, wall, fixture, face sign
        List<Float> alphas = new ArrayList<>();
        List<Boolean> follows = new ArrayList<>(), screens = new ArrayList<>();
        List<float[]> strips = new ArrayList<>();
        List<Integer> stripRooms = new ArrayList<>();
        for (int i = 0; i < nw; i++) {
            JSONObject wall = walls.getJSONObject(i);
            if (wall.has("id")) wallIndex.put(wall.getString("id"), i);
            JSONArray from = wall.getJSONArray("from"), to = wall.getJSONArray("to");
            float x0 = (float) from.getDouble(0), y0 = (float) from.getDouble(1);
            float x1 = (float) to.getDouble(0), y1 = (float) to.getDouble(1);
            float wh = (float) wall.getDouble("h");
            m.wallH[i] = wh;
            m.wallLow[i] = (float) wall.optDouble("low", wh);
            JSONArray out = wall.optJSONArray("out");
            if (out != null) {
                m.hasOut[i] = true;
                m.outX[i] = (float) out.getDouble(0);
                m.outY[i] = (float) out.getDouble(1);
            }
            // In 1 m pieces, each overlapping the next by 2 cm so no seam shows.
            boolean along = y0 == y1;
            float len = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            int n = Math.max(1, (int) Math.ceil(len - 1e-4));
            for (int p = 0; p < n; p++) {
                float e = p < n - 1 ? 0.02f : 0;
                float ax = x0 + (x1 - x0) * p / n, ay = y0 + (y1 - y0) * p / n;
                float bx = x0 + (x1 - x0) * (p + 1) / n + (x1 > x0 ? e : 0), by = y0 + (y1 - y0) * (p + 1) / n + (y1 > y0 ? e : 0);
                float[] b = along ? new float[] {Math.min(ax, bx), ay - WALL / 2, 0, Math.abs(bx - ax), WALL, wh}
                        : new float[] {ax - WALL / 2, Math.min(ay, by), 0, WALL, Math.abs(by - ay), wh};
                boxes.add(b);
                info.add(new int[] {PIECE, m.wall, roomsAt(m, b[0] + b[3] / 2, b[1] + b[4] / 2), i, -1, 0});
                alphas.add(1f);
                follows.add(false);
                screens.add(false);
                // Two darker bands on the floor where the wall meets it, on each side that's indoors.
                for (int side = -1; side <= 1; side += 2) {
                    float sx = along ? 0 : side, sy = along ? side : 0;
                    int mask = roomsAt(m, b[0] + b[3] / 2 + sx * 0.2f, b[1] + b[4] / 2 + sy * 0.2f);
                    if (mask == 0) continue;
                    float[] st = new float[8];
                    for (int band = 0; band < 2; band++) {
                        float wd = band == 0 ? 0.12f : 0.32f, bx0, by0, bx1, by1;
                        if (sy != 0) {
                            bx0 = b[0];
                            bx1 = b[0] + b[3];
                            by0 = sy > 0 ? b[1] + b[4] : b[1] - wd;
                            by1 = sy > 0 ? b[1] + b[4] + wd : b[1];
                        } else {
                            by0 = b[1];
                            by1 = b[1] + b[4];
                            bx0 = sx > 0 ? b[0] + b[3] : b[0] - wd;
                            bx1 = sx > 0 ? b[0] + b[3] + wd : b[0];
                        }
                        st[band * 4] = bx0;
                        st[band * 4 + 1] = by0;
                        st[band * 4 + 2] = bx1;
                        st[band * 4 + 3] = by1;
                    }
                    strips.add(st);
                    stripRooms.add(mask);
                }
            }
        }
        m.strip = strips.toArray(new float[0][]);
        m.stripRooms = new int[stripRooms.size()];
        for (int i = 0; i < m.stripRooms.length; i++) m.stripRooms[i] = stripRooms.get(i);

        JSONArray furniture = j.getJSONArray("furniture");
        List<float[]> flats = new ArrayList<>();
        List<int[]> flatInfo = new ArrayList<>();
        List<Integer> shadows = new ArrayList<>();
        for (int i = 0; i < furniture.length(); i++) {
            JSONObject f = furniture.getJSONObject(i);
            float[] b = floats(f.getJSONArray("box"));
            Integer room = roomIndex.get(f.optString("room"));
            int mask = room == null ? 0 : 1 << room;
            int col = color(colors, f.optString("color", "wood"));
            if (b[5] <= 0.02f && b[2] <= 0.03f) {
                flats.add(b);
                flatInfo.add(new int[] {col, mask});
                continue;
            }
            String kind = f.optString("kind");
            Integer wall = f.has("wall") ? wallIndex.get(f.getString("wall")) : null;
            if (f.optBoolean("shadow", true) && b[2] < 0.3f) shadows.add(boxes.size());
            boxes.add(b);
            String shape = f.optString("shape"), side = f.optString("face", "+y");
            int type = "round".equals(shape) ? ROUND : "disc".equals(shape) ? (side.endsWith("x") ? DISC_X : DISC_Y) : STAND;
            info.add(new int[] {type, col, mask, wall == null ? -1 : wall, -1, side.startsWith("-") ? -1 : 1});
            alphas.add((float) f.optDouble("alpha", 1));
            follows.add("door".equals(kind) || "door-glass".equals(kind) || "door-frame".equals(kind));
            screens.add("screen".equals(f.optString("color")) && Math.max(b[3], b[4]) > 0.3f);
        }
        // Flat things in the order they lie (a rug's border, then its pattern).
        int nf = flats.size();
        Integer[] fo = new Integer[nf];
        for (int i = 0; i < nf; i++) fo[i] = i;
        java.util.Arrays.sort(fo, (p, q) -> Float.compare(flats.get(p)[2], flats.get(q)[2]));
        m.flat = new float[nf][];
        m.flatColor = new int[nf];
        m.flatRooms = new int[nf];
        for (int i = 0; i < nf; i++) {
            m.flat[i] = flats.get(fo[i]);
            m.flatColor[i] = flatInfo.get(fo[i])[0];
            m.flatRooms[i] = flatInfo.get(fo[i])[1];
        }
        m.shadowOf = new int[shadows.size()];
        for (int i = 0; i < m.shadowOf.length; i++) m.shadowOf[i] = shadows.get(i);

        JSONArray fixtures = j.getJSONArray("fixtures");
        int n = fixtures.length();
        m.fixtureId = new String[n];
        m.fixtureName = new String[n];
        m.fixtureRoom = new int[n];
        m.kind = new int[n];
        m.at = new float[n][];
        m.glowAt = new float[n][];
        m.shadeBottom = new float[n];
        m.length = new float[n];
        m.mid = new float[n];
        m.top = new float[n];
        m.alongX = new boolean[n];
        for (int i = 0; i < n; i++) {
            JSONObject f = fixtures.getJSONObject(i);
            m.fixtureId[i] = f.getString("id");
            m.fixtureName[i] = f.optString("name", "Lightbulb");
            Integer room = roomIndex.get(f.optString("room"));
            m.fixtureRoom[i] = room == null ? 0 : room;
            String kind = f.optString("kind", "bulb");
            m.kind[i] = "lamp".equals(kind) ? LAMP : "tube".equals(kind) ? TUBE : BULB;
            m.shadeBottom[i] = (float) f.optDouble("height", 1.3);
            m.at[i] = floats(f.getJSONArray("at"));
            m.glowAt[i] = f.has("glow") ? floats(f.getJSONArray("glow")) : m.at[i];
            m.alongX[i] = "x".equals(f.optString("along", "x"));
            m.length[i] = (float) f.optDouble("length", 1.2);
            m.mid[i] = m.kind[i] == LAMP ? m.shadeBottom[i] + 0.22f : m.at[i][2];
            m.top[i] = m.kind[i] == LAMP ? m.shadeBottom[i] + SHADE_H : m.at[i][2] + 0.06f;
            boxes.add(new float[] {m.at[i][0], m.at[i][1], 0, 0, 0, 0});
            info.add(new int[] {LIGHT, 0, 1 << m.fixtureRoom[i], -1, i, 0});
            alphas.add(1f);
            follows.add(false);
            screens.add(false);
        }
        JSONArray fans = j.optJSONArray("fans");
        int nfan = fans == null ? 0 : fans.length();
        m.fanC = new float[nfan][];
        m.fanR = new float[nfan];
        m.fanAlongY = new boolean[nfan];
        m.fanRoom = new int[nfan];
        for (int i = 0; i < nfan; i++) {
            JSONObject f = fans.getJSONObject(i);
            m.fanC[i] = floats(f.getJSONArray("center"));
            m.fanR[i] = (float) f.optDouble("r", 0.17);
            m.fanAlongY[i] = f.optString("face", "+y").endsWith("y");
            Integer room = roomIndex.get(f.optString("room"));
            m.fanRoom[i] = room == null ? 0 : room;
            boxes.add(new float[] {m.fanC[i][0], m.fanC[i][1], 0, 0, 0, 0});
            info.add(new int[] {FAN, 0, 1 << m.fanRoom[i], -1, i, 0});
            alphas.add(1f);
            follows.add(false);
            screens.add(false);
        }

        int items = boxes.size();
        m.box6 = boxes.toArray(new float[0][]);
        m.type = new int[items];
        m.color = new int[items];
        m.rooms = new int[items];
        m.wallOf = new int[items];
        m.fixtureOf = new int[items];
        m.faceSign = new int[items];
        m.alpha = new float[items];
        m.phase = new float[items];
        m.followsWall = new boolean[items];
        m.screen = new boolean[items];
        for (int i = 0; i < items; i++) {
            int[] f = info.get(i);
            m.type[i] = f[0];
            m.color[i] = f[1];
            m.rooms[i] = f[2];
            m.wallOf[i] = f[3];
            m.fixtureOf[i] = f[4];
            m.faceSign[i] = f[5];
            m.alpha[i] = alphas.get(i);
            m.followsWall[i] = follows.get(i);
            m.screen[i] = screens.get(i);
            m.phase[i] = (m.box6[i][0] * 0.37f + m.box6[i][1] * 0.11f) % 1;
        }
        // What rests on what (a duvet on a bed, a monitor on its stand): drawn after it, at any camera.
        m.under = new int[items][];
        for (int i = 0; i < items; i++) {
            float[] t = m.box6[i];
            if (m.type[i] == LIGHT || m.type[i] == FAN || t[2] <= 0) continue;
            List<Integer> below = new ArrayList<>();
            for (int u = 0; u < items; u++) {
                float[] b = m.box6[u];
                if (u == i || m.type[u] == LIGHT || m.type[u] == FAN || Math.abs(b[2] + b[5] - t[2]) > 0.03f) continue;
                if (t[0] < b[0] + b[3] && b[0] < t[0] + t[3] && t[1] < b[1] + b[4] && b[1] < t[1] + t[4]) below.add(u);
            }
            if (!below.isEmpty()) {
                m.under[i] = new int[below.size()];
                for (int b = 0; b < below.size(); b++) m.under[i][b] = below.get(b);
            }
        }
        Integer[] byZ = new Integer[items];
        for (int i = 0; i < items; i++) byZ[i] = i;
        java.util.Arrays.sort(byZ, (p, q) -> Float.compare(m.box6[p][2], m.box6[q][2]));
        m.byZ = new int[items];
        for (int i = 0; i < items; i++) m.byZ[i] = byZ[i];
        return m;
    }

    private static int roomsAt(Model m, float x, float y) {
        int mask = 0;
        for (int r = 0; r < m.roomRect.length; r++) {
            float[] rr = m.roomRect[r];
            if (x >= rr[0] - 0.2f && x <= rr[2] + 0.2f && y >= rr[1] - 0.2f && y <= rr[3] + 0.2f) mask |= 1 << r;
        }
        return mask;
    }

    private static int color(Map<String, Integer> colors, String key) {
        Integer i = colors.get(key);
        return i == null ? 0 : i;
    }

    private static float[] floats(JSONArray a) throws Exception {
        float[] out = new float[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = (float) a.getDouble(i);
        return out;
    }

    // MARK: - This size

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = width / 1920f;
        nameP = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        stateP = Ui.text(Ui.regular, 26 * k, 0xB3FFFFFF);
        subP = Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF);
        nameCap = Ui.halfCap(nameP);
        subCap = Ui.halfCap(subP);
        edge.setStrokeWidth(Math.max(1, k));
        face.setStrokeWidth(Math.max(1, 0.8f * k));
        int g = 0xFFC07A;
        // Unit shaders, placed and sized with the canvas matrix (or a local matrix) when drawn.
        houseShadow = new RadialGradient(0, 0, 1, new int[] {0x57000000, 0}, null, Shader.TileMode.CLAMP);
        floorPool = new RadialGradient(0, 0, 1, new int[] {0xFF000000 | g, 0x66000000 | g, g}, new float[] {0, 0.45f, 1},
                Shader.TileMode.CLAMP);
        bloomShader = new RadialGradient(0, 0, 1, new int[] {0xFFFFC882, 0x00FFC07A}, null, Shader.TileMode.CLAMP);
        dotHalo = new RadialGradient(0, 0, 1, new int[] {0x80FFC07A, 0x00FFC07A}, null, Shader.TileMode.CLAMP);
        coneShader = new LinearGradient(0, 0, 0, 1, 0x42FFCD8C, 0x00FFCD8C, Shader.TileMode.CLAMP);
        topFade = new LinearGradient(0, 0, 0, FADE_H * k, new int[] {0xFF000000, 0x99000000, 0}, new float[] {0, 0.55f, 1},
                Shader.TileMode.CLAMP);
        fade.setShader(topFade);
        updatePill();
        build();
    }

    /** The overview (the whole plan fitted into camera.box) and each light's place in it, for the arrows. */
    private void build() {
        if (m == null || w == 0) return;
        ax = (m.box[0] + m.box[2]) / 2 * k;
        ay = (m.box[1] + m.box[3]) / 2 * k;
        angles(m.yaw, m.pitch);
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            raw((i & 1) == 0 ? 0 : m.width, (i & 2) == 0 ? 0 : m.depth, (i & 4) == 0 ? 0 : TALL);
            minU = Math.min(minU, pt[0]);
            maxU = Math.max(maxU, pt[0]);
            minV = Math.min(minV, pt[1]);
            maxV = Math.max(maxV, pt[1]);
        }
        s0 = Math.min((m.box[2] - m.box[0]) * k / (maxU - minU), (m.box[3] - m.box[1]) * k / (maxV - minV));
        // The floor point under the box's centre.
        float u = (minU + maxU) / 2, v = (minV + maxV) / 2, ry = v / sinP;
        overX = u * cosT + ry * sinT + m.width / 2;
        overY = -u * sinT + ry * cosT + m.depth / 2;
        aim(m.yaw, m.pitch, 1, overX, overY);
        for (int i = 0; i < navX.length; i++) {
            project(m.at[i][0], m.at[i][1], m.mid[i]);
            navX[i] = pt[0];
            navY[i] = pt[1];
        }
        cameraTarget(!pillPlaced);
        invalidate();
    }

    private void angles(float yaw, float pitch) {
        double t = Math.toRadians(yaw), p = Math.toRadians(pitch);
        cosT = (float) Math.cos(t);
        sinT = (float) Math.sin(t);
        sinP = (float) Math.sin(p);
        cosP = (float) Math.cos(p);
    }

    /** Before the scale: u right, v down, in metres. */
    private void raw(float x, float y, float z) {
        float dx = x - m.width / 2, dy = y - m.depth / 2;
        float rx = dx * cosT - dy * sinT, ry = dx * sinT + dy * cosT;
        pt[0] = rx;
        pt[1] = ry * sinP - z * cosP;
    }

    private void project(float x, float y, float z) {
        raw(x, y, z);
        pt[0] = ox + pt[0] * scale;
        pt[1] = oy + pt[1] * scale;
    }

    /** Larger is nearer the viewer (drawn later). */
    private float depthOf(float x, float y, float z) {
        float dx = x - m.width / 2, dy = y - m.depth / 2;
        return (dx * sinT + dy * cosT) * cosP + z * sinP;
    }

    /** The projection for a camera: yaw and pitch in degrees, zoom, the world point at the box's centre. */
    private void aim(float yaw, float pitch, float zoom, float cx, float cy) {
        angles(yaw, pitch);
        scale = s0 * zoom;
        raw(cx, cy, 0);
        ox = ax - pt[0] * scale;
        oy = ay - pt[1] * scale;
    }

    // MARK: - Drawing

    /** One frame: every spring a step, the camera and the walls; draws, and asks for the next frame while moving. */
    @Override public void doFrame(long nanos) {
        running = false;
        if (m == null || w == 0 || !isShown()) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        clock = (nanos % 3_600_000_000_000L) / 1e9f;
        boolean moving = step(dt, nanos);
        aimNow();
        // Outside walls stand tall when the camera sees their inside, and lower themselves (WALL_CUT) when they face it.
        for (int i = 0; i < wallNow.length; i++) {
            if (!m.hasOut[i]) continue;
            float v = -m.outX[i] * sinT - m.outY[i] * cosT, t = Ui.clamp((v + 0.12f) / 0.24f);
            cut[i].target = t * t * (3 - 2 * t);
            moving |= cut[i].step(dt);
            wallTall[i] = Ui.clamp(cut[i].value);
            wallNow[i] = m.wallLow[i] + (m.wallH[i] - m.wallLow[i]) * wallTall[i];
        }
        moving |= pillX.step(dt) | pillY.step(dt);
        if (pillAge < 1) {
            pillAge = Math.min(1, pillAge + dt / 0.26f);
            moving = true;
        }
        invalidate();
        // The fans, the glints and a lit dot's breath keep frames coming while the house shows.
        if (moving || ambient()) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The always-on touches (fans, glints, breath, drift): not with animations off, nor on Android 8–11's GPUs. */
    private boolean ambient() {
        return !Spring.instant && !Ui.LEGACY;
    }

    /** The camera as it is now, with the idle figure eight (±1.5° of yaw, 36 s) when it's drifting. */
    private void aimNow() {
        float phase = (float) (clock * 2 * Math.PI / 36), d = drift.value;
        aim(camYaw.value + 1.5f * (float) Math.sin(phase) * d, camPitch.value + 0.6f * (float) Math.sin(2 * phase) * d,
                camZoom.value, camX.value, camY.value);
    }

    @Override protected void onDraw(Canvas c) {
        if (m == null || w == 0) return;
        aimNow();
        // Android 12 and newer fade the model under the tab bar in a layer; older GPUs get a plain cut.
        int save = Ui.LEGACY ? c.save() : c.saveLayer(0, 0, w, h, null);
        if (Ui.LEGACY) c.clipRect(0, FADE_H * 0.6f * k, w, h);
        drawGround(c);
        drawItems(c);
        if (!Ui.LEGACY) c.drawRect(0, 0, w, FADE_H * k, fade);
        c.restoreToCount(save);
        drawOverlay(c);
        drawPill(c);
    }

    /** Every spring a step; true while anything still moves. */
    private boolean step(float dt, long now) {
        boolean moving = camYaw.step(dt) | camPitch.step(dt) | camZoom.step(dt) | camX.step(dt) | camY.step(dt);
        moving |= shown.step(dt);
        for (int i = 0; i < lit.length; i++) {
            grow[i].target = focused && i == index ? 1 : 0;
            moving |= grow[i].step(dt) | lit[i].step(dt) | flash[i].step(dt) | nudgeX[i].step(dt) | nudgeY[i].step(dt);
            // The warmth's reach: out at WAVE_SPEED when on, back into the light in WAVE_OUT s when off.
            if (on[i] && wave[i] < WAVE_MAX) {
                wave[i] = Spring.instant ? WAVE_MAX : Math.min(WAVE_MAX, wave[i] + WAVE_SPEED * dt);
                moving = true;
            } else if (!on[i] && wave[i] > 0) {
                wave[i] = Spring.instant ? 0 : Math.max(0, wave[i] - WAVE_MAX / WAVE_OUT * dt);
                moving = true;
            }
            if (ripple[i] >= 0) {
                ripple[i] += dt / 0.45f;
                moving = true;
                if (ripple[i] >= 1) ripple[i] = -1;
            }
        }
        for (int r = 0; r < roomGlow.length; r++) {
            float glow = 0;
            for (int i = 0; i < on.length; i++) if (m.fixtureRoom[i] == r && on[i]) glow = 1;
            roomGlow[r].target = glow;
            moving |= roomGlow[r].step(dt) | emph[r].step(dt);
        }
        // A slow figure eight once the remote has been still for IDLE (eased in and out).
        drift.target = ambient() && now - lastKey > IDLE ? 1 : 0;
        moving |= drift.step(dt);
        return moving;
    }

    private void drawGround(Canvas c) {
        // A soft shadow under the house, so it sits on the frosted background.
        project(m.width / 2, m.depth / 2, 0);
        float spread = 0.62f * Math.max(m.width, m.depth) * scale;
        c.save();
        c.translate(pt[0], pt[1] + 0.4f * scale);
        c.scale(spread, spread * sinP);
        fill.setShader(houseShadow);
        c.drawCircle(0, 0, 1, fill);
        fill.setShader(null);
        c.restore();
        // Each room's base, back to front (insertion sort: nearly sorted from the last frame).
        for (int i = 1; i < slab.length; i++) {
            int v = slab[i], j = i - 1;
            float d = roomDepth(v);
            while (j >= 0 && roomDepth(slab[j]) > d) {
                slab[j + 1] = slab[j];
                j--;
            }
            slab[j + 1] = v;
        }
        for (int i = 0; i < slab.length; i++) {
            float[] rr = m.roomRect[slab[i]];
            box(c, rr[0] - 0.1f, rr[1] - 0.1f, -0.25f, rr[2] - rr[0] + 0.2f, rr[3] - rr[1] + 0.2f, 0.25f, m.wall, m.wallTop, 0,
                    emph[slab[i]].value, 1, false);
        }
        for (int r = 0; r < m.roomRect.length; r++) {
            float[] rr = m.roomRect[r];
            face.setColor(tone(m.roomFloor[r], 0.35f * roomGlow[r].value, 1, emph[r].value, 1));
            quad(c, rr[0], rr[1], rr[2], rr[3], 0, face);
        }
        if (!Ui.LEGACY) {
            // Ambient occlusion where walls meet floors.
            for (int i = 0; i < m.strip.length; i++) {
                float[] st = m.strip[i];
                float e = 0.5f + 0.5f * emphOf(m.stripRooms[i]);
                fill.setColor(Ui.alpha(OCCLUSION, 0.12f * e));
                quad(c, st[0], st[1], st[2], st[3], 0.001f, fill);
                fill.setColor(Ui.alpha(OCCLUSION, 0.06f * e));
                quad(c, st[4], st[5], st[6], st[7], 0.001f, fill);
            }
        }
        for (int i = 0; i < m.flat.length; i++) {
            float[] b = m.flat[i];
            box(c, b[0], b[1], b[2], b[3], b[4], b[5], m.flatColor[i], -1, warmth(m.flatRooms[i], b[0] + b[3] / 2,
                    b[1] + b[4] / 2), emphOf(m.flatRooms[i]), 1, false);
        }
        if (!Ui.LEGACY) {
            // Soft contact shadows under what stands on the floor.
            for (int i = 0; i < m.shadowOf.length; i++) {
                int it = m.shadowOf[i];
                float[] b = m.box6[it];
                float e = 0.5f + 0.5f * emphOf(m.rooms[it]);
                for (int layer = 0; layer < 2; layer++) {
                    float grow = layer == 0 ? 0.05f : 0.16f;
                    fill.setColor(Ui.alpha(SHADOW, (layer == 0 ? 0.13f : 0.07f) * e));
                    if (m.type[it] == ROUND) {
                        project(b[0] + b[3] / 2, b[1] + b[4] / 2, 0.002f);
                        float rr = (Math.min(b[3], b[4]) / 2 + grow) * scale;
                        oval.set(pt[0] - rr, pt[1] - rr * sinP, pt[0] + rr, pt[1] + rr * sinP);
                        c.drawOval(oval, fill);
                    } else {
                        quad(c, b[0] - grow, b[1] - grow, b[0] + b[3] + grow, b[1] + b[4] + grow, 0.002f, fill);
                    }
                }
            }
        }
        for (int i = 0; i < on.length; i++) floorPool(c, i);
    }

    private float roomDepth(int r) {
        float[] rr = m.roomRect[r];
        return depthOf((rr[0] + rr[2]) / 2, (rr[1] + rr[3]) / 2, 0);
    }

    private void drawItems(Canvas c) {
        int n = order.length;
        for (int i = 0; i < n; i++) {
            float[] b = m.box6[i];
            if (m.type[i] == LIGHT) {
                int f = m.fixtureOf[i];
                float[] at = m.at[f];
                depth[i] = m.kind[f] == TUBE ? depthOf(at[0] + 0.12f, at[1], at[2]) : depthOf(at[0], at[1], 0.4f);
            } else if (m.type[i] == FAN) {
                float[] fc = m.fanC[m.fixtureOf[i]];
                depth[i] = depthOf(fc[0], fc[1], 0.3f);
            } else {
                depth[i] = depthOf(b[0] + b[3] / 2, b[1] + b[4] / 2, b[2]);
            }
        }
        for (int z = 0; z < n; z++) {
            int i = m.byZ[z];
            int[] under = m.under[i];
            if (under != null) for (int u : under) depth[i] = Math.max(depth[i], depth[u] + 0.001f);
        }
        for (int i = 1; i < n; i++) {
            int v = order[i], j = i - 1;
            float d = depth[v];
            while (j >= 0 && depth[order[j]] > d) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        for (int o = 0; o < n; o++) {
            int i = order[o];
            float[] b = m.box6[i];
            int rooms = m.rooms[i];
            float e = emphOf(rooms);
            switch (m.type[i]) {
                case LIGHT:
                    drawLight(c, m.fixtureOf[i]);
                    break;
                case FAN:
                    drawFan(c, m.fixtureOf[i], e);
                    break;
                case PIECE: {
                    float g = 0.4f * maxGlow(rooms), wh = wallNow[m.wallOf[i]];
                    box(c, b[0], b[1], 0, b[3], b[4], wh, m.wall, m.wallTop, g, e, 1, true);
                    sides(c, b[0], b[1], 0, b[3], b[4], Math.min(0.09f, wh), m.baseboard, g, e, 1);   // the baseboards
                    break;
                }
                case DISC_X:
                case DISC_Y:
                    disc(c, i, b, warmth(rooms, b[0] + b[3] / 2, b[1] + b[4] / 2), e);
                    break;
                default: {
                    int wall = m.wallOf[i];
                    float z = b[2], hh = b[5], a = m.alpha[i];
                    if (wall >= 0) {
                        if (m.followsWall[i]) {
                            float s = wallNow[wall] / m.wallH[wall];
                            z *= s;
                            hh *= s;
                        } else {
                            a *= wallTall[wall];   // art and cabinets on an outside wall are gone while it's down
                        }
                    }
                    float g = warmth(rooms, b[0] + b[3] / 2, b[1] + b[4] / 2);
                    if (m.type[i] == ROUND) round(c, b, z, hh, m.color[i], g, e, a);
                    else box(c, b[0], b[1], z, b[3], b[4], hh, m.color[i], -1, g, e, a, true);
                    if (m.screen[i] && ambient()) glint(c, i, b, e);
                }
            }
        }
    }

    /** A box: the vertical faces turned toward the camera, then its top with a light clay edge. */
    private void box(Canvas c, float x, float y, float z, float bw, float bd, float bh, int color, int topColor, float g,
            float e, float a, boolean rim) {
        if (bh <= 0.001f || a < 0.01f) return;
        sides(c, x, y, z, bw, bd, bh, color, g, e, a);
        face.setColor(tone(topColor >= 0 ? topColor : color, g, topColor >= 0 ? 1 : 1.05f, e, a));
        quad(c, x, y, x + bw, y + bd, z + bh, face);
        if (rim && !Ui.LEGACY && bh > 0.03f && bw > 0.08f && bd > 0.08f) {
            edge.setColor(Ui.alpha(0xFFFFFFFF, 0.3f * a * (0.4f + 0.6f * e)));
            c.drawPath(tmp, edge);
        }
    }

    /** A box's vertical faces that look at the camera. */
    private void sides(Canvas c, float x, float y, float z, float bw, float bd, float bh, int color, float g, float e, float a) {
        if (cosT > 0.001f) side(c, x, y + bd, x + bw, y + bd, z, bh, tone(color, g, 0.93f, e, a));
        if (cosT < -0.001f) side(c, x + bw, y, x, y, z, bh, tone(color, g, 0.93f, e, a));
        if (sinT > 0.001f) side(c, x + bw, y, x + bw, y + bd, z, bh, tone(color, g, 0.83f, e, a));
        if (sinT < -0.001f) side(c, x, y + bd, x, y, z, bh, tone(color, g, 0.83f, e, a));
    }

    /** A flat circle on one side of something (a washer's door, a dial): only when that side faces the camera. */
    private void disc(Canvas c, int i, float[] b, float g, float e) {
        boolean alongY = m.type[i] == DISC_Y;
        if ((alongY ? cosT : sinT) * m.faceSign[i] <= 0.001f) return;
        float r = (alongY ? b[3] : b[4]) / 2, cx = b[0] + b[3] / 2, cy = b[1] + b[4] / 2, cz = b[2] + b[5] / 2;
        tmp.rewind();
        for (int p = 0; p < DISC_POINTS; p++) {
            double t = p * 2 * Math.PI / DISC_POINTS;
            float u = r * (float) Math.cos(t), v = r * (float) Math.sin(t);
            if (alongY) project(cx + u, cy, cz + v);
            else project(cx, cy + u, cz + v);
            if (p == 0) tmp.moveTo(pt[0], pt[1]);
            else tmp.lineTo(pt[0], pt[1]);
        }
        tmp.close();
        fill.setColor(tone(m.color[i], g, alongY ? 0.97f : 0.88f, e, m.alpha[i]));
        c.drawPath(tmp, fill);
    }

    private void side(Canvas c, float x0, float y0, float x1, float y1, float z, float bh, int color) {
        project(x0, y0, z);
        q[0] = pt[0];
        q[1] = pt[1];
        project(x1, y1, z);
        q[2] = pt[0];
        q[3] = pt[1];
        project(x1, y1, z + bh);
        q[4] = pt[0];
        q[5] = pt[1];
        project(x0, y0, z + bh);
        q[6] = pt[0];
        q[7] = pt[1];
        face.setColor(color);
        poly(c, face);
    }

    /** A horizontal rectangle at height z. */
    private void quad(Canvas c, float x0, float y0, float x1, float y1, float z, Paint p) {
        project(x0, y0, z);
        q[0] = pt[0];
        q[1] = pt[1];
        project(x1, y0, z);
        q[2] = pt[0];
        q[3] = pt[1];
        project(x1, y1, z);
        q[4] = pt[0];
        q[5] = pt[1];
        project(x0, y1, z);
        q[6] = pt[0];
        q[7] = pt[1];
        poly(c, p);
    }

    private void poly(Canvas c, Paint p) {
        tmp.rewind();
        tmp.moveTo(q[0], q[1]);
        tmp.lineTo(q[2], q[3]);
        tmp.lineTo(q[4], q[5]);
        tmp.lineTo(q[6], q[7]);
        tmp.close();
        c.drawPath(tmp, p);
    }

    /** A round thing (a pot, a stool, a basket): a circle at the camera's pitch is an upright ellipse here. */
    private void round(Canvas c, float[] b, float z, float bh, int color, float g, float e, float a) {
        float r = Math.min(b[3], b[4]) / 2, cx = b[0] + b[3] / 2, cy = b[1] + b[4] / 2;
        float rx = r * scale, ry = rx * sinP;
        project(cx, cy, z);
        float bx = pt[0], by = pt[1];
        project(cx, cy, z + bh);
        float tx = pt[0], ty = pt[1];
        fill.setColor(tone(color, g, 0.86f, e, a));
        tmp.rewind();
        tmp.moveTo(bx - rx, by);
        tmp.lineTo(tx - rx, ty);
        tmp.lineTo(tx + rx, ty);
        tmp.lineTo(bx + rx, by);
        tmp.close();
        c.drawPath(tmp, fill);
        oval.set(bx - rx, by - ry, bx + rx, by + ry);
        c.drawOval(oval, fill);
        fill.setColor(tone(color, g, 1.05f, e, a));
        oval.set(tx - rx, ty - ry, tx + rx, ty + ry);
        c.drawOval(oval, fill);
    }

    /** Each lit light's warm pool on its room's floor (added light), a circle flattened by the pitch. */
    private void floorPool(Canvas c, int i) {
        float g = Ui.clamp(lit[i].value) * (1 + Math.max(0, flash[i].value));
        if (g < 0.01f || wave[i] < 0.05f) return;
        float[] rr = m.roomRect[m.fixtureRoom[i]];
        project(rr[0], rr[1], 0);
        clip.rewind();
        clip.moveTo(pt[0], pt[1]);
        project(rr[2], rr[1], 0);
        clip.lineTo(pt[0], pt[1]);
        project(rr[2], rr[3], 0);
        clip.lineTo(pt[0], pt[1]);
        project(rr[0], rr[3], 0);
        clip.lineTo(pt[0], pt[1]);
        clip.close();
        project(m.glowAt[i][0], m.glowAt[i][1], 0);
        float r = Math.min(3, wave[i] + 0.4f) * scale;   // the pool spreads with the warmth
        c.save();
        c.clipPath(clip);
        c.translate(pt[0], pt[1]);
        c.scale(r, r * sinP);
        add.setShader(floorPool);
        add.setAlpha(Math.round(255 * Math.min(0.6f, 0.32f * g)));
        c.drawCircle(0, 0, 1, add);
        c.restore();
        add.setShader(null);
        add.setAlpha(255);
    }

    private void bloom(Canvas c, float x, float y, float r, float g) {
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        add.setShader(bloomShader);
        add.setAlpha(Math.round(255 * Math.min(0.8f, 0.55f * g)));
        c.drawCircle(0, 0, 1, add);
        c.restore();
        add.setShader(null);
        add.setAlpha(255);
    }

    private void drawLight(Canvas c, int i) {
        float g = Ui.clamp(lit[i].value), boost = 1 + Math.max(0, flash[i].value), s = 1 + 0.12f * grow[i].value;
        float e = emphOf(1 << m.fixtureRoom[i]);
        float[] at = m.at[i];
        project(at[0], at[1], m.mid[i]);
        float cx = pt[0], cy = pt[1];
        c.save();
        c.scale(s, s, cx, cy);
        if (m.kind[i] == TUBE) {
            float half = m.length[i] / 2, ex = m.alongX[i] ? half : 0, ey = m.alongX[i] ? 0 : half;
            project(at[0] - ex, at[1] - ey, at[2]);
            float x0 = pt[0], y0 = pt[1];
            project(at[0] + ex, at[1] + ey, at[2]);
            float x1 = pt[0], y1 = pt[1];
            if (g > 0.01f) bloom(c, cx, cy, 1.2f * scale, g * boost);
            line.setColor(tone(m.charcoal, 0, 0.9f, e, 1));
            line.setStrokeWidth(Math.max(3 * k, TUBE_W * scale));
            c.drawLine(x0, y0, x1, y1, line);
            float lx0 = x0 + (x1 - x0) * TUBE_CAP, ly0 = y0 + (y1 - y0) * TUBE_CAP;
            float lx1 = x0 + (x1 - x0) * (1 - TUBE_CAP), ly1 = y0 + (y1 - y0) * (1 - TUBE_CAP);
            line.setColor(g > 0.01f ? blend(0xFFD6DAE2, 0xFFFFECCC, g) : tone(m.white, 0, 0.92f, e, 1));
            line.setStrokeWidth(Math.max(2 * k, TUBE_LIT * scale));
            c.drawLine(lx0, ly0, lx1, ly1, line);
            if (g > 0.01f) {
                line.setColor(Ui.alpha(0xFFFFFAF0, g));
                line.setStrokeWidth(Math.max(k, TUBE_CORE * scale));
                c.drawLine(lx0, ly0, lx1, ly1, line);
            }
        } else if (m.kind[i] == LAMP) {
            float sb = m.shadeBottom[i];
            project(at[0], at[1], 0);
            float bx = pt[0], by = pt[1];
            project(at[0], at[1], sb);
            float sx = pt[0], sy = pt[1];
            project(at[0], at[1], sb + SHADE_H);
            float tx = pt[0], ty = pt[1];
            if (g > 0.01f) {
                // The cone of light from the shade to the floor.
                float top = 0.22f * scale, bottom = 0.95f * scale;
                matrix.setScale(1, Math.max(1, by - sy));
                matrix.postTranslate(0, sy);
                coneShader.setLocalMatrix(matrix);
                add.setShader(coneShader);
                add.setAlpha(Math.round(255 * Math.min(1, g * boost)));
                tmp.rewind();
                tmp.moveTo(sx - top, sy);
                tmp.lineTo(sx + top, sy);
                tmp.lineTo(bx + bottom, by);
                tmp.lineTo(bx - bottom, by);
                tmp.close();
                c.drawPath(tmp, add);
                add.setShader(null);
                add.setAlpha(255);
            }
            float br = BASE_R * scale;
            fill.setColor(tone(m.wood, g, 0.8f, e, 1));
            oval.set(bx - br, by - br * sinP, bx + br, by + br * sinP);
            c.drawOval(oval, fill);
            line.setColor(tone(m.wood, g, 0.7f, e, 1));
            line.setStrokeWidth(Math.max(1.5f * k, 0.035f * scale));
            c.drawLine(bx, by, sx, sy, line);
            if (g > 0.01f) bloom(c, cx, cy, 0.9f * scale * boost, g * boost);
            float rt = SHADE_RT * scale, rb = SHADE_RB * scale;
            fill.setColor(g > 0.01f ? blend(m.cool[m.linen], 0xFFFFE2B4, g) : tone(m.linen, 0, 0.95f, e, 1));
            tmp.rewind();
            tmp.moveTo(tx - rt, ty);
            tmp.lineTo(tx + rt, ty);
            tmp.lineTo(sx + rb, sy);
            tmp.lineTo(sx - rb, sy);
            tmp.close();
            c.drawPath(tmp, fill);
        } else {
            float r = 0.16f * scale;
            if (g > 0.01f) bloom(c, cx, cy, r * 6, g * boost);
            fill.setColor(blend(0xFFC3C8D1, 0xFFFFE9C6, g));
            c.drawCircle(cx, cy, r, fill);
        }
        c.restore();
    }

    // MARK: - The dots and the pill (last, above everything)

    private void drawOverlay(Canvas c) {
        for (int i = 0; i < on.length; i++) {
            float[] at = m.at[i];
            project(at[0], at[1], m.top[i]);
            float bx = pt[0], by = pt[1];
            project(at[0], at[1], m.top[i] + 0.42f);
            float g = Ui.clamp(lit[i].value), f = Ui.clamp(grow[i].value);
            // The focused dot grows 1.35× and rides 8 px higher on a longer stem; a dead end nudges it (rubber band).
            float x = pt[0] + Spring.rubber(nudgeX[i].value) * k, y = pt[1] - 8 * k * grow[i].value + Spring.rubber(nudgeY[i].value) * k;
            float breath = g > 0.5f && ambient() ? 1 + 0.04f * g * (float) Math.sin(clock * 2 * Math.PI / 4) : 1;
            float r = DOT_R * k * (1 + 0.35f * grow[i].value) * breath;
            // The hairline stem from the light up to its dot.
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setStrokeWidth(Math.max(1, k));
            line.setColor(Ui.alpha(0xFFFFFFFF, 0.45f + 0.4f * f));
            c.drawLine(bx, by, x, y + r, line);
            line.setStrokeCap(Paint.Cap.ROUND);
            if (g > 0.01f) {
                c.save();
                c.translate(x, y);
                c.scale(r * 3.2f, r * 3.2f);
                fill.setShader(dotHalo);
                fill.setAlpha(Math.round(255 * g));
                c.drawCircle(0, 0, 1, fill);
                fill.setShader(null);
                fill.setAlpha(255);
                c.restore();
            }
            fill.setColor(g > 0.5f ? AMBER : 0xB81E2026);
            c.drawCircle(x, y, r, fill);
            edge.setStrokeWidth(2 * k);
            edge.setColor(g > 0.5f ? 0xF2FFF0DC : 0xCCFFFFFF);
            c.drawCircle(x, y, r, edge);
            if (f > 0.01f) {
                edge.setStrokeWidth(3 * k);
                edge.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(x, y, r + 6 * k, edge);
            }
            if (ripple[i] >= 0) {
                // OK: a ring leaves the dot, 1 → 2.8× its size, fading from 0.8.
                edge.setStrokeWidth(2 * k);
                edge.setColor(Ui.alpha(0xFFFFFFFF, 0.8f * (1 - ripple[i])));
                c.drawCircle(x, y, r * (1 + 1.8f * ripple[i]), edge);
            }
            edge.setStrokeWidth(Math.max(1, k));
            dotX[i] = x;
            dotY[i] = y;
            dotR[i] = r;
        }
    }

    /**
     * The pill above the focused light's dot, on its own spring (stepped in doFrame), kept inside the safe area. Moving
     * to another light, the old text leaves in 0.12 s and the new one comes in just after, both on the same capsule.
     */
    private void drawPill(Canvas c) {
        float a = Ui.clamp(shown.value);
        if (nameP == null || pillName.isEmpty() || dotX.length == 0) return;
        float padX = 24 * k, lineH = 52 * k, subH = pillSub.isEmpty() ? 0 : 30 * k;
        float pw = Math.max(pillNameW + pillStateW, pillSubW) + 2 * padX, ph = lineH + subH;
        float tx = Math.max(SAFE_L * k + pw / 2, Math.min(SAFE_R * k - pw / 2, dotX[index]));
        float ty = Math.max(SAFE_T * k, Math.min(SAFE_B * k - ph, dotY[index] - dotR[index] - 16 * k - ph));
        if (Math.abs(pillX.target - tx) > 0.5f || Math.abs(pillY.target - ty) > 0.5f) {
            pillX.target = tx;
            pillY.target = ty;
            kick();
        }
        if (!pillPlaced || a < 0.01f) {
            pillX.snap(tx);
            pillY.snap(ty);
            pillPlaced = true;
        }
        if (a < 0.01f) return;
        float t = pillAge, outA = a * Ui.clamp(1 - t * 0.26f / 0.12f), inA = a * Ui.clamp((t * 0.26f - 0.06f) / 0.2f);
        if (outA > 0.01f && !oldName.isEmpty()) {
            float ow = Math.max(oldNameW + oldStateW, oldSubW) + 2 * padX, oh = lineH + (oldSub.isEmpty() ? 0 : 30 * k);
            capsule(c, pillX.value - ow / 2, pillY.value, ow, oh, outA, oldName, oldState, oldSub, oldNameW, false);
        }
        capsule(c, pillX.value - pw / 2, pillY.value, pw, ph, t >= 1 ? a : inA, pillName, pillState, pillSub, pillNameW,
                on[index]);
    }

    private void capsule(Canvas c, float left, float top, float pw, float ph, float a, String name, String state,
            String sub, float nameW, boolean lit) {
        if (a < 0.01f) return;
        float padX = 24 * k, lineH = 52 * k;
        pillRect.set(left, top, left + pw, top + ph);
        fill.setColor(Ui.alpha(PILL, a));
        c.drawRoundRect(pillRect, 26 * k, 26 * k, fill);
        edge.setStrokeWidth(Math.max(1, k));
        edge.setColor(Ui.alpha(0x29FFFFFF, a));
        c.drawRoundRect(pillRect, 26 * k, 26 * k, edge);
        float base = top + lineH / 2 + nameCap;
        nameP.setAlpha(Math.round(255 * a));
        c.drawText(name, left + padX, base, nameP);
        stateP.setColor(Ui.alpha(lit ? AMBER : 0xB3FFFFFF, a));
        c.drawText(state, left + padX + nameW, base, stateP);
        if (!sub.isEmpty()) {
            subP.setAlpha(Math.round(0xB3 * a));
            c.drawText(sub, left + padX, top + lineH + 15 * k - 6 * k + subCap, subP);
        }
    }

    // MARK: - Fans and screens

    /** A standing fan's cage and its three blades, turning in the plane it faces (the motor behind is a box). */
    private void drawFan(Canvas c, int f, float e) {
        float[] fc = m.fanC[f];
        float r = m.fanR[f], turn = ambient() ? (clock / FAN_PERIOD % 1) * 2 * (float) Math.PI : 0.3f;
        boolean alongY = m.fanAlongY[f];
        fill.setColor(tone(m.silver, 0, 0.95f, e, 0.85f));
        for (int b = 0; b < 3; b++) {
            float a = turn + b * 2 * (float) Math.PI / 3;
            tmp.rewind();
            fanPoint(fc, alongY, 0.035f, a + 0.32f);
            tmp.moveTo(pt[0], pt[1]);
            fanPoint(fc, alongY, r * 0.92f, a + 0.22f);
            tmp.lineTo(pt[0], pt[1]);
            fanPoint(fc, alongY, r * 0.92f, a - 0.22f);
            tmp.lineTo(pt[0], pt[1]);
            fanPoint(fc, alongY, 0.035f, a - 0.32f);
            tmp.lineTo(pt[0], pt[1]);
            tmp.close();
            c.drawPath(tmp, fill);
        }
        tmp.rewind();
        for (int p = 0; p < 12; p++) {
            fanPoint(fc, alongY, 0.04f, p * (float) Math.PI / 6);
            if (p == 0) tmp.moveTo(pt[0], pt[1]);
            else tmp.lineTo(pt[0], pt[1]);
        }
        tmp.close();
        fill.setColor(tone(m.charcoal, 0, 1, e, 1));
        c.drawPath(tmp, fill);
        // The cage: a thin ring.
        tmp.rewind();
        for (int p = 0; p < 24; p++) {
            fanPoint(fc, alongY, r, p * (float) Math.PI / 12);
            if (p == 0) tmp.moveTo(pt[0], pt[1]);
            else tmp.lineTo(pt[0], pt[1]);
        }
        tmp.close();
        edge.setStrokeWidth(Math.max(1, 0.012f * scale));
        edge.setColor(tone(m.charcoal, 0, 1.1f, e, 1));
        c.drawPath(tmp, edge);
        edge.setStrokeWidth(Math.max(1, k));
    }

    /** A point on a fan's face plane, at radius r and angle a around its centre, projected into pt. */
    private void fanPoint(float[] fc, boolean alongY, float r, float a) {
        float u = r * (float) Math.cos(a), v = r * (float) Math.sin(a);
        if (alongY) project(fc[0] + u, fc[1], fc[2] + v);
        else project(fc[0], fc[1] + u, fc[2] + v);
    }

    /** A screen's standby glint: a faint diagonal band sweeping its face once every GLINT_PERIOD s. */
    private void glint(Canvas c, int i, float[] o, float e) {
        float q = ((clock / GLINT_PERIOD + m.phase[i]) % 1) * 5;
        if (q >= 1) return;
        boolean alongY = o[3] >= o[4];
        float x = o[0], y = o[1], z = o[2], sw = alongY ? o[3] : o[4], sh = o[5];
        float front = alongY ? (cosT > 0 ? y + o[4] + 0.002f : y - 0.002f) : (sinT > 0 ? x + o[3] + 0.002f : x - 0.002f);
        float mid = -0.3f * sw + q * 1.6f * sw, band = 0.12f * sw, lean = 0.35f * sh;
        float u0 = Math.max(0, mid - lean), u1 = Math.min(sw, mid + band - lean), u2 = Math.min(sw, mid + band), u3 = Math.max(0, mid);
        if (u2 <= 0 || u0 >= sw) return;
        tmp.rewind();
        screenPoint(alongY, x, y, front, z, u0, 0);
        tmp.moveTo(pt[0], pt[1]);
        screenPoint(alongY, x, y, front, z, u1, 0);
        tmp.lineTo(pt[0], pt[1]);
        screenPoint(alongY, x, y, front, z, u2, sh);
        tmp.lineTo(pt[0], pt[1]);
        screenPoint(alongY, x, y, front, z, u3, sh);
        tmp.lineTo(pt[0], pt[1]);
        tmp.close();
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * (float) Math.sin(q * Math.PI) * (0.4f + 0.6f * e)));
        c.drawPath(tmp, fill);
    }

    private void screenPoint(boolean alongY, float x, float y, float front, float z, float u, float v) {
        if (alongY) project(x + u, front, z + v);
        else project(front, y + u, z + v);
    }

    // MARK: - Colour

    /** A colour: cool to warm by g, shaded, faded toward grey when its room isn't the one in focus, then alpha. */
    private int tone(int index, float g, float shade, float e, float a) {
        int cool = m.cool[index], warm = m.warm[index];
        float r = (((cool >> 16) & 255) + ((((warm >> 16) & 255) - ((cool >> 16) & 255)) * g)) * shade;
        float gr = (((cool >> 8) & 255) + ((((warm >> 8) & 255) - ((cool >> 8) & 255)) * g)) * shade;
        float b = ((cool & 255) + (((warm & 255) - (cool & 255)) * g)) * shade;
        if (e < 0.999f) {
            // Another room than the focused one: 30% less colour, and 45% of it left over the dark background.
            float lum = 0.3f * r + 0.59f * gr + 0.11f * b, keep = 1 - DESAT * (1 - e), left = DIM + (1 - DIM) * e;
            r = BG[0] + (lum + (r - lum) * keep - BG[0]) * left;
            gr = BG[1] + (lum + (gr - lum) * keep - BG[1]) * left;
            b = BG[2] + (lum + (b - lum) * keep - BG[2]) * left;
        }
        int alpha = Math.round(255 * Ui.clamp(a));
        return (alpha << 24) | (clamp255(r) << 16) | (clamp255(gr) << 8) | clamp255(b);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private float emphOf(int rooms) {
        if (rooms == 0) return 1;
        float e = 0;
        for (int r = 0; r < emph.length; r++) if ((rooms & (1 << r)) != 0) e = Math.max(e, emph[r].value);
        return e;
    }

    private float maxGlow(int rooms) {
        float g = 0;
        for (int r = 0; r < roomGlow.length; r++) if ((rooms & (1 << r)) != 0) g = Math.max(g, roomGlow[r].value);
        return g;
    }

    /** How warm a thing is: more the nearer it is to a lit light in its room, once that light's warmth reached it. */
    private float warmth(int rooms, float x, float y) {
        float g = 0;
        for (int i = 0; i < on.length; i++) {
            float l = lit[i].value;
            if (l < 0.01f || (rooms & (1 << m.fixtureRoom[i])) == 0) continue;
            float dx = x - m.glowAt[i][0], dy = y - m.glowAt[i][1], d = (float) Math.sqrt(dx * dx + dy * dy);
            // The wave front, softened over 0.8 m.
            float t = Ui.clamp((wave[i] - d) / 0.8f + 0.5f), reach = t * t * (3 - 2 * t);
            g += l * reach * (0.4f + Math.max(0, 1 - d / 4.2f) * 0.8f);
        }
        return Math.min(1, g);
    }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
