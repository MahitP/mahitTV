package io.freemytv;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Clean Up This TV" (TVOS-LAUNCHER-MERGE-SPEC.md S1): CleanYourTV's per-model debloat profiles
 * (assets/cleanyourtv, MIT, Gathin23/cleanyourtv) matched to this TV, and the plan of what may go off. The matching is
 * adapted from tvOS-Launcher (Apache-2.0); what may go off is decided only by HomeMode's own rules (mayTurnOff, the
 * TV's inputs, the Wi-Fi owners, the home screens), and HomeMode turns them off with `pm disable-user` and records
 * them, so Original turns them back on. Nothing here talks to the TV: HomeMode passes in what ADB found.
 */
final class Debloat {
    private Debloat() {}

    static final String DIR = "cleanyourtv";

    /** Why a package of the profile stays on (SAFE: it may go off). */
    static final int SAFE = 0, SYSTEM = 1, TV_NEEDS = 2, WIFI = 3, HOME = 4, UNCHECKED = 5;

    /** How the profile was found: this exact model, a similar one, or the most apps in common. */
    static final int EXACT = 0, SIMILAR = 1, PACKAGES = 2;

    static final class Model {
        final String slug, title, meta;
        final List<String> packages;

        Model(String slug, String title, String meta, List<String> packages) {
            this.slug = slug;
            this.title = title;
            this.meta = meta;
            this.packages = packages;
        }
    }

    static final class Match {
        final Model model;
        final int score, kind;

        Match(Model model, int score, int kind) {
            this.model = model;
            this.score = score;
            this.kind = kind;
        }
    }

    static final class Item {
        final String pkg;
        final int why;

        Item(String pkg, int why) {
            this.pkg = pkg;
            this.why = why;
        }
    }

    // MARK: - Reading the profiles

