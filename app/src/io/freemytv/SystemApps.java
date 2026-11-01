package io.freemytv;

/** Which system apps with only a phone-style launcher entry All Apps lists. */
final class SystemApps {
    private SystemApps() {}

    /** With Show system apps or when named; never a factory or service menu (one OK there can reset the TV). */
    static boolean listed(boolean showSystem, boolean named, boolean factoryMenu) {
        return !factoryMenu && (showSystem || named);
    }
}
