package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moving apps (hold OK on one): the cursor's line between apps and outline on an app, one after the other; what OK does
 * there (place it, make a folder, add it to a folder, swap it with a Dock app); and the layout each profile keeps.
 */
public final class AppLayoutTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void same(Object actual, Object expected, String message) {
        check(expected.equals(actual), message + ": expected " + expected + ", got " + actual);
    }

    private static List<String> list(String... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }

    /** A Dock of six, a grid of 13 (two full rows, then one) with the folder "folder:apple" second, six across. */
    private static AppLayout.Board board() {
        Map<String, List<String>> folders = new LinkedHashMap<>();
        folders.put("folder:apple", list("music", "guide:facetime"));
        Set<String> dockable = new HashSet<>(list("A", "B", "C", "D", "E", "F", "g0", "g2", "g3", "g4", "g5", "g6", "g7", "g8",
                "g9", "g10", "g11", "g12", "music"));
        return new AppLayout.Board(list("A", "B", "C", "D", "E", "F"),
                list("g0", "folder:apple", "g2", "g3", "g4", "g5", "g6", "g7", "g8", "g9", "g10", "g11", "g12"),
                folders, dockable, 6, Integer.MAX_VALUE);
    }

    private static void at(AppLayout.Board b, int row, int pos, String message) {
        check(b.row == row && b.pos == pos, message + ": expected (" + row + ", " + pos + "), got (" + b.row + ", " + b.pos + ")");
    }

    public static void main(String[] args) {
        saved();
        order();
        arrange();
        cursor();
        drops();
        swaps();
        folders();
        limits();
        System.out.println("AppLayoutTest: " + checks + " checks passed");
    }

    /** What a profile keeps: the grid's order and its own folders (name and apps), read back as written. */
    private static void saved() {
        AppLayout l = new AppLayout();
        l.grid.addAll(list("com.a", "folder:u1", "com.b"));
        l.folder("folder:u1", "Movie\tNight\nTwo").apps.addAll(list("com.c", "com.d"));
        AppLayout back = AppLayout.parse(l.encode());
        same(back.grid, list("com.a", "folder:u1", "com.b"), "the grid's order comes back");
        same(back.folders.keySet(), new HashSet<>(list("folder:u1")), "its folder comes back");
        same(back.folders.get("folder:u1").name, "Movie Night Two", "a tab or a line break in a name is a space");
        same(back.folders.get("folder:u1").apps, list("com.c", "com.d"), "the folder's apps, in order");
        check(AppLayout.parse(null).grid.isEmpty() && AppLayout.parse("").folders.isEmpty(), "nothing saved: an empty layout");
        check(AppLayout.parse("nonsense\nmore").grid.isEmpty(), "something unreadable: an empty layout");
        check(new AppLayout().isEmpty() && !l.isEmpty(), "empty until something is saved");
    }

    /** The saved order wins; an app it doesn't know (just installed) follows the app before it in the TV's own order. */
    private static void order() {
        same(AppLayout.order(list("a", "b", "c", "d"), list("d", "c", "b", "a")), list("d", "c", "b", "a"), "the saved order");
        same(AppLayout.order(list("a", "b", "n", "c"), list("c", "a", "b")), list("c", "a", "b", "n"), "new: after its natural neighbour");
        same(AppLayout.order(list("n", "a", "b"), list("b", "a")), list("n", "b", "a"), "new and first: first");
        same(AppLayout.order(list("a", "b"), list("gone", "b", "a")), list("b", "a"), "an uninstalled app's place is forgotten");
        same(AppLayout.order(list("a", "n1", "n2", "b"), list("b", "a")), list("b", "a", "n1", "n2"), "two new ones keep theirs");
        same(AppLayout.order(list("a", "b"), Collections.<String>emptyList()), list("a", "b"), "nothing saved: as the TV has it");
    }

    /** The saved layout over what the TV has: its own folders take their apps, the TV's folders keep the rest. */
    private static void arrange() {
        List<String> natural = list("netflix", "folder:apple", "hulu", "spotify", "folder:tcl");
        Map<String, List<String>> auto = new HashMap<>();
        auto.put("folder:apple", list("music", "guide:facetime", "guide:fitness"));
        auto.put("folder:tcl", list("t1", "t2", "t3"));
        AppLayout l = new AppLayout();
        l.folder("folder:u1", "Watch").apps.addAll(list("hulu", "t1", "gone"));
        Map<String, List<String>> out = new HashMap<>();
        List<String> grid = l.arrange(natural, auto, Collections.<String>emptySet(), out);
        same(grid, list("netflix", "folder:apple", "spotify", "folder:tcl", "folder:u1"), "a new folder goes last until moved");
        same(out.get("folder:u1"), list("hulu", "t1"), "its apps, in its order (an uninstalled one skipped)");
        same(out.get("folder:tcl"), list("t2", "t3"), "the TV's folder keeps the others");
        same(out.get("folder:apple"), list("music", "guide:facetime", "guide:fitness"), "an untouched folder as it is");

        // A TV folder the person changed is theirs now: the apps they took out stay out.
        l.folder("folder:apple", "Apple").apps.addAll(list("music", "guide:fitness"));
        l.grid.addAll(list("folder:u1", "netflix", "folder:apple", "guide:facetime", "spotify", "folder:tcl"));
        out.clear();
        grid = l.arrange(natural, auto, Collections.<String>emptySet(), out);
        same(grid, list("folder:u1", "netflix", "folder:apple", "guide:facetime", "spotify", "folder:tcl"), "the saved order");
        same(out.get("folder:apple"), list("music", "guide:fitness"), "a changed TV folder: what the person left in it");

        // The Dock's apps are never in the grid too; a folder down to one app is that app, in its place.
        out.clear();
        grid = l.arrange(natural, auto, new HashSet<>(list("hulu", "spotify")), out);
        same(grid, list("t1", "netflix", "folder:apple", "guide:facetime", "folder:tcl"), "one app left: that app; none from the Dock");
        check(!out.containsKey("folder:u1"), "a folder of one is not a folder");
        AppLayout two = new AppLayout();
        two.folder("folder:tcl", "TCL").apps.addAll(list("t1"));
        out.clear();
        grid = two.arrange(natural, auto, Collections.<String>emptySet(), out);
        same(grid, list("netflix", "folder:apple", "hulu", "spotify", "t1", "t2", "t3"), "a TV folder emptied to one: its apps loose, where it was");
    }

    /** Line, outline, line, outline: ← → step through both, ↑ ↓ keep the kind, rows wrap without a repeated line. */
    private static void cursor() {
        AppLayout.Board b = board();
        check(b.lift(AppLayout.Board.FROM_DOCK, 2, null), "a Dock app lifts");
        same(b.dock, list("A", "B", "D", "E", "F"), "the Dock closes up behind it");
        at(b, AppLayout.Board.DOCK, 4, "the cursor starts on the line where it was");
        same(b.action(), AppLayout.Board.NONE, "OK there changes nothing");
        check(b.move(1, 0), "→");
        at(b, AppLayout.Board.DOCK, 5, "→ outlines the next app");
        same(b.target(), "D", "the outlined app");
        check(b.move(1, 0), "→");
        at(b, AppLayout.Board.DOCK, 6, "→ the next line");
        same(b.before(), "D", "the app before the line");
        same(b.after(), "E", "the app after the line");
        for (int i = 0; i < 4; i++) b.move(1, 0);
        at(b, AppLayout.Board.DOCK, 10, "the line after the last app");
        check(b.after() == null && "F".equals(b.before()), "nothing after it");
        check(!b.move(1, 0), "the Dock's end: a dead end");
        check(b.move(0, 1), "↓ from the Dock: the grid");
        at(b, 0, 10, "the same line in the grid's first row (gap 5)");
        check(b.move(0, -1), "↑ back");
        at(b, AppLayout.Board.DOCK, 10, "the Dock again");
        check(!b.move(0, -1), "nothing above the Dock");
        b.move(-1, 0);
        at(b, AppLayout.Board.DOCK, 9, "← outlines the last app");
        b.move(0, 1);
        at(b, 0, 9, "↓ keeps the outline (the app below)");
        same(b.target(), "g4", "outlining g4");

        // Rows wrap like text: the line at a row's end is the next row's start, so it's shown once.
        for (int i = 0; i < 3; i++) b.move(1, 0);
        at(b, 0, 12, "the line at the end of the first row");
        same(b.insertion(), 6, "it goes in 7th");
        b.move(1, 0);
        at(b, 1, 1, "→ again: the next row's first app (no second line)");
        b.move(-1, 0);
        at(b, 1, 0, "← from it: the line before it");
        same(b.insertion(), 6, "the same place as the end of the row above");
        b.move(-1, 0);
        at(b, 0, 11, "← again: the row above's last app");
        b.move(0, 1);
        b.move(0, 1);
        at(b, 2, 1, "↓ onto a shorter row: its last app");
        check(!b.move(0, 1), "nothing below the last row");
        check(!b.move(-1, 0) || b.row == 2, "still the last row");
    }

    /** OK: in place, in a new folder, into a folder; and what each says it does before it's pressed. */
    private static void drops() {
        AppLayout.Board b = board();
        b.lift(AppLayout.Board.FROM_GRID, 3, null);   // g3
        same(b.grid.indexOf("g3"), -1, "lifted: out of the grid");
        at(b, 0, 6, "on the line where it was");
        same(b.action(), AppLayout.Board.NONE, "OK there: nothing changes");
        b.move(0, 1);
        at(b, 1, 6, "↓ the line in the row below");
        same(b.action(), AppLayout.Board.PLACE, "OK places it");
        same(b.drop("folder:new"), AppLayout.Board.PLACE, "placed");
        same(b.grid, list("g0", "folder:apple", "g2", "g4", "g5", "g6", "g7", "g8", "g9", "g3", "g10", "g11", "g12"), "between g9 and g10");
        check(b.lifted == null, "nothing lifted any more");

        b = board();
        b.lift(AppLayout.Board.FROM_GRID, 3, null);
        b.move(1, 0);
        same(b.target(), "g4", "outlining g4");
        same(b.action(), AppLayout.Board.NEW_FOLDER, "OK on an app: a new folder");
        same(b.drop("folder:u9"), AppLayout.Board.NEW_FOLDER, "a folder made");
        same(b.newFolder, "folder:u9", "its key");
        same(b.grid, list("g0", "folder:apple", "g2", "folder:u9", "g5", "g6", "g7", "g8", "g9", "g10", "g11", "g12"), "in g4's place");
        same(b.folders.get("folder:u9"), list("g4", "g3"), "the outlined app first, then the one moved");
        check(b.touched.contains("folder:u9"), "a new folder is saved");

        b = board();
        b.lift(AppLayout.Board.FROM_GRID, 0, null);   // g0; the folder is first now
        b.move(1, 0);
        same(b.target(), "folder:apple", "outlining the folder");
        same(b.action(), AppLayout.Board.ADD, "OK on a folder: into it");
        b.drop("folder:x");
        same(b.folders.get("folder:apple"), list("music", "guide:facetime", "g0"), "added last");
        check(b.touched.contains("folder:apple") && !b.folders.containsKey("folder:x"), "only that folder changed");

        // From the grid up into the Dock.
        b = board();
        b.lift(AppLayout.Board.FROM_GRID, 2, null);   // g2
        b.move(0, -1);
        at(b, AppLayout.Board.DOCK, 4, "↑ the Dock's line above");
        same(b.drop("folder:x"), AppLayout.Board.PLACE, "into the Dock");
        same(b.dock, list("A", "B", "g2", "C", "D", "E", "F"), "between B and C");
        check(!b.grid.contains("g2"), "and out of the grid");

        // Out of the Dock into the grid.
        b = board();
        b.lift(AppLayout.Board.FROM_DOCK, 0, null);
        b.move(0, 1);
        b.drop("folder:x");
        same(b.dock, list("B", "C", "D", "E", "F"), "off the Dock");
        same(b.grid.get(0), "A", "first in the grid");
    }

    /** OK on a Dock app: the two swap places (the Dock app goes where the other came from). */
    private static void swaps() {
        AppLayout.Board b = board();
        b.lift(AppLayout.Board.FROM_DOCK, 2, null);   // C
        b.move(1, 0);
        b.move(1, 0);
        b.move(1, 0);                                    // outline E
        same(b.target(), "E", "outlining E");
        same(b.action(), AppLayout.Board.SWAP, "OK on a Dock app: swap");
        b.drop("folder:x");
        same(b.dock, list("A", "B", "E", "D", "C", "F"), "C and E swapped");

        b = board();
        b.lift(AppLayout.Board.FROM_GRID, 2, null);   // g2
        b.move(0, -1);
        b.move(1, 0);
        same(b.target(), "C", "outlining C from the grid");
        b.drop("folder:x");
        same(b.dock, list("A", "B", "g2", "D", "E", "F"), "g2 in C's place");
        same(b.grid.get(2), "C", "C in g2's");

        b = board();
        b.lift(AppLayout.Board.FROM_FOLDER, 0, "folder:apple");   // music, out of the folder
        at(b, 0, 4, "from a folder: the line after it");
        same(b.action(), AppLayout.Board.PLACE, "placing it out of the folder");
        b.move(0, -1);
        b.move(-1, 0);
        same(b.target(), "B", "outlining B");
        b.drop("folder:x");
        same(b.dock.get(1), "music", "music in the Dock");
        same(b.grid.indexOf("folder:apple"), -1, "the folder, down to one app, is that app");
        same(b.grid.get(1), "guide:facetime", "its last app where the folder was");
        same(b.grid.get(2), "B", "B after it: out of a folder, the Dock app goes beside it, not in it");
        check(!b.folders.containsKey("folder:apple") && b.dissolved.contains("folder:apple"), "the folder is gone");
    }

    /** A folder moves only between apps and never to the Dock; out of a folder its others stay together. */
    private static void folders() {
        AppLayout.Board b = board();
        b.lift(AppLayout.Board.FROM_GRID, 1, null);   // the folder
        at(b, 0, 2, "the line where the folder was");
        b.move(1, 0);
        at(b, 0, 4, "→ skips the outline: folders go only between apps");
        check(!b.move(0, -1), "↑ a folder stays out of the Dock");
        b.drop("folder:x");
        same(b.grid.subList(0, 3), list("g0", "g2", "folder:apple"), "moved one place on");

        Map<String, List<String>> three = new LinkedHashMap<>();
        three.put("folder:apple", list("music", "guide:facetime", "guide:fitness"));
        b = new AppLayout.Board(list("A"), list("g0", "folder:apple"), three, new HashSet<>(list("A", "g0", "music")), 6, 6);
        check(!b.lift(AppLayout.Board.FROM_FOLDER, 1, "folder:apple") || b.lifted.equals("guide:facetime"), "a guide lifts");
        check(!b.move(0, -1), "a guide (no app of its own) stays out of the Dock");
        same(b.folders.get("folder:apple"), list("music", "guide:fitness"), "the folder without it, while it moves");
        b.drop("folder:x");
        check(b.touched.contains("folder:apple"), "a folder an app left is saved as it is now");
        same(b.grid, list("g0", "folder:apple", "guide:facetime"), "after the folder");
    }

    /** A full Dock only swaps; the Dock keeps one app; the folder's own app back in it. */
    private static void limits() {
        Map<String, List<String>> none = new LinkedHashMap<>();
        AppLayout.Board b = new AppLayout.Board(list("A", "B", "C"), list("g0", "g1", "g2"), none,
                new HashSet<>(list("A", "B", "C", "g0", "g1", "g2")), 6, 3);
        b.lift(AppLayout.Board.FROM_GRID, 1, null);
        b.move(0, -1);
        check(b.pos % 2 == 1, "a full Dock: ↑ lands on an app (swap), not between");
        b.move(1, 0);
        check(b.pos % 2 == 1, "→ goes app to app");
        b.move(1, 0);
        check(b.pos % 2 == 1 && b.pos == 5, "to the last");
        check(!b.move(1, 0), "and no line after it");

        b = new AppLayout.Board(list("A"), list("g0"), none, new HashSet<>(list("A", "g0")), 6, 6);
        b.lift(AppLayout.Board.FROM_DOCK, 0, null);
        check(!b.move(0, 1), "the Dock's only app can't leave it");
        same(b.drop("folder:x"), AppLayout.Board.NONE, "OK: where it was");
        same(b.dock, list("A"), "back in the Dock");

        b = board();
        b.lift(AppLayout.Board.FROM_GRID, 12, null);   // g12, alone on the last row
        at(b, 1, 12, "the last app's line: the end of the row above (its row is gone)");
        same(b.action(), AppLayout.Board.NONE, "where it was");
        check(b.rows() == 2, "two rows while it's lifted");
    }
}
