package io.freemytv;

/** Where this TV's apps go in All Apps: TCL's own utilities in the TCL folder, watching and system apps in TV & Utilities. */
public final class FolderSortTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String[][] tcl = {{"com.tcl.smartalexa", "Works with Alexa"}, {"com.tcl.ui_mediaCenter", "Media Player"},
            {"com.tcl.ocean.instructions", "User Guide"}, {"com.tcl.suspension", "Quick Panel"}, {"com.tcl.t_solo", "T-Solo"},
            {"com.tcl.dashboard", "TCL Home"}, {"com.tcl.exhibit", "T-Exhibition"}};
        int last = -1;
        for (String[] a : tcl) {
            check(AppGrid.tclApp(a[0], a[1]), a[1] + ": the TCL folder");
            check(!AppGrid.tvUtility(a[0], a[1]), a[1] + ": only there");
            int rank = AppGrid.gridRank(a[0], a[1]);
            check(rank > last, a[1] + ": in the folder's order");
            last = rank;
        }
        String[][] tv = {{"com.tcl.tv", "TV"}, {"com.tcl.tv.plus", "TCL TV+"}, {"com.google.android.tv.freeplay", "Google TV Freeplay"},
            {"com.tcl.browser", "BrowseHere"}};
        last = -1;
        for (String[] a : tv) {
            check(!AppGrid.tclApp(a[0], a[1]), a[1] + ": not a utility of TCL's");
            check(AppGrid.tvUtility(a[0], a[1]), a[1] + ": TV & Utilities");
            int rank = AppGrid.gridRank(a[0], a[1]);
            check(rank > last, a[1] + ": in the folder's order");
            last = rank;
        }
        for (String[] a : new String[][] {{"com.netflix.ninja", "Netflix"}, {"com.spotify.tv.android", "Spotify"}, {"com.tubitv", "Tubi"},
            {"com.roblox.client", "Roblox"}, {"com.instagram.airwave", "Instagram"}, {"com.wbd.stream", "HBO Max"}}) {
            check(!AppGrid.tclApp(a[0], a[1]) && !AppGrid.tvUtility(a[0], a[1]), a[1] + ": in the grid, never a folder");
        }
        check(AppGrid.tclApp("com.tcl.somethingnew", "Something New"), "another com.tcl app: TCL's");
        // A folder's rows come out even: no app left alone on a row, up to a full window.
        for (int n = 2; n <= 12; n++) {
            int cols = AppGrid.folderColumns(n), rest = n % cols;
            check(cols >= 2 && cols <= 4, n + " apps: 2 to 4 across");
            check(rest != 1, n + " apps: none alone on the last row (" + cols + " across)");
            check(n / cols + (rest > 0 ? 1 : 0) <= 3, n + " apps: all in view");
        }
        check(AppGrid.folderColumns(4) == 2 && AppGrid.folderColumns(6) == 3 && AppGrid.folderColumns(7) == 4, "4, 6 and 7 apps: 2, 3 and 4 across");
        check(AppGrid.folderColumns(13) == 3, "a long folder: 3 across, the rest a scroll away");
        // Moving in an open folder: 7 apps, 4 across (0 1 2 3 / 4 5 6).
        check(AppGrid.folderMove(3, 0, 1, 7, 4) == 6, "down from the end of a longer row: the last app");
        check(AppGrid.folderMove(1, 0, 1, 7, 4) == 5, "down: the app below");
        check(AppGrid.folderMove(5, 0, -1, 7, 4) == 1, "up: the app above");
        check(AppGrid.folderMove(3, 1, 0, 7, 4) == -1, "right at the row's end: nowhere");
        check(AppGrid.folderMove(4, -1, 0, 7, 4) == -1, "left at the row's start: nowhere");
        check(AppGrid.folderMove(5, 0, 1, 7, 4) == -1, "down from the last row: nowhere");
        check(AppGrid.folderMove(1, 0, -1, 7, 4) == -1, "up from the first row: nowhere");
        System.out.println("FolderSortTest: " + checks + " checks passed");
    }
}
