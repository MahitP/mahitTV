package io.freemytv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Clean Up This TV (Debloat): the profiles parse, the matcher finds a TV's own profile, and nothing HomeMode protects,
 * nothing the TV's inputs or Wi-Fi need, no home screen and never mahitTV can be planned to go off. Run from the
 * repository's root (tools/check.sh), against out/classes and android.jar.
 */
public final class DebloatTest {
    private static int checks;
    private static final Path ASSETS = Paths.get("app", "assets", "cleanyourtv");

    public static void main(String[] args) throws IOException {
        indexParses();
        listParses();
        realProfilesParse();
        matcherScores();
        protectedNeverPlanned();
        reasonsAreExact();
        everySafePackageMayTurnOff();
        recordGrows();
        System.out.println("DebloatTest: " + checks + " checks passed");
    }

    private static void indexParses() {
        String text = "tcl-65c725\tTCL 65C725\tnotes, device BeyondTV4\tcom.tcl.bi,com.tcl.esticker, not a package ,x\n"
                + "broken line without tabs\n"
                + "\tno slug\tmeta\tcom.a.b\n";
        List<Debloat.Model> models = Debloat.parseIndex(text);
        equal(1, models.size(), "only whole lines with a slug");
        Debloat.Model m = models.get(0);
        equal("tcl-65c725", m.slug, "slug");
        equal("TCL 65C725", m.title, "title");
        equal(Arrays.asList("com.tcl.bi", "com.tcl.esticker"), m.packages, "only package names");
    }

    private static void listParses() {
        String text = "# a comment\n\ncom.tcl.bi                 # analytics\n  com.tcl.esticker\ncom.tcl.bi\nnot-a-package\n"
                + "#com.commented.out\ncom.foo.bar extra words\n";
        equal(Arrays.asList("com.tcl.bi", "com.tcl.esticker", "com.foo.bar"), Debloat.parseList(text),
                "comments, blanks and repeats left out, order kept");
    }

    private static void realProfilesParse() throws IOException {
        List<Debloat.Model> models = Debloat.parseIndex(read(ASSETS.resolve("index.txt")));
        equal(341, models.size(), "every profile in the index");
        int lists = 0;
        for (Debloat.Model m : models) {
            ok(!m.slug.isEmpty() && !m.title.isEmpty(), "profile " + m.slug + " has a slug and a title");
            Path file = ASSETS.resolve("examples").resolve(m.slug).resolve("debloat.txt");
            if (Files.exists(file) && !Debloat.parseList(read(file)).isEmpty()) lists++;
        }
        ok(lists >= 300, "most profiles have their own list (" + lists + ")");
    }

    private static void matcherScores() throws IOException {
        List<Debloat.Model> models = Debloat.parseIndex(read(ASSETS.resolve("index.txt")));
        Set<String> none = Collections.emptySet();
        Debloat.Match tcl = Debloat.match(models, "TCL", "TCL", "65C725", "BeyondTV4", "", none);
        ok(tcl != null && tcl.kind == Debloat.EXACT && tcl.model.slug.equals("tcl-65c725"), "TCL 65C725: its own profile");
        ok(tcl.score >= 100, "an exact match scores 100 or more");
        Debloat.Match mi = Debloat.match(models, "Xiaomi", "Xiaomi", "MiTV-MSSP3", "", "", none);
        ok(mi != null && mi.kind == Debloat.EXACT && mi.model.slug.startsWith("xiaomi-mitv-mssp3"), "Xiaomi MiTV-MSSP3");
        equal(null, Debloat.match(models, "Acme", "Acme", "Z1", "zz", "zz", none), "an unknown TV with no apps in common: none");
        Debloat.Model sample = models.get(0);
        Set<String> installed = new HashSet<>(sample.packages);
        Debloat.Match byApps = Debloat.match(models, "Acme", "Acme", "Z1", "zz", "zz", installed);
        ok(byApps != null && byApps.kind == Debloat.PACKAGES, "an unknown TV: by the apps it has");
        Debloat.Model fake = new Debloat.Model("brand-x100", "Brand X100", "", Collections.<String>emptyList());
        equal(120 + 35 + 25, Debloat.score(fake, "Brand", "", "X100", "", ""), "the slug (120), the brand (35), the model's name (25)");
    }

