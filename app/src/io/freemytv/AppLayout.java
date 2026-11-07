package io.freemytv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a profile has put its apps in All Apps: the grid's order and the folders it made or changed (by key: an app's
 * package, a folder's "folder:…", a guide's "guide:…"). Apart from HomeApps so it can be tested without a TV.
 */
final class AppLayout {
    /** A folder the person made, or one of the TV's they changed: its name and its apps in order. */
    static final class Folder {
        final String key;
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String key, String name) {
            this.key = key;
            this.name = clean(name);
        }
    }

    final List<String> grid = new ArrayList<>();
    final LinkedHashMap<String, Folder> folders = new LinkedHashMap<>();

    boolean isEmpty() {
        return grid.isEmpty() && folders.isEmpty();
    }

    /** The folder with this key (made if it isn't there), named `name`. */
    Folder folder(String key, String name) {
        Folder f = folders.get(key);
        if (f == null) folders.put(key, f = new Folder(key, name));
        else f.name = clean(name);
        return f;
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    // MARK: - Saved as text: "g" then the grid's keys; "f", key, name, then its apps; tab-separated, one per line

    String encode() {
        StringBuilder s = new StringBuilder("v1");
        s.append("\ng");
        for (String k : grid) s.append('\t').append(k);
        for (Folder f : folders.values()) {
            s.append("\nf\t").append(f.key).append('\t').append(f.name);
            for (String a : f.apps) s.append('\t').append(a);
        }
        return s.toString();
    }

    static AppLayout parse(String text) {
        AppLayout l = new AppLayout();
        if (text == null || !text.startsWith("v1")) return l;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p[0].equals("g")) {
                for (int i = 1; i < p.length; i++) if (!p[i].isEmpty()) l.grid.add(p[i]);
            } else if (p[0].equals("f") && p.length >= 3 && !p[1].isEmpty()) {
                Folder f = l.folder(p[1], p[2]);
                for (int i = 3; i < p.length; i++) if (!p[i].isEmpty()) f.apps.add(p[i]);
            }
        }
        return l;
    }

    // MARK: - The layout over what the TV has

    /**
     * `natural` in the saved order: the keys `saved` names keep its order (the others it names are gone); one it doesn't
     * name (an app just installed) follows the one before it in `natural`, or goes first.
     */
    static List<String> order(List<String> natural, List<String> saved) {
        Set<String> there = new HashSet<>(natural), placed = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String k : saved) if (there.contains(k) && placed.add(k)) out.add(k);
        String before = null;
        for (String k : natural) {
            if (placed.add(k)) out.add(before == null ? 0 : out.indexOf(before) + 1, k);
            before = k;
        }
        return out;
    }

    /**
     * The grid with this layout: `natural` is All Apps as HomeApps makes it (apps and the TV's own folders, whose apps
     * are in `auto`), `dock` what the Dock shows (never in the grid as well). The person's folders take their apps; a
     * TV folder they changed is theirs (the apps they took out stay out); a folder down to one app is that app, in its
     * place. Returns the grid's keys in order, and each folder's apps in `out`.
     */
    List<String> arrange(List<String> natural, Map<String, List<String>> auto, Collection<String> dock,
                         Map<String, List<String>> out) {
        Set<String> all = new HashSet<>();
        for (String k : natural) {
            List<String> kids = auto.get(k);
            if (kids != null) all.addAll(kids);
            else all.add(k);
        }
        all.removeAll(dock);
        Set<String> claimed = new HashSet<>();
        Map<String, List<String>> mine = new LinkedHashMap<>();
        for (Folder f : folders.values()) {
            List<String> kids = new ArrayList<>();
            for (String a : f.apps) if (all.contains(a) && claimed.add(a)) kids.add(a);
            mine.put(f.key, kids);
        }
        List<String> entries = new ArrayList<>();
        Map<String, String> single = new HashMap<>();   // a folder down to one app → that app
        for (String k : natural) {
            List<String> kids = auto.get(k);
            if (kids == null) {
                if (!claimed.contains(k) && !dock.contains(k)) entries.add(k);
                continue;
            }
            List<String> left = new ArrayList<>();
            for (String a : kids) if (!claimed.contains(a) && !dock.contains(a)) left.add(a);
            if (mine.containsKey(k)) {   // the person's version in its place; what they took out of it, loose after it
                entries.add(k);
                entries.addAll(left);
            } else if (left.size() >= 2) {
                entries.add(k);
                out.put(k, left);
            } else if (left.size() == 1) {
                entries.add(left.get(0));
                single.put(k, left.get(0));
            }
        }
        for (String k : mine.keySet()) if (!entries.contains(k)) entries.add(k);   // a new folder: last, until moved
        List<String> grid = new ArrayList<>();
        for (String k : entries) {
            List<String> kids = mine.get(k);
            if (kids == null) {
                grid.add(k);
            } else if (kids.size() >= 2) {
                grid.add(k);
                out.put(k, kids);
            } else if (kids.size() == 1) {
                grid.add(kids.get(0));
                single.put(k, kids.get(0));
            }
        }
        List<String> saved = new ArrayList<>(this.grid);
        for (int i = 0; i < saved.size(); i++) {
            String app = single.get(saved.get(i));
            if (app != null && !this.grid.contains(app)) saved.set(i, app);
        }
        return order(grid, saved);
    }

    // MARK: - Moving one app (hold OK on it)

    /**
     * The Dock and the grid while something is moved: it's out of its place, and a cursor goes through the places it
     * can go, a line between two apps (even `pos`: the gap pos / 2) and then an outline on an app (odd: the app
     * (pos - 1) / 2), in rows: the Dock (DOCK), then the grid's rows of `columns`.
     */
    static final class Board {
        static final int DOCK = -1;
        static final int FROM_DOCK = 1, FROM_GRID = 2, FROM_FOLDER = 3;
        /** What OK does at the cursor. */
        static final int NONE = 0, PLACE = 1, NEW_FOLDER = 2, ADD = 3, SWAP = 4;

        final List<String> dock, grid;
        final Map<String, List<String>> folders;
        final Set<String> dockable;
        final int columns, dockMax;
        /** Folders whose apps changed (they're saved as they are now); folders down to one app or none (gone). */
        final Set<String> touched = new HashSet<>(), dissolved = new HashSet<>();
        String lifted, fromFolder, newFolder;
        int from, fromIndex;
        int row, pos;

        Board(List<String> dock, List<String> grid, Map<String, List<String>> folders, Set<String> dockable, int columns,
              int dockMax) {
            this.dock = dock;
            this.grid = grid;
            this.folders = folders;
            this.dockable = dockable;
            this.columns = columns;
            this.dockMax = dockMax;
        }

        boolean isFolder(String key) {
            return key != null && folders.containsKey(key);
        }

        /** Takes the item out of its place (`index` in the Dock, the grid, or the folder) and puts the cursor there. */
        boolean lift(int zone, int index, String folder) {
            if (lifted != null) return false;
            List<String> list = zone == FROM_DOCK ? dock : zone == FROM_GRID ? grid : folders.get(folder);
            if (list == null || index < 0 || index >= list.size()) return false;
            lifted = list.remove(index);
            from = zone;
            fromIndex = index;
            fromFolder = zone == FROM_FOLDER ? folder : null;
            if (zone == FROM_FOLDER) touched.add(folder);
            if (zone == FROM_DOCK) {
                row = DOCK;
                pos = 2 * index;
            } else {
                cursorAt(zone == FROM_GRID ? index : grid.indexOf(folder) + 1);
            }
            return true;
        }

        /** The cursor on the line where grid index `at` would go (past the last row: at that row's end). */
        private void cursorAt(int at) {
            row = at / columns;
            pos = 2 * (at % columns);
            if (row >= rows()) {
                row = rows() - 1;
                pos = 2 * count(row);
            }
        }

        int rows() {
            return Math.max(1, (grid.size() + columns - 1) / columns);
        }

        int count(int r) {
            if (r == DOCK) return dock.size();
            return Math.max(0, Math.min(columns, grid.size() - r * columns));
        }

        /** The item `i` of row `r`. */
        String at(int r, int i) {
            return r == DOCK ? dock.get(i) : grid.get(r * columns + i);
        }

        /** Where a line at the cursor puts it: its index in the Dock, or in the grid. */
        int insertion() {
            return row == DOCK ? pos / 2 : row * columns + pos / 2;
        }

        /** The outlined item, or null on a line. */
        String target() {
            return pos % 2 == 1 ? at(row, (pos - 1) / 2) : null;
        }

        /** On a line: the item before it in its row (null at the start). */
        String before() {
            return pos % 2 == 0 && pos > 0 ? at(row, pos / 2 - 1) : null;
        }

        /** On a line: the item after it in its row (null at the end). */
        String after() {
            return pos % 2 == 0 && pos / 2 < count(row) ? at(row, pos / 2) : null;
        }

        private boolean valid(int r, int p) {
            if (p < 0 || p > 2 * count(r)) return false;
            if (r == DOCK) {
                if (!dockable.contains(lifted)) return false;   // a folder, a guide: not in the Dock
                return p % 2 == 1 || dock.size() < dockMax || from == FROM_DOCK;   // a full Dock only swaps
            }
            if (from == FROM_DOCK && dock.isEmpty()) return false;   // the Dock keeps one app
            return p % 2 == 0 || !isFolder(lifted);   // a folder goes only between apps
        }

        /** An arrow: true if the cursor moved. */
        boolean move(int dx, int dy) {
            if (lifted == null) return false;
            if (dx > 0) return right();
            if (dx < 0) return left();
            return dy != 0 && vertical(dy);
        }

        private boolean right() {
            int r = row, p = pos;
            while (true) {
                p++;
                if (p > 2 * count(r)) {
                    // A row's end line is the next row's start: from it, straight to that row's first app.
                    if (r == DOCK || r + 1 >= rows()) return false;
                    r++;
                    p = 1;
                }
                if (valid(r, p)) return set(r, p);
            }
        }

        private boolean left() {
            int r = row, p = pos;
            while (true) {
                p--;
                if (p < 0) {
                    if (r == DOCK || r == 0) return false;
                    r--;
                    p = 2 * count(r) - 1;
                }
                if (valid(r, p)) return set(r, p);
            }
        }

        /** ↑ ↓: the same kind of place in the row above or below (its last app on a shorter row), else the nearest. */
        private boolean vertical(int dy) {
            int r;
            if (dy < 0) {
                if (row == DOCK) return false;
                r = row == 0 ? DOCK : row - 1;
            } else {
                if (row != DOCK && row + 1 >= rows()) return false;
                r = row == DOCK ? 0 : row + 1;
            }
            int c = count(r);
            int p = pos % 2 == 1 ? (c == 0 ? 0 : 2 * Math.min((pos - 1) / 2, c - 1) + 1) : 2 * Math.min(pos / 2, c);
            for (int d = 0; d <= 2 * c; d++) {
                if (valid(r, p - d)) return set(r, p - d);
                if (valid(r, p + d)) return set(r, p + d);
            }
            return false;
        }

        private boolean set(int r, int p) {
            row = r;
            pos = p;
            return true;
        }

        /** What OK does here. */
        int action() {
            if (lifted == null) return NONE;
            if (row == DOCK) {
                if (pos % 2 == 1) return SWAP;
                return from == FROM_DOCK && pos / 2 == fromIndex ? NONE : PLACE;
            }
            if (pos % 2 == 1) return isFolder(target()) ? ADD : NEW_FOLDER;
            return from == FROM_GRID && insertion() == fromIndex ? NONE : PLACE;
        }

        /** OK: puts it where the cursor is (a new folder gets `folderKey`). Returns what it did. */
        int drop(String folderKey) {
            int kind = action();
            switch (kind) {
                case PLACE:
                    (row == DOCK ? dock : grid).add(insertion(), lifted);
                    break;
                case NEW_FOLDER: {
                    int t = row * columns + (pos - 1) / 2;
                    List<String> apps = new ArrayList<>();
                    apps.add(grid.get(t));
                    apps.add(lifted);
                    folders.put(folderKey, apps);
                    grid.set(t, folderKey);
                    touched.add(folderKey);
                    newFolder = folderKey;
                    break;
                }
                case ADD:
                    folders.get(target()).add(lifted);
                    touched.add(target());
                    break;
                case SWAP: {
                    int i = (pos - 1) / 2;
                    String out = dock.set(i, lifted);
                    if (from == FROM_DOCK) dock.add(fromIndex, out);
                    else if (from == FROM_GRID) grid.add(fromIndex, out);
                    else grid.add(grid.indexOf(fromFolder) + 1, out);   // out of a folder: beside it, not in it
                    break;
                }
                default:
                    putBack();
            }
            lifted = null;
            tidy();
            return kind;
        }

        private void putBack() {
            List<String> list = from == FROM_DOCK ? dock : from == FROM_GRID ? grid : folders.get(fromFolder);
            list.add(Math.min(fromIndex, list.size()), lifted);
        }

        /** A folder down to one app is that app, in its place; an empty one goes. */
        private void tidy() {
            for (int i = grid.size() - 1; i >= 0; i--) {
                List<String> apps = folders.get(grid.get(i));
                if (apps == null || apps.size() >= 2) continue;
                String key = grid.get(i);
                folders.remove(key);
                touched.remove(key);
                dissolved.add(key);
                if (apps.isEmpty()) grid.remove(i);
                else grid.set(i, apps.get(0));
            }
        }
    }
}
