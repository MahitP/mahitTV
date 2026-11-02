package io.freemytv;

/** The command that lets mahitTV run in the background on TCL: this firmware knows only one of the op's two names. */
public final class TclAppBootTest {
    public static void main(String[] args) {
        String c = TclAppBoot.allow("io.freemytv");
        if (!c.equals("appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow")) {
            throw new AssertionError(c);
        }
        for (String word : new String[] {"wifi", "pm ", "settings ", "cmd "}) {
            if (c.contains(word)) throw new AssertionError("only the app-op: " + word);
        }
        System.out.println("TclAppBootTest: 2 checks passed");
    }
}
