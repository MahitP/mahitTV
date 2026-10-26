package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Home tab (WELLY-HOME-REMOTE-SPEC.md §4): the house in app/assets/house.json, roof off, seen from above; its four
 * lights take the focus and OK turns them on or off (SmartHome). Lit rooms turn from the cool palette to the warm one,
 * with a warm glow on the floor. The projected shapes are made once per size; only the glow and the focus animate.
 */
final class HouseView extends View {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Walls are boxes this thick, cut in pieces of at most PIECE m so each sorts by its own depth. */
    private static final float WALL = 0.12f, PIECE = 1f;
    private static final float CORD_TOP = 2.6f, BULB_R = 0.12f, GLOW_R = 3f, BULB_HALO = 0.6f, FOCUS_HALO = 0.55f;
    private static final float SHADE_BOTTOM = 1.3f, SHADE_H = 0.45f, SHADE_RB = 0.22f, SHADE_RT = 0.13f, BASE_R = 0.16f;
    private static final float CAPTION_Y = 1000;
    // Face shading (top lighter; the faces toward +y and +x darker, as lit from the front left).
    private static final float TOP = 0.08f, FACE_Y = -0.06f, FACE_X = -0.16f;
    private static final int BULB_OFF = 0xFFC3C8D1, BULB_ON = 0xFFFFE9C6, BULB_CORE = 0xFFFFF8EC, CORD = 0xFF8A909B;
    private static final int SHADE_ON = 0xFFFFE2B5, SHADE_IN_OFF = 0xFF7D838E, SHADE_IN_ON = 0xFFFFF3DD;

    /** house.json as read (metres; x right, y toward the viewer, z up). */
    private static final class Model {
        float yaw, pitch, width, depth;
        final float[] box = new float[4];
        int[] cool, warm;
        int glow, wallColor, wallTop, wood, linen;
        String[] roomName;
        float[][] roomRect;
        int[] roomFloor;
        float[][] walls;          // x0, y0, x1, y1, h
        float[][] furniture;      // x, y, z, w, d, h
        int[] furnitureRoom, furnitureColor;
        boolean[] rug;
        String[] fixtureId, fixtureName;
        int[] fixtureRoom;
        boolean[] lamp;
        float[][] at;
    }

    /** One thing to draw in depth order: a box (a wall piece, a piece of furniture) or a light. */
    private static final class Item {
        float depth, x, y, z, w, d, h;
        int color, top, rooms, fixture = -1;
        float topShade;
        boolean skipLow, skipHigh, alongX;   // a wall piece's ends inside the wall (never seen)
    }

    private final SmartHome.Bridge bridge;
    private Model m;
    private boolean[] on = new boolean[0];
    private Spring[] lit = new Spring[0], focus = new Spring[0];
    private final Spring shown = new Spring(0.30f, 0.80f, 0);   // the caption, while the house has the focus
    private int index;
    private boolean focused;
    private String caption = "";
    private long lastNanos;

    // This size's shapes.
    private int w;
    private float k = 1, scale, ox, oy, sinT, cosT, sinP, cosP;
    private Path[] facePath;
    private int[] faceColor, faceRooms, faceFixture;
    private float[] faceShade;
    private int floorFaces;
    private Path[] roomFloor;
    private float[] roomGlow = new float[0];
    private float[] fixX, fixY, cordX, cordY, poleX, poleY, glowX, glowY;
    private Path[] shade;
    private RectF[] shadeTop, lampBase;
    private Shader[] floorGlow, bulbHalo;
    private Shader focusHalo;
    private float bulbR, haloR, focusR, glowR;
    private final float[] pt = new float[2];
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint captionPaint;