    /** HomeMode's own rules: PROTECTED, Android's core, factory menus, Amazon, mahitTV, never in the plan as SAFE. */
    private static void protectedNeverPlanned() {
        List<String> profile = Arrays.asList(
                "com.tcl.dashboard",                // PROTECTED (a profile lists it; ours keeps it)
                "com.tcl.useragreement",            // PROTECTED
                "android",                          // CORE
                "com.android.permissioncontroller", // CORE
                "com.android.providers.tv",         // CORE (CleanYourTV's own CORE_OK allows it; ours doesn't)
                "com.tcl.factory.view",             // FACTORY and PROTECTED
                "mediatek.factorymenu.ui",          // FACTORY
                "com.amazon.tv.launcher",           // Amazon
                "io.freemytv",                      // ours
                "com.google.android.tungsten.setupwraith",   // the setup app: HomeMode's switch only
                "com.tcl.bi");                      // an ordinary promo app
        Set<String> enabled = new HashSet<>(profile);
        Set<String> home = new HashSet<>(Collections.singletonList("com.google.android.tungsten.setupwraith"));
        List<Debloat.Item> plan = Debloat.plan(profile, enabled, Collections.<String>emptySet(),
                Collections.<String>emptySet(), true, home);
        equal(profile.size(), plan.size(), "every enabled package is listed, with its reason");
        for (Debloat.Item i : plan) {
            if (i.pkg.equals("com.tcl.bi")) equal(Debloat.SAFE, i.why, "an ordinary promo app may go off");
            else ok(i.why != Debloat.SAFE, i.pkg + " stays on");
        }
    }

    private static void reasonsAreExact() {
        Set<String> none = Collections.emptySet(), home = new HashSet<>(Collections.singletonList("com.tcl.home"));
        Set<String> owners = new HashSet<>(Collections.singletonList("com.tcl.wifiowner"));
        Set<String> needed = new HashSet<>(Collections.singletonList("com.tcl.hdmi"));
        equal(Debloat.HOME, Debloat.why("io.freemytv", needed, owners, true, home), "mahitTV: never");
        equal(Debloat.HOME, Debloat.why("com.tcl.home", needed, owners, true, home), "a home screen: the switch's");
        equal(Debloat.SYSTEM, Debloat.why("com.amazon.firetv", needed, owners, true, home), "Amazon's");
        equal(Debloat.WIFI, Debloat.why("com.tcl.wifiowner", needed, owners, true, home), "saved a Wi-Fi network");
        equal(Debloat.WIFI, Debloat.why("com.tcl.bi", needed, null, true, home), "Wi-Fi owners unknown, on Wi-Fi: nothing goes");
        equal(Debloat.SAFE, Debloat.why("com.tcl.bi", needed, null, false, home), "Wi-Fi owners unknown, off Wi-Fi");
        equal(Debloat.UNCHECKED, Debloat.why("com.tcl.bi", null, owners, true, home), "inputs unchecked: nothing goes");
        equal(Debloat.TV_NEEDS, Debloat.why("com.tcl.hdmi", needed, owners, true, home), "the TV's inputs need it");
        equal(Debloat.SAFE, Debloat.why("com.tcl.bi", needed, owners, true, home), "ordinary");
        List<Debloat.Item> plan = Debloat.plan(Arrays.asList("com.a.one", "com.a.two", "com.a.one"),
                new HashSet<>(Collections.singletonList("com.a.one")), none, none, false, none);
        equal(1, plan.size(), "only installed and enabled, once");
    }

    /** Over every real profile: whatever the plan lets go off passes HomeMode.mayTurnOff and isn't ours. */
    private static void everySafePackageMayTurnOff() throws IOException {
        List<Debloat.Model> models = Debloat.parseIndex(read(ASSETS.resolve("index.txt")));
        Set<String> none = Collections.emptySet();
        int safe = 0;
        for (Debloat.Model m : models) {
            Path file = ASSETS.resolve("examples").resolve(m.slug).resolve("debloat.txt");
            List<String> pkgs = Files.exists(file) ? Debloat.parseList(read(file)) : m.packages;
            for (Debloat.Item i : Debloat.plan(pkgs, new HashSet<>(pkgs), none, none, false, none)) {
                if (i.why != Debloat.SAFE) continue;
                safe++;
                if (!HomeMode.mayTurnOff(i.pkg) || i.pkg.equals(HomeMode.PKG) || i.pkg.startsWith("com.amazon.")) {
                    throw new AssertionError(m.slug + ": " + i.pkg + " planned to go off");
                }
            }
        }
        ok(safe > 0, "the profiles do suggest apps (" + safe + ")");
    }

    private static void recordGrows() {
        Set<String> record = new HashSet<>(Arrays.asList("com.google.android.tvrecommendations", "com.tcl.bi"));
        Set<String> after = Debloat.recordOff(record, "com.tcl.esticker");
        ok(after.containsAll(record), "what was recorded stays");
        ok(after.contains("com.tcl.esticker"), "the new one is added");
        equal(3, after.size(), "once each");
        equal(3, Debloat.recordOff(after, "com.tcl.bi").size(), "no repeats");
        equal(3, Debloat.recordOff(after, "not a package").size(), "only package names");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void equal(Object want, Object got, String message) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(message + ": want " + want + ", got " + got);
        checks++;
    }
}
