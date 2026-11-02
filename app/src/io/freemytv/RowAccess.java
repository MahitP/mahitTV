package io.freemytv;

/** Who may open the rows that hold a key, a sign-in or a choice for the whole TV: upstream's come without profiles. */
final class RowAccess {
    private RowAccess() {}

    static boolean mayOpen(String row, boolean manager, boolean guest) {
        switch (row) {
            case "tmdb": case "watchmode": case "picksFrom": case "cartoons": case "trakt": case "photoSource": case "powerOn":
                return manager;
            case "clock":
                return !guest;
            default:
                return false;
        }
    }
}