    HouseView(Context c) {
        super(c);
        bridge = SmartHome.get(c);
        face.setStyle(Paint.Style.FILL_AND_STROKE);   // the thin stroke closes the seams between faces
        face.setStrokeJoin(Paint.Join.ROUND);
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
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
        int n = model.fixtureId.length;
        on = new boolean[n];
        lit = new Spring[n];
        focus = new Spring[n];
        for (int i = 0; i < n; i++) {
            SmartHome.Device d = bridge.device(model.fixtureId[i]);
            on[i] = d != null && d.known && d.on;
            lit[i] = new Spring(0.45f, 0.90f, on[i] ? 1 : 0);
            focus[i] = new Spring(0.30f, 0.80f, 0);
        }
        roomGlow = new float[model.roomName.length];
        build();
        setFocused(focused);
        updateCaption();
        refresh();
    }

    // MARK: - What HomeView calls

    /** Whether the house has the focus (the tab bar has it otherwise): the focused light grows, the caption shows. */
    void setFocused(boolean f) {
        focused = f;
        shown.target = f ? 1 : 0;
        for (int i = 0; i < focus.length; i++) focus[i].target = f && i == index ? 1 : 0;
        if (f) updateCaption();
        kick();
    }

    /** The D-pad: the nearest light that way on screen. False when there is none (↑ from the top one: the tab bar). */
    boolean move(int dx, int dy) {
        if (fixX == null || fixX.length == 0) return false;
        float x0 = fixX[index], y0 = fixY[index];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int j = 0; j < fixX.length; j++) {
            if (j == index) continue;
            float ddx = fixX[j] - x0, ddy = fixY[j] - y0;
            float along = dx != 0 ? ddx * dx : ddy * dy, across = Math.abs(dx != 0 ? ddy : ddx);
            if (along <= 4 * k) continue;
            float score = along + 2 * across;
            if (score < bestScore) {
                bestScore = score;
                best = j;
            }
        }
        if (best < 0) return false;
        index = best;
        setFocused(true);
        return true;
    }

    /** OK: the focused light on or off, at once; back as it was, with the reason, if the device doesn't answer. */
    void toggle() {
        if (m == null) return;
        int f = index;
        SmartHome.Device d = bridge.device(m.fixtureId[f]);
        boolean want = !on[f];
        if (d == null) {
            if (Config.PREVIEW) setOn(f, want);   // a preview build shows the lights without devices
            updateCaption();
            return;
        }
        setOn(f, want);
        updateCaption();
        bridge.set(d, want, (device, ok) -> {
            if (!ok) setOn(f, !want);
            updateCaption();
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
            updateCaption();
        });
    }

    private void setOn(int f, boolean value) {
        on[f] = value;
        lit[f].target = value ? 1 : 0;
        kick();
    }

    private void kick() {
        lastNanos = 0;
        invalidate();
    }

    /** "My Room · Lamp · On", the reason it can't be reached, or how to connect it. */
    private void updateCaption() {
        if (m == null) return;
        int f = index;
        SmartHome.Device d = bridge.device(m.fixtureId[f]);
        String where = name(m.roomName[m.fixtureRoom[f]]) + " · " + name(m.fixtureName[f]) + " · ";
        if (d == null && !Config.PREVIEW) {
            caption = Ui.tr("Not connected: add its IP to kasa= in config.properties");
        } else if (d != null && !d.reachable && !d.problem.isEmpty()) {
            caption = where + problem(d.problem);
        } else {
            caption = where + (on[f] ? Ui.tr("On") : Ui.tr("Off"));
        }
        invalidate();
    }

    /** house.json's names, translated (spelled out so tools/translate.py finds them). */
    private static String name(String english) {
        switch (english) {
            case "Parents' Room": return Ui.tr("Parents' Room");
            case "My Room": return Ui.tr("My Room");
            case "Living Room": return Ui.tr("Living Room");
            case "Lightbulb": return Ui.tr("Lightbulb");
            case "Lamp": return Ui.tr("Lamp");
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
        m.yaw = (float) Math.toRadians(cam.getDouble("yaw"));
        m.pitch = (float) Math.toRadians(cam.getDouble("pitch"));
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
        m.wallColor = color(colors, "wall");
        m.wallTop = colors.containsKey("wall-top") ? colors.get("wall-top") : m.wallColor;
        m.wood = color(colors, "wood");
        m.linen = color(colors, "linen");

        JSONArray rooms = j.getJSONArray("rooms");
        Map<String, Integer> roomIndex = new HashMap<>();
        m.roomName = new String[rooms.length()];
        m.roomRect = new float[rooms.length()][];
        m.roomFloor = new int[rooms.length()];
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject r = rooms.getJSONObject(i);
            roomIndex.put(r.getString("id"), i);
            m.roomName[i] = r.getString("name");
            m.roomRect[i] = floats(r.getJSONArray("rect"));
            m.roomFloor[i] = color(colors, "floor-" + r.optString("floor", "wood"));
        }
        JSONArray walls = j.getJSONArray("walls");
        m.walls = new float[walls.length()][];
        for (int i = 0; i < walls.length(); i++) {
            JSONObject wall = walls.getJSONObject(i);
            JSONArray from = wall.getJSONArray("from"), to = wall.getJSONArray("to");
            m.walls[i] = new float[] {(float) from.getDouble(0), (float) from.getDouble(1), (float) to.getDouble(0),
                    (float) to.getDouble(1), (float) wall.getDouble("h")};
        }
        JSONArray furniture = j.getJSONArray("furniture");
        int n = furniture.length();
        m.furniture = new float[n][];
        m.furnitureRoom = new int[n];
        m.furnitureColor = new int[n];
        m.rug = new boolean[n];
        for (int i = 0; i < n; i++) {
            JSONObject f = furniture.getJSONObject(i);
            m.furniture[i] = floats(f.getJSONArray("box"));
            Integer room = roomIndex.get(f.optString("room"));
            m.furnitureRoom[i] = room == null ? -1 : room;
            m.furnitureColor[i] = color(colors, f.optString("color", "wood"));
            m.rug[i] = "rug".equals(f.optString("kind"));
        }
        JSONArray fixtures = j.getJSONArray("fixtures");
        n = fixtures.length();
        m.fixtureId = new String[n];
        m.fixtureName = new String[n];
        m.fixtureRoom = new int[n];
        m.lamp = new boolean[n];
        m.at = new float[n][];
        for (int i = 0; i < n; i++) {
            JSONObject f = fixtures.getJSONObject(i);
            m.fixtureId[i] = f.getString("id");
            m.fixtureName[i] = f.optString("name", "Lightbulb");
            Integer room = roomIndex.get(f.optString("room"));
            m.fixtureRoom[i] = room == null ? 0 : room;
            m.lamp[i] = "lamp".equals(f.optString("kind"));
            m.at[i] = floats(f.getJSONArray("at"));
        }
        return m;
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

    // MARK: - Shapes for this size

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        k = width / 1920f;
        captionPaint = Ui.text(Ui.semibold, 26 * k, 0xD9FFFFFF);
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setShadowLayer(14 * k, 0, 1 * k, 0x80000000);
        build();
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
    private float depth(float x, float y, float z) {
        float dx = x - m.width / 2, dy = y - m.depth / 2;
        return (dx * sinT + dy * cosT) * cosP + z * sinP;
    }

    private void build() {
        if (m == null || w == 0) return;
        sinT = (float) Math.sin(m.yaw);
        cosT = (float) Math.cos(m.yaw);
        sinP = (float) Math.sin(m.pitch);
        cosP = (float) Math.cos(m.pitch);
        // Fit the floor's corners and the walls' tops in camera.box (uniform scale, centred).
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        fit(b, 0, 0, 0);
        fit(b, m.width, 0, 0);
        fit(b, 0, m.depth, 0);
        fit(b, m.width, m.depth, 0);
        for (float[] wall : m.walls) {
            fit(b, wall[0], wall[1], wall[4]);
            fit(b, wall[2], wall[3], wall[4]);
        }
        float bw = (m.box[2] - m.box[0]) * k, bh = (m.box[3] - m.box[1]) * k;
        scale = Math.min(bw / (b[2] - b[0]), bh / (b[3] - b[1]));
        ox = (m.box[0] + m.box[2]) / 2 * k - (b[0] + b[2]) / 2 * scale;
        oy = (m.box[1] + m.box[3]) / 2 * k - (b[1] + b[3]) / 2 * scale;

        List<Path> paths = new ArrayList<>();
        List<int[]> info = new ArrayList<>();      // color, rooms, fixture
        List<Float> shades = new ArrayList<>();
        // Floors, then the rugs on them.
        int rooms = m.roomRect.length;
        roomFloor = new Path[rooms];
        for (int r = 0; r < rooms; r++) {
            float[] rr = m.roomRect[r];
            roomFloor[r] = quad(rr[0], rr[1], rr[2], rr[1], rr[2], rr[3], rr[0], rr[3], 0);
            paths.add(roomFloor[r]);
            info.add(new int[] {m.roomFloor[r], 1 << r, -1});
            shades.add(0f);
        }
        for (int i = 0; i < m.furniture.length; i++) {
            if (!m.rug[i]) continue;
            float[] f = m.furniture[i];
            float z = f[2] + f[5];
            paths.add(quad(f[0], f[1], f[0] + f[3], f[1], f[0] + f[3], f[1] + f[4], f[0], f[1] + f[4], z));
            info.add(new int[] {m.furnitureColor[i], roomMask(m.furnitureRoom[i]), -1});
            shades.add(0f);
        }
        floorFaces = paths.size();

        // Walls (in pieces), furniture and the lights, far to near.
        List<Item> items = new ArrayList<>();
        for (float[] wall : m.walls) {
            boolean alongX = Math.abs(wall[3] - wall[1]) < Math.abs(wall[2] - wall[0]);
            float a0 = alongX ? Math.min(wall[0], wall[2]) : Math.min(wall[1], wall[3]);
            float a1 = alongX ? Math.max(wall[0], wall[2]) : Math.max(wall[1], wall[3]);
            float across = alongX ? wall[1] : wall[0];
            int pieces = Math.max(1, (int) Math.ceil((a1 - a0) / PIECE - 1e-4));
            float step = (a1 - a0) / pieces;
            for (int p = 0; p < pieces; p++) {
                Item it = new Item();
                float s0 = a0 + p * step;
                if (alongX) {
                    it.x = s0;
                    it.y = across - WALL / 2;
                    it.w = step;
                    it.d = WALL;
                } else {
                    it.x = across - WALL / 2;
                    it.y = s0;
                    it.w = WALL;
                    it.d = step;
                }
                it.h = wall[4];
                it.alongX = alongX;
                it.skipLow = p > 0;
                it.skipHigh = p < pieces - 1;
                it.color = m.wallColor;
                it.top = m.wallTop;
                it.topShade = 0;
                it.rooms = roomsAt(it.x + it.w / 2, it.y + it.d / 2);
                it.depth = depth(it.x + it.w / 2, it.y + it.d / 2, 0);
                items.add(it);
            }
        }
        List<Item> furniture = new ArrayList<>();
        for (int i = 0; i < m.furniture.length; i++) {
            if (m.rug[i]) continue;
            float[] f = m.furniture[i];
            Item it = new Item();
            it.x = f[0];
            it.y = f[1];
            it.z = f[2];
            it.w = f[3];
            it.d = f[4];
            it.h = f[5];
            it.color = it.top = m.furnitureColor[i];
            it.topShade = TOP;
            it.rooms = roomMask(m.furnitureRoom[i]);
            it.depth = depth(it.x + it.w / 2, it.y + it.d / 2, it.z);
            furniture.add(it);
        }
        // Something resting on another box (pillows, a monitor, the TV) is drawn after it.
        List<Item> byHeight = new ArrayList<>(furniture);
        Collections.sort(byHeight, (p, q) -> Float.compare(p.z, q.z));
        for (Item it : byHeight) {
            if (it.z <= 0) continue;
            for (Item under : byHeight) {
                if (under == it || Math.abs(under.z + under.h - it.z) > 0.03f) continue;
                if (it.x < under.x + under.w && under.x < it.x + it.w && it.y < under.y + under.d && under.y < it.y + it.d) {
                    it.depth = Math.max(it.depth, under.depth + 0.001f);
                }
            }
        }
        items.addAll(furniture);
        for (int i = 0; i < m.at.length; i++) {
            Item it = new Item();
            it.fixture = i;
            float[] a = m.at[i];
            it.depth = depth(a[0], a[1], a[2]);
            items.add(it);
        }
        Collections.sort(items, (p, q) -> Float.compare(p.depth, q.depth));
        for (Item it : items) {
            if (it.fixture >= 0) {
                paths.add(null);
                info.add(new int[] {0, 0, it.fixture});
                shades.add(0f);
                continue;
            }
            float x0 = it.x, x1 = it.x + it.w, y0 = it.y, y1 = it.y + it.d, z0 = it.z, z1 = it.z + it.h;
            // The vertical faces turned toward the viewer (their normal's rotated y > 0), then the top.
            if (visible(0, -1) && !(it.skipLow && !it.alongX)) side(paths, info, shades, it, x0, y0, x1, y0, z0, z1, FACE_Y);
            if (visible(1, 0) && !(it.skipHigh && it.alongX)) side(paths, info, shades, it, x1, y0, x1, y1, z0, z1, FACE_X);
            if (visible(0, 1) && !(it.skipHigh && !it.alongX)) side(paths, info, shades, it, x1, y1, x0, y1, z0, z1, FACE_Y);
            if (visible(-1, 0) && !(it.skipLow && it.alongX)) side(paths, info, shades, it, x0, y1, x0, y0, z0, z1, FACE_X);
            paths.add(quad(x0, y0, x1, y0, x1, y1, x0, y1, z1));
            info.add(new int[] {it.top, it.rooms, -1});
            shades.add(it.topShade);
        }
        int n = paths.size();
        facePath = paths.toArray(new Path[0]);
        faceColor = new int[n];
        faceRooms = new int[n];
        faceFixture = new int[n];
        faceShade = new float[n];
        for (int i = 0; i < n; i++) {
            int[] f = info.get(i);
            faceColor[i] = f[0];
            faceRooms[i] = f[1];
            faceFixture[i] = f[2];
            faceShade[i] = shades.get(i);
        }
        face.setStrokeWidth(Math.max(1, 0.8f * k));
        buildLights();
        invalidate();
    }

    private void fit(float[] b, float x, float y, float z) {
        raw(x, y, z);
        b[0] = Math.min(b[0], pt[0]);
        b[1] = Math.min(b[1], pt[1]);
        b[2] = Math.max(b[2], pt[0]);
        b[3] = Math.max(b[3], pt[1]);
    }

    private boolean visible(float nx, float ny) {
        return nx * sinT + ny * cosT > 1e-4f;
    }

    private void side(List<Path> paths, List<int[]> info, List<Float> shades, Item it, float ax, float ay, float bx, float by,
            float z0, float z1, float shade) {
        Path p = new Path();
        project(ax, ay, z0);
        p.moveTo(pt[0], pt[1]);
        project(bx, by, z0);
        p.lineTo(pt[0], pt[1]);
        project(bx, by, z1);
        p.lineTo(pt[0], pt[1]);
        project(ax, ay, z1);
        p.lineTo(pt[0], pt[1]);
        p.close();
        paths.add(p);
        info.add(new int[] {it.color, it.rooms, -1});
        shades.add(shade);
    }

    private Path quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float z) {
        Path p = new Path();
        project(x0, y0, z);
        p.moveTo(pt[0], pt[1]);
        project(x1, y1, z);
        p.lineTo(pt[0], pt[1]);
        project(x2, y2, z);
        p.lineTo(pt[0], pt[1]);
        project(x3, y3, z);
        p.lineTo(pt[0], pt[1]);
        p.close();
        return p;
    }

    private int roomMask(int room) {
        return room < 0 ? 0 : 1 << room;
    }

    /** The rooms a wall piece borders (it warms with either). */
    private int roomsAt(float x, float y) {
        int mask = 0;
        for (int r = 0; r < m.roomRect.length; r++) {
            float[] rr = m.roomRect[r];
            if (x >= rr[0] - 0.1f && x <= rr[2] + 0.1f && y >= rr[1] - 0.1f && y <= rr[3] + 0.1f) mask |= 1 << r;
        }
        return mask;
    }

    private void buildLights() {
        int n = m.at.length;
        fixX = new float[n];
        fixY = new float[n];
        cordX = new float[n];
        cordY = new float[n];
        poleX = new float[n];
        poleY = new float[n];
        glowX = new float[n];
        glowY = new float[n];
        shade = new Path[n];
        shadeTop = new RectF[n];
        lampBase = new RectF[n];
        floorGlow = new Shader[n];
        bulbHalo = new Shader[n];
        bulbR = BULB_R * scale;
        haloR = BULB_HALO * scale;
        focusR = FOCUS_HALO * scale;
        glowR = GLOW_R * scale;
        int g = m.glow & 0xFFFFFF;
        for (int i = 0; i < n; i++) {
            float[] a = m.at[i];
            project(a[0], a[1], 0);
            glowX[i] = pt[0];
            glowY[i] = pt[1];
            floorGlow[i] = new RadialGradient(0, 0, glowR, new int[] {0xFF000000 | g, 0x66000000 | g, g},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
            bulbHalo[i] = new RadialGradient(0, 0, haloR, new int[] {0xCC000000 | g, 0x33000000 | g, g},
                    new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
            if (m.lamp[i]) {
                float rb = SHADE_RB * scale, rt = SHADE_RT * scale;
                project(a[0], a[1], 0);
                float br = BASE_R * scale;
                lampBase[i] = new RectF(pt[0] - br, pt[1] - br * sinP, pt[0] + br, pt[1] + br * sinP);
                cordX[i] = pt[0];
                cordY[i] = pt[1];
                project(a[0], a[1], SHADE_BOTTOM);
                float bx = pt[0], by = pt[1];
                project(a[0], a[1], SHADE_BOTTOM + SHADE_H);
                float tx = pt[0], ty = pt[1];
                poleX[i] = bx;
                poleY[i] = by;
                Path p = new Path();
                p.moveTo(tx - rt, ty);
                p.lineTo(bx - rb, by);
                p.arcTo(new RectF(bx - rb, by - rb * sinP, bx + rb, by + rb * sinP), 180, -180, false);
                p.lineTo(tx + rt, ty);
                p.arcTo(new RectF(tx - rt, ty - rt * sinP, tx + rt, ty + rt * sinP), 0, -180, false);
                p.close();
                shade[i] = p;
                shadeTop[i] = new RectF(tx - rt, ty - rt * sinP, tx + rt, ty + rt * sinP);
                fixX[i] = (bx + tx) / 2;
                fixY[i] = (by + ty) / 2;
            } else {
                project(a[0], a[1], CORD_TOP);
                cordX[i] = pt[0];
                cordY[i] = pt[1];
                project(a[0], a[1], a[2]);
                fixX[i] = pt[0];
                fixY[i] = pt[1];
            }
        }
        focusHalo = new RadialGradient(0, 0, focusR, new int[] {0x8CFFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (facePath == null || m == null) return;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        boolean moving = shown.step(dt);
        for (Spring s : lit) moving |= s.step(dt);
        for (Spring s : focus) moving |= s.step(dt);
        for (int r = 0; r < roomGlow.length; r++) roomGlow[r] = 0;
        for (int i = 0; i < lit.length; i++) {
            int r = m.fixtureRoom[i];
            roomGlow[r] = Math.max(roomGlow[r], Ui.clamp(lit[i].value));
        }

        int top = focused || shown.value > 0.01f ? index : -1;   // the focused light is drawn last, above the rest
        for (int i = 0; i < facePath.length; i++) {
            if (i == floorFaces) drawFloorGlows(c);
            int f = faceFixture[i];
            if (f >= 0) {
                if (f != top) drawLight(c, f);
                continue;
            }
            int ci = faceColor[i];
            int col = blend(m.cool[ci], m.warm[ci], glow(faceRooms[i]));
            float s = faceShade[i];
            face.setColor(s >= 0 ? blend(col, 0xFFFFFFFF, s) : blend(col, 0xFF000000, -s));
            c.drawPath(facePath[i], face);
        }
        if (floorFaces >= facePath.length) drawFloorGlows(c);
        if (top >= 0) drawLight(c, top);

        float a = Ui.clamp(shown.value);
        if (a > 0.01f && !caption.isEmpty() && captionPaint != null) {
            captionPaint.setAlpha(Math.round(0xD9 * a));
            c.drawText(caption, w / 2f, CAPTION_Y * k + Ui.halfCap(captionPaint), captionPaint);
        }
        if (moving) postInvalidateOnAnimation();
    }

    private float glow(int rooms) {
        float g = 0;
        for (int r = 0; r < roomGlow.length; r++) if ((rooms & (1 << r)) != 0) g = Math.max(g, roomGlow[r]);
        return g;
    }

    /** Each lit light's warm pool on its room's floor (added light), a circle flattened by the view's pitch. */
    private void drawFloorGlows(Canvas c) {
        for (int i = 0; i < lit.length; i++) {
            float g = Ui.clamp(lit[i].value);
            if (g < 0.004f) continue;
            add.setShader(floorGlow[i]);
            add.setAlpha(Math.round(255 * 0.35f * g));
            c.save();
            c.clipPath(roomFloor[m.fixtureRoom[i]]);
            c.translate(glowX[i], glowY[i]);
            c.scale(1, sinP);
            c.drawCircle(0, 0, glowR, add);
            c.restore();
        }
        add.setShader(null);
    }

    private void drawLight(Canvas c, int i) {
        float g = Ui.clamp(lit[i].value), f = Ui.clamp(focus[i].value), s = 1 + 0.25f * focus[i].value;
        c.save();
        c.scale(s, s, fixX[i], fixY[i]);
        if (f > 0.01f) {
            fill.setShader(focusHalo);
            fill.setAlpha(Math.round(255 * f));
            c.save();
            c.translate(fixX[i], fixY[i]);
            c.drawCircle(0, 0, focusR, fill);
            c.restore();
            fill.setShader(null);
            fill.setAlpha(255);
        }
        if (g > 0.01f) {
            fill.setShader(bulbHalo[i]);
            fill.setAlpha(Math.round(255 * g));
            c.save();
            c.translate(fixX[i], fixY[i]);
            c.drawCircle(0, 0, haloR, fill);
            c.restore();
            fill.setShader(null);
            fill.setAlpha(255);
        }
        if (m.lamp[i]) {
            float room = roomGlow[m.fixtureRoom[i]];
            int wood = blend(blend(m.cool[m.wood], m.warm[m.wood], room), 0xFF000000, 0.12f);
            fill.setColor(wood);
            c.drawOval(lampBase[i], fill);
            line.setColor(wood);
            line.setStrokeWidth(Math.max(1.5f * k, 0.035f * scale));
            c.drawLine(cordX[i], cordY[i], poleX[i], poleY[i], line);
            fill.setColor(blend(m.cool[m.linen], SHADE_ON, g));
            c.drawPath(shade[i], fill);
            fill.setColor(blend(SHADE_IN_OFF, SHADE_IN_ON, g));
            c.drawOval(shadeTop[i], fill);
        } else {
            line.setColor(CORD);
            line.setStrokeWidth(Math.max(1.5f * k, 0.02f * scale));
            c.drawLine(cordX[i], cordY[i], fixX[i], fixY[i] - bulbR, line);
            fill.setColor(blend(BULB_OFF, BULB_ON, g));
            c.drawCircle(fixX[i], fixY[i], bulbR, fill);
            if (g > 0.01f) {
                fill.setColor(Ui.alpha(BULB_CORE, g));
                c.drawCircle(fixX[i], fixY[i], bulbR * 0.55f, fill);
            }
        }
        c.restore();
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
