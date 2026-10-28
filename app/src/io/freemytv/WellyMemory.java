package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What Welly remembers (WELLY-HOME-REMOTE-SPEC.md §3.2): the last 20 turns and a short list of facts, per profile, in
 * the app's private files. It never leaves the TV except inside the prompt sent to the brain in use.
 */
final class WellyMemory {
    private static final int TURNS = 20, FACTS = 30;

    private final File file;   // null for Guest: kept in memory only, gone with the profile
    private final List<String[]> turns = new ArrayList<>();   // {role, text}: "user" or "assistant"
    private final List<String> facts = new ArrayList<>();

    /** The profile's memory, in files/welly/<id>.json (MAHITTV-PROFILES-SPEC.md §4); Guest's lives only in memory. */
    WellyMemory(Context c, ProfileManager.Profile p) {
        if (p.guest()) {
            file = null;
            return;
        }
        File dir = new File(c.getFilesDir(), "welly");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, safe(p.id) + ".json");
        // Before profiles had ids, the memory was named after the person: it becomes theirs once.
        File old = new File(dir, safe(p.name) + ".json");
        if (!file.exists() && !old.equals(file) && old.exists() && !old.renameTo(file)) {
            AppLog.w("Welly memory: couldn't move " + old.getName());
        }
        load();
    }

    private static String safe(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]", "_");
        return n.isEmpty() ? "me" : n;
    }

    /** Forgets everything Welly remembers about `p` (Settings → Welly; MAHITTV-PROFILES-SPEC.md §6). */
    static void clear(Context c, ProfileManager.Profile p) {
        File dir = new File(c.getFilesDir(), "welly");
        for (String name : new String[] {p.id, p.name}) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, safe(name) + ".json").delete();
        }
    }

    synchronized List<String[]> turns() {
        return new ArrayList<>(turns);
    }

    synchronized List<String> facts() {
        return new ArrayList<>(facts);
    }

    synchronized void addTurn(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        turns.add(new String[] {role, text.trim()});
        while (turns.size() > TURNS) turns.remove(0);
        save();
    }

    synchronized void addFact(String fact) {
        if (fact == null || fact.trim().isEmpty()) return;
        String f = fact.trim();
        facts.remove(f);
        facts.add(f);
        while (facts.size() > FACTS) facts.remove(0);
        save();
    }

    /** "Welly, forget that": the newest fact goes. Returns it, or null when there was none. */
    synchronized String forgetLast() {
        if (facts.isEmpty()) return null;
        String f = facts.remove(facts.size() - 1);
        save();
        return f;
    }

    private void load() {
        if (file == null || !file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            JSONObject o = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            JSONArray t = o.optJSONArray("turns");
            for (int i = 0; t != null && i < t.length(); i++) {
                JSONObject e = t.optJSONObject(i);
                if (e != null) turns.add(new String[] {e.optString("role"), e.optString("text")});
            }
            JSONArray f = o.optJSONArray("facts");
            for (int i = 0; f != null && i < f.length(); i++) facts.add(f.optString(i));
        } catch (Exception e) {
            AppLog.w("Welly memory: " + e.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray t = new JSONArray();
            for (String[] e : turns) t.put(new JSONObject().put("role", e[0]).put("text", e[1]));
            o.put("turns", t);
            o.put("facts", new JSONArray(facts));
            File part = new File(file.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(file)) AppLog.w("Welly memory: couldn't save");
        } catch (Exception e) {
            AppLog.w("Welly memory: " + e.getMessage());
        }
    }
}
