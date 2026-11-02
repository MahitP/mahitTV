package io.freemytv;

/** Upstream's rows have no profiles: only the manager changes a key, a sign-in or a choice for the whole TV. */
public final class RowAccessTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (String row : new String[] {"tmdb", "watchmode", "picksFrom", "cartoons", "trakt", "photoSource", "powerOn"}) {
            check(RowAccess.mayOpen(row, true, false), row + ": the manager may");
            check(!RowAccess.mayOpen(row, false, false), row + ": family may not");
            check(!RowAccess.mayOpen(row, false, true), row + ": a guest may not");
        }
        check(RowAccess.mayOpen("clock", true, false), "clock: the manager may");
        check(RowAccess.mayOpen("clock", false, false), "clock: family may");
        check(!RowAccess.mayOpen("clock", false, true), "clock: a guest may not");
        check(!RowAccess.mayOpen("anything else", true, false), "a row nobody listed is closed");
        System.out.println("RowAccessTest: " + checks + " checks passed");
    }
}
