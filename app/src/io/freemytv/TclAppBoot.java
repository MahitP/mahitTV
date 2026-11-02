package io.freemytv;

/**
 * TCL's TclAppBoot blocks background jobs until its permission is allowed, so a switch's job never starts (#364). It's an
 * app-op, nothing else on the TV changes: some firmware calls it APP_AUTO_START, and some has neither.
 */
final class TclAppBoot {
    private TclAppBoot() {}

    static String allow(String pkg) {
        return "appops set " + pkg + " AUTO_START allow || appops set " + pkg + " APP_AUTO_START allow";
    }
}
