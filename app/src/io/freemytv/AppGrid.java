package io.freemytv;

import java.util.regex.Pattern;

/** Where each app goes in All Apps: the grid's order, then which folder. Apart from HomeApps so it can be tested without a TV. */
final class AppGrid {
    private AppGrid() {}

    /** How many across an open folder shows its apps, so no app sits alone on a row: 2×2, 3×2, 4×2, 3×3, 4×3. Past 12, 3 across and a scroll. */
    static int folderColumns(int n) {
        if (n == 3 || n == 5 || n == 6 || n == 9) return 3;
        if (n <= 4) return 2;
        return n <= 12 ? 4 : 3;
    }

    /** Where a press takes the focus in an open folder, or -1 for nowhere. Down onto a shorter row lands on its last app. */
    static int folderMove(int from, int dx, int dy, int n, int columns) {
        int next = from + dx + dy * columns;
        if (dy > 0 && next >= n && from / columns < (n - 1) / columns) next = n - 1;
        if (next < 0 || next >= n || (dx != 0 && next / columns != from / columns)) return -1;
        return next;
    }

    /**
     * All Apps' order after the Dock (by package, else by the name the TV shows, so a renamed or regional build still
     * lands in place); the rest follow by name. An app here is never hidden, and goes in a folder only from TCL_FIRST on.
     */
    private static final Pattern[][] GRID_ORDER = {
        order("com\\.apple\\.android\\.music", "^apple music$"),
        order("youtube\\.tvmusic", "^youtube music$"),
        order("^com\\.spotify\\.", "^spotify$"),
        order("^com\\.disney\\.disneyplus", "^disney"),
        order("^com\\.tubitv", "^tubi$"),
        order("youtube\\.tvunplugged", "^youtube tv$"),
        order("twitch", "^twitch$"),
        order("^com\\.hulu\\.", "^hulu$"),
        order("^com\\.wbd\\.stream$|hbonow|hbomax", "^(hbo )?max$"),
        order("peacock", "^peacock"),
        order("^com\\.cbs\\.|paramount", "^paramount"),
        order("^com\\.instagram\\.", "^instagram$"),
        order("^com\\.roblox\\.", "^roblox$"),
        // From here (TCL_FIRST) TCL's apps, in the TCL folder in this order.
        order("alexa", "alexa"),
        order("(?i)media_?center|mediaplayer|videoplayer", "^media ?player$"),
        order("instructions|userguide|manual", "^user ?guide$"),
        order("quickpanel|com\\.tcl\\.suspension", "^quick ?panel$"),
        order("t_?solo", "^t-?solo$"),
        order("^com\\.tcl\\.dashboard$|tclhome", "^tcl home$"),
        order("exhibit", "^t-?exhibition$"),
        // From here (TV_FIRST) what's for watching and the TV's general tools, first in TV & Utilities.
        order("^com\\.tcl\\.tv$", "^(live )?tv$"),
        order("tclplus|com\\.tcl\\.tv\\.plus|com\\.tcl\\.vod|waterfall", "^tcl ?tv\\+?$|^tcl channel$"),
        order("freeplay", "freeplay"),
        order("(?i)browse", "(?i)browse"),
    };

    /** GRID_ORDER's first TCL app (Works with Alexa): it and the ones after it go in the TCL folder. */
    private static final int TCL_FIRST = 13, TV_FIRST = 20;
    /** A browser (TCL's BrowseHere): TV & Utilities', not the TCL folder's. */
    private static final Pattern BROWSER = Pattern.compile("browse", Pattern.CASE_INSENSITIVE);

    /**
     * One of TCL's utilities, for the TCL folder: GRID_ORDER's last ones, or another com.tcl app it doesn't place (TCL's
     * browser goes in TV & Utilities).
     */
    static boolean tclApp(String pkg, String label) {
        int r = gridRank(pkg, label);
        if (r < GRID_ORDER.length) return r >= TCL_FIRST && r < TV_FIRST;
        boolean browser = (pkg != null && BROWSER.matcher(pkg).find()) || (label != null && BROWSER.matcher(label).find());
        return pkg != null && pkg.startsWith("com.tcl.") && !browser;
    }

    /** Live TV, TCL TV+ and the like, and the browser: first in TV & Utilities (GRID_ORDER from TV_FIRST). */
    static boolean tvUtility(String pkg, String label) {
        int r = gridRank(pkg, label);
        return r >= TV_FIRST && r < GRID_ORDER.length;
    }

    private static Pattern[] order(String pkg, String label) {
        return new Pattern[] {Pattern.compile(pkg), Pattern.compile(label, Pattern.CASE_INSENSITIVE)};
    }

    /** Where an app goes in All Apps (GRID_ORDER's index), or GRID_ORDER.length when it isn't listed. */
    static int gridRank(String pkg, String label) {
        for (int i = 0; i < GRID_ORDER.length; i++) {
            if ((pkg != null && GRID_ORDER[i][0].matcher(pkg).find()) || (label != null && GRID_ORDER[i][1].matcher(label.trim()).find())) return i;
        }
        return GRID_ORDER.length;
    }

    /** gridRank's answer for an app GRID_ORDER doesn't place. */
    static final int UNPLACED = GRID_ORDER.length;
}