    /** index.txt: a line per model, tab-separated: slug, title, notes, the packages (comma-separated). */
    static List<Model> parseIndex(String text) {
        List<Model> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4 || f[0].trim().isEmpty()) continue;
            List<String> pkgs = new ArrayList<>();
            for (String p : f[3].split(",")) if (isPackage(p.trim())) pkgs.add(p.trim());
            out.add(new Model(f[0].trim(), f[1].trim(), f[2].trim(), pkgs));
        }
        return out;
    }

    /** debloat.txt: a package per line, "#" starts a comment (whole lines and after a package). */
    static List<String> parseList(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            String p = (hash >= 0 ? line.substring(0, hash) : line).trim();
            int space = p.indexOf(' ');
            if (space > 0) p = p.substring(0, space);
            if (isPackage(p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    static boolean isPackage(String p) {
        return p.matches("[A-Za-z][\\w]*(\\.[\\w]+)+");
    }

    static List<Model> load(Context c) {
        try {
            return parseIndex(read(c.getAssets().open(DIR + "/index.txt")));
        } catch (IOException e) {
            AppLog.w("clean up: no profiles: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** The model's own list (examples/<slug>/debloat.txt, with its comments), else the index's. */
    static List<String> packagesFor(Context c, Model m) {
        try {
            List<String> l = parseList(read(c.getAssets().open(DIR + "/examples/" + m.slug + "/debloat.txt")));
            if (!l.isEmpty()) return l;
        } catch (IOException ignored) {
        }
        return m.packages;
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // MARK: - Which profile is this TV's

    /** This TV, as Build reports it. */
    static Match match(List<Model> models, Set<String> installed) {
        return match(models, Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT, installed);
    }

    /**
     * The best profile: by model, product, device and brand (100 and over is this model, 35 and over a similar one);
     * without a similar one, the profile with the most of its apps on this TV (at least 3). null: none fits.
     */
    static Match match(List<Model> models, String brand, String manufacturer, String model, String device, String product,
                       Set<String> installed) {
        Model best = null;
        int bestScore = 0;
        for (Model m : models) {
            int s = score(m, brand, manufacturer, model, device, product);
            if (s > bestScore) {
                best = m;
                bestScore = s;
            }
        }
        if (best != null && bestScore >= 100) return new Match(best, bestScore, EXACT);
        Model byApps = null;
        int appsScore = 0, appsMatched = 0;
        for (Model m : models) {
            int total = 0, oem = 0;
            for (String p : m.packages) {
                if (!installed.contains(p)) continue;
                total++;
                if (!GENERIC.contains(p)) oem++;
            }
            if (total == 0) continue;
            int s = oem * 25 + total * 4 + total * 100 / Math.max(1, m.packages.size()) / 4;
            if (s > appsScore) {
                byApps = m;
                appsScore = s;
                appsMatched = total;
            }
        }
        if (best != null && bestScore >= 35) return new Match(best, bestScore, SIMILAR);
        return byApps != null && appsMatched >= 3 ? new Match(byApps, appsScore, PACKAGES) : null;
    }

    /** tvOS-Launcher's scoring (CleanYourTv.findHardwareMatches), without its reasons. */
    static int score(Model m, String brand, String manufacturer, String model, String device, String product) {
        String slug = m.slug.toLowerCase(Locale.US), title = m.title.toLowerCase(Locale.US), meta = m.meta.toLowerCase(Locale.US);
        String modelLower = lower(model), deviceLower = lower(device), productLower = lower(product);
        String slugGuess = slugOf(lower(brand) + " " + modelLower), modelSlug = slugOf(modelLower);
        int score = 0;
        if (!modelLower.isEmpty() && slug.equals(slugGuess)) {
            score += 120;
        } else if (modelSlug.length() >= 4 && (slug.equals(modelSlug) || slug.endsWith("-" + modelSlug))) {
            score += 105;
        }
        if (modelLower.length() >= 3 && (meta.contains("model=\"" + modelLower + "\"") || title.contains("(" + modelLower + ")"))) {
            score += 90;
        }
        if (productLower.length() >= 3 && meta.contains(productLower)) score += 80;
        if (deviceLower.length() >= 3 && (meta.contains("device " + deviceLower) || meta.contains("device: " + deviceLower)
                || slug.contains("-" + deviceLower))) {
            score += 65;
        }
        for (String t : tokens(lower(brand) + " " + lower(manufacturer))) {
            if (t.length() >= 3 && (slug.startsWith(t + "-") || title.startsWith(t + " ") || meta.contains(t))) {
                score += 35;
                break;
            }
        }
        for (String t : tokens(modelLower)) {
            if (t.length() >= 3 && (slug.contains(t) || title.contains(t) || meta.contains(t))) score += 25;
        }
        return score;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }

    private static String slugOf(String s) {
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList("tv", "smart", "android", "google", "4k", "uhd",
            "fhd", "hd", "led", "qled", "oled", "pro", "max", "plus", "stick", "box", "edition", "series"));

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+")) {
            if (p.length() >= 2 && !STOP.contains(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** Google's own apps that are on nearly every TV: they count less when matching by apps. */
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "com.android.bips", "com.android.calllogbackup", "com.android.dreams.basic", "com.android.printspooler",
            "com.android.providers.calendar", "com.android.providers.contacts", "com.android.providers.tv",
            "com.android.stk", "com.android.traceur", "com.android.vpndialogs", "com.android.wallpaperbackup",
            "com.google.android.apps.tv.launcherx", "com.google.android.backdrop", "com.google.android.feedback",
            "com.google.android.katniss", "com.google.android.leanbacklauncher", "com.google.android.marvin.talkback",
            "com.google.android.partnersetup", "com.google.android.play.games", "com.google.android.tts",
            "com.google.android.tungsten.setupwraith", "com.google.android.tv.bugreportsender",
            "com.google.android.tvrecommendations", "com.google.android.videos", "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic"));

    // MARK: - What may go off

    /**
     * The profile's packages that are installed and on, each with why it stays on, in the profile's order.
     * `needed`: what the TV's inputs and remote need (null: the check didn't finish, so none goes off); `owners`: the
     * apps that saved a Wi-Fi network (null: unknown, so none goes off while on Wi-Fi: `onWifi`); `home`: the home
     * screens, the setup app and the screensaver, which only the mahitTV/Original switch turns off.
     */
    static List<Item> plan(List<String> profile, Set<String> enabled, Set<String> needed, Set<String> owners,
                           boolean onWifi, Set<String> home) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String pkg : profile) {
            if (!seen.add(pkg) || !enabled.contains(pkg)) continue;
            out.add(new Item(pkg, why(pkg, needed, owners, onWifi, home)));
        }
        return out;
    }

    /**
     * The record after `pkg` went off: what was there stays (Original turns all of it back on), `pkg` is added once.
     * HomeMode keeps both its `disabled` list and Clean Up's own `debloated` list this way.
     */
    static Set<String> recordOff(Set<String> record, String pkg) {
        Set<String> out = new java.util.TreeSet<>(record);
        if (isPackage(pkg)) out.add(pkg);
        return out;
    }

    static int why(String pkg, Set<String> needed, Set<String> owners, boolean onWifi, Set<String> home) {
        if (pkg.equals(HomeMode.PKG) || home.contains(pkg)) return HOME;
        if (!HomeMode.mayTurnOff(pkg)) return SYSTEM;
        if (owners == null ? onWifi : owners.contains(pkg)) return WIFI;
        if (needed == null) return UNCHECKED;
        if (needed.contains(pkg)) return TV_NEEDS;
        return SAFE;
    }
}
