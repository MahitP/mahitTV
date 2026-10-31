package io.freemytv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Top Shelf never hides the Dock on Android (TVOS-LAUNCHER-MERGE-SPEC.md V1, as tools/home-background-test.js
 * checks on the web): ↑ into it recesses the Dock 36 px down to 0.75, and the status pill and the tab bar stay.
 * Read from HomeView's source: loading HomeView needs Android, which a plain JVM doesn't have.
 */
public final class DockRecessTest {
    private static int checks;

    private static void ok(boolean v, String m) {
        if (!v) throw new AssertionError(m);
        checks++;
    }

    /** The statement that starts with `start` (up to its semicolon). */
    private static String statement(String src, String start) {
        int at = src.indexOf(start);
        ok(at >= 0, "HomeView has " + start);
        return src.substring(at, src.indexOf(';', at));
    }

    public static void main(String[] args) throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("app/src/io/freemytv/HomeView.java")), StandardCharsets.UTF_8);

        ok(Pattern.compile("\\bTV_FULL_DROP = 36\\b").matcher(src).find(), "the Dock recesses 36 px (TV_FULL_DROP = 36)");
        ok(Pattern.compile("\\bTV_RECESS = 0\\.75f\\b").matcher(src).find(), "the Dock recesses to 0.75 (TV_RECESS = 0.75f)");
        ok(src.contains("1 - (1 - TV_RECESS) * smoothstep("), "full screen fades the Dock only down to TV_RECESS");
        ok(src.contains("full.value * TV_FULL_DROP * k"), "full screen moves the Dock by TV_FULL_DROP");

        // The Dock's layers use the recess, nothing that reaches 0 in full screen.
        for (String layer : new String[] {"show(platter,", "show(tiles,"}) {
            String s = statement(src, layer);
            ok(s.contains("away"), layer + " uses the recess (away)");
            ok(!s.contains("1 - fs") && !s.contains("1 - full.value"), layer + " never fades out in full screen");
        }
        // The status pill and the tab bar don't depend on full screen at all.
        for (String layer : new String[] {"show(status,", "show(tabBar,"}) {
            String s = statement(src, layer);
            Matcher m = Pattern.compile("\\bfs\\b|\\bfull\\.value\\b|\\baway\\b").matcher(s);
            ok(!m.find(), layer + " stays in full screen: " + s.trim());
        }
        System.out.println("DockRecessTest: " + checks + " checks passed");
    }
}
