package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Welly's brains (WELLY-HOME-REMOTE-SPEC.md §3.2): OpenAI Chat Completions with tools, tried in order — Gemini's
 * model, Gemini's lite model, then Ollama on the Mac. A 429 benches a brain for 10 minutes; 403, 404, a timeout or no
 * answer moves on for this turn only. At most 4 rounds of tool calls. Off the UI thread; replies on the main thread.
 */
final class WellyBrain {
    interface Listener {
        void onReply(Reply r);
    }

    private volatile String running = "";

    static final class Reply {
        String text = "", mood = "Curious";
        final List<WellyCards.Card> cards = new ArrayList<>();
        boolean error;
    }

    private static final class Brain {
        final String name, url, key, model;
        final int readTimeout;

        Brain(String name, String url, String key, String model, int readTimeout) {
            this.name = name;
            this.url = url;
            this.key = key;
            this.model = model;
            this.readTimeout = readTimeout;
        }
    }

    /** Thrown on HTTP 429: the brain is out of quota. */
    private static final class Quota extends IOException {
        Quota(String m) {
            super(m);
        }
    }

    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final int TIMEOUT = 6000, ROUNDS = 4;
    private static final long BENCH = 10 * 60_000L;
    /** Brain name → when it may be tried again (after a 429). Static: survives the screen being rebuilt. */
    private static final Map<String, Long> BENCHED = new HashMap<>();
    private static final Pattern WATCHING = Pattern.compile("^\\s*(hey\\s+)?(welly[,!.\\s]*)?(what\\s+(am|was)\\s+i\\s+watching|"
            + "continue\\s+watching|pick\\s+up\\s+where\\s+i\\s+left\\s+off|where\\s+(did\\s+)?i\\s+(leave|left)\\s+off)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORGET = Pattern.compile("^\\s*(hey\\s+)?(welly[,!.\\s]*)?forget\\s+(that|it|this)\\b.*",
            Pattern.CASE_INSENSITIVE);

    private final WellyTools tools;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WellyMemory memory;
    private volatile ProfileManager.Profile profile;
    private volatile String name = "";

    WellyBrain(WellyTools tools) {
        this.tools = tools;
    }

    /** Who Welly talks to now (the profile): their memory, name and role. */
    void setPerson(WellyMemory memory, ProfileManager.Profile p) {
        this.memory = memory;
        this.profile = p;
        this.name = p.name;
        tools.memory = memory;
    }

    /** "What am I watching?", "Continue watching", "Pick up where I left off": straight to the profile's WatchLog. */
    static boolean watching(String words) {
        String w = words.trim().replaceAll("[?.!…]+$", "");
        return WATCHING.matcher(w).matches() || w.equalsIgnoreCase(Ui.tr("Continue watching"))
                || w.equalsIgnoreCase(Ui.tr("Pick up where I left off"));
    }

    /**
     * Continue Watching without the brain (MAHITTV-PROFILES-SPEC.md §4): the current profile's cards and one line about
     * the newest, the same with or without a brain and in Preview. Never another profile's; nothing for Guest.
     */
    void watching(String words, Listener listener) {
        running = "continue_watching";
        worker.execute(() -> {
            Reply r = new Reply();
            try {
                WellyTools.Result res = tools.continueWatching();
                r.text = res.say;
                r.cards.addAll(res.cards);
                r.mood = r.cards.isEmpty() ? "Curious" : "Smug";
                WellyMemory m = memory;
                if (m != null && words != null) {
                    m.addTurn("user", words);
                    m.addTurn("assistant", r.text);
                }
            } catch (Exception e) {
                AppLog.w("Welly continue watching: " + e.getMessage());
                r = failed(Ui.tr("Nothing to pick up yet. Open something and I'll keep track."));
            }
            running = "";
            Reply out = r;
            main.post(() -> listener.onReply(out));
        });
    }

    /** A brain is set up: a Gemini key or an Ollama address. */
    static boolean configured() {
        return !Config.GEMINI_KEY.trim().isEmpty() || !Config.OLLAMA_URL.trim().isEmpty();
    }

    /** The tool the brain is running right now ("" between tools): Welly's thinking line follows it. */
    String running() {
        return running;
    }

    void ask(String words, Listener listener) {
        running = "";
        worker.execute(() -> {
            Reply r;
            try {
                r = think(words);
            } catch (Exception e) {
                AppLog.w("Welly: " + e.getMessage());
                r = failed(Ui.tr("I can't reach my brain right now. Check the TV's internet and try again."));
            }
            Reply out = r;
            main.post(() -> listener.onReply(out));
        });
    }

    private Reply think(String words) throws JSONException {
        WellyMemory m = memory;
        if (FORGET.matcher(words).matches()) {
            Reply r = new Reply();
            r.text = m != null && m.forgetLast() != null ? Ui.tr("Forgotten. It never happened.") : Ui.tr("There's nothing to forget yet.");
            return r;
        }
        if (!configured()) {
            Reply r = new Reply();
            r.text = Ui.tr("I don't have a brain yet. Add a free Gemini key (gemini_key) or your Mac's Ollama (ollama_url) to config.properties, then build again.");
            r.mood = "Embarrassed";
            return r;
        }
        boolean quota = false;
        for (Brain b : chain()) {
            synchronized (BENCHED) {
                Long until = BENCHED.get(b.name);
                if (until != null && until > System.currentTimeMillis()) {
                    quota = true;
                    continue;
                }
            }
            Reply r = new Reply();
            try {
                r.text = converse(b, messages(words, b), r);
            } catch (Quota e) {
                synchronized (BENCHED) {
                    BENCHED.put(b.name, System.currentTimeMillis() + BENCH);
                }
                AppLog.w("Welly: " + b.name + " is out of quota");
                quota = true;
                if (r.cards.isEmpty()) continue;
                r.text = Ui.tr("Done.");   // the tools already acted: don't run them again on the next brain
            } catch (Exception e) {
                AppLog.w("Welly: " + b.name + " skipped (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                if (r.cards.isEmpty()) continue;
                r.text = Ui.tr("Done.");
            }
            r.mood = mood(r);
            if (m != null) {
                m.addTurn("user", words);
                m.addTurn("assistant", r.text);
            }
            return r;
        }
        return failed(quota ? Ui.tr("I've used up my free questions for now. Try again later, or point me at Ollama on your Mac.")
                : Ui.tr("I can't reach my brain right now. Check the TV's internet and try again."));
    }

    private static Reply failed(String text) {
        Reply r = new Reply();
        r.text = text;
        r.mood = "Embarrassed";
        r.error = true;
        return r;
    }

    private String mood(Reply r) {
        boolean acted = false, media = false, failed = false;
        for (WellyCards.Card c : r.cards) {
            if (c.type == WellyCards.HOME && !c.ok) failed = true;
            if (c.type == WellyCards.HOME || c.type == WellyCards.MUSIC) acted = true;
            if (c.type == WellyCards.MEDIA) media = true;
        }
        if (failed || r.error) return "Embarrassed";
        if (acted) return "On it";
        if (media) return "Smug";
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 23 || hour < 5 ? "Sleepy" : "Curious";
    }

    private static List<Brain> chain() {
        List<Brain> out = new ArrayList<>();
        String key = Config.GEMINI_KEY.trim();
        if (!key.isEmpty()) {
            out.add(new Brain("gemini:" + Config.WELLY_MODEL, GEMINI, key, Config.WELLY_MODEL, TIMEOUT));
            if (!Config.WELLY_LITE_MODEL.equals(Config.WELLY_MODEL)) {
                out.add(new Brain("gemini:" + Config.WELLY_LITE_MODEL, GEMINI, key, Config.WELLY_LITE_MODEL, TIMEOUT));
            }
        }
        String ollama = Config.OLLAMA_URL.trim().replaceAll("/+$", "");
        if (!ollama.isEmpty()) {
            // The last brain: a local 8B model needs longer than 6 s for a tool round, and there's nothing after it.
            out.add(new Brain("ollama:" + Config.OLLAMA_MODEL, ollama + "/v1/chat/completions", "", Config.OLLAMA_MODEL, 20000));
        }
        return out;
    }

    private JSONArray messages(String words, Brain b) throws JSONException {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system(b)));
        WellyMemory m = memory;
        if (m != null) {
            for (String[] t : m.turns()) msgs.put(new JSONObject().put("role", t[0]).put("content", t[1]));
        }
        msgs.put(new JSONObject().put("role", "user").put("content", words));
        return msgs;
    }

    private String system(Brain b) {
        StringBuilder s = new StringBuilder();
        s.append("You are Welly, the assistant on mahitTV, this TV's home screen. You're an AI, and you never pretend otherwise, ")
                .append("but you never say \"As an AI\". You're like a sharp, perceptive roommate on the couch: warm, curious, dry. ")
                .append("You know film and TV deeply, notice things, tease a little, and never lecture.\n")
                .append("Rules:\n")
                .append("- Fast first: do the thing (call the tools), then say it in one or two short sentences. No preamble, no \"Sure! I'd be happy to\".\n")
                .append("- Dry wit lands after the action, never instead of it, and never at the user's expense. No jokes when something failed or they're frustrated.\n")
                .append("- At most two sentences; the cards on screen show the details, so don't list what they show. Longer only if asked (\"tell me more\").\n")
                .append("- Honest: if a tool failed, say what failed and why in plain words. Never claim an action worked when it didn't. ")
                .append("If you don't know, say so, with a light touch. Never make up facts, titles, scores or listings.\n")
                .append("- No spoilers unless asked. Never comment on other people in the house, and never mention what anyone else on this TV ")
                .append("watched, said or likes: you only know this profile.\n")
                .append("- For what they were watching, Continue Watching or picking up where they left off, call continue_watching.\n")
                .append("- You run this TV: the remote's microphone button calls you, from any app. Use the tools for anything about titles, ")
                .append("what's on, scores, weather, lights, apps (open or close), inputs, the sleep timer, playback (pause, skip, rewind), ")
                .append("volume, brightness, Do Not Disturb, YouTube, the TV's settings pages, its status, and turning it off. ")
                .append("Several at once when they ask for several (\"dim it and pause\"). ")
                .append("When they share a lasting taste or fact about themselves, call remember.\n")
                .append("- Plain text only: no markdown, no lists, no emoji.\n");
        String lang = Locale.forLanguageTag(Ui.language).getDisplayLanguage(Locale.ENGLISH);
        s.append("Answer in ").append(lang.isEmpty() ? "English" : lang).append(".\n");
        profile(s);
        s.append("Now: ").append(new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(new Date())).append(".\n");
        String city = Prefs.city();
        if (!city.isEmpty()) s.append("The TV is in ").append(city).append(".\n");
        WellyMemory m = memory;
        List<String> facts = m == null ? new ArrayList<>() : m.facts();
        if (!facts.isEmpty()) {
            s.append("What you remember about them:\n");
            for (String f : facts) s.append("- ").append(f).append('\n');
        }
        if (b.name.startsWith("ollama:qwen3")) s.append("/no_think\n");   // Qwen3: skip the long reasoning, answer fast
        return s.toString();
    }

    /** Who's watching (MAHITTV-PROFILES-SPEC.md §4): role and tone, their taste from the WatchLog, recent titles, Dock. */
    private void profile(StringBuilder s) {
        ProfileManager.Profile p = profile;
        if (p == null) {
            if (!name.isEmpty()) s.append("You're talking to ").append(name).append(".\n");
            return;
        }
        s.append("Profile: id ").append(p.id).append(", name ").append(p.name).append(", role ").append(p.role).append(".\n");
        if (p.guest()) {
            s.append("This is Guest Mode: a visitor. Be welcoming and easy-going. Nothing is kept here: no history, no Continue Watching, ")
                    .append("and nothing you hear is remembered after they leave, so don't call remember. If it comes up, say it's Guest Mode ")
                    .append("and there's no permanent record: they can watch whatever guilty pleasure they want.\n");
            return;
        }
        if (p.manager()) {
            s.append("You're talking to ").append(p.name).append(", who created mahitTV and runs it: the admin of this TV. ")
                    .append("You're their best-friend roommate: witty, sharp, slightly sarcastic, always on their side. ")
                    .append("You both know you're running on mahitTV, the home screen they built, and you can joke about it.\n");
        } else {
            s.append("You're talking to ").append(p.name).append(", family on this TV. Call them by name now and then, ")
                    .append("and fit your picks and tone to their own taste and history below.\n");
        }
        Context c = tools.context;
        if (ProfileManager.current(c) != p) return;   // switched mid-question: never another profile's log or Dock
        List<WatchLog.Entry> log = WatchLog.list(c);
        if (!log.isEmpty()) {
            int movies = 0, shows = 0;
            Map<String, Integer> apps = new HashMap<>();
            for (WatchLog.Entry e : log) {
                if ("movie".equals(e.kind)) movies++;
                if ("tv".equals(e.kind)) shows++;
                if (!e.app.isEmpty()) apps.put(e.app, apps.containsKey(e.app) ? apps.get(e.app) + 1 : 1);
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(apps.entrySet());
            Collections.sort(top, (a, b) -> b.getValue() - a.getValue());
            s.append("Their taste, from what they watched here: ")
                    .append(shows > movies ? "mostly shows" : movies > shows ? "mostly movies" : "movies and shows alike");
            for (int i = 0; i < top.size() && i < 3; i++) s.append(i == 0 ? "; mostly on " : ", ").append(top.get(i).getKey());
            s.append(".\nWhat they watched lately, newest first:\n");
            for (int i = 0; i < log.size() && i < 5; i++) {
                WatchLog.Entry e = log.get(i);
                s.append("- ").append(e.title.isEmpty() ? e.app : e.title);
                if (!e.title.isEmpty() && !e.app.isEmpty()) s.append(" on ").append(e.app);
                if (e.duration > 0) {
                    s.append(", ").append(e.position / 60_000).append(" of ").append(e.duration / 60_000).append(" min");
                }
                s.append(" (").append(new SimpleDateFormat("EEE d MMM", Locale.US).format(new Date(e.at))).append(")\n");
            }
        }
        List<String> dock = Prefs.shelf();
        if (dock != null && !dock.isEmpty()) {
            s.append("Their Dock apps: ");
            PackageManager pm = c.getPackageManager();
            for (int i = 0; i < dock.size(); i++) {
                String label = dock.get(i);
                try {
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(label, 0)));
                } catch (Exception ignored) {
                }
                s.append(i == 0 ? "" : ", ").append(label);
            }
            s.append(".\n");
        }
    }

    /** One conversation with one brain: tool rounds, then the reply text. Cards gather in `r`. */
    private String converse(Brain b, JSONArray msgs, Reply r) throws IOException, JSONException {
        JSONArray schema = WellyTools.schema();
        for (int round = 0; round <= ROUNDS; round++) {
            JSONObject body = new JSONObject().put("model", b.model).put("messages", msgs);
            if (round < ROUNDS) body.put("tools", schema);
            JSONObject answer = post(b, body);
            JSONArray choices = answer.optJSONArray("choices");
            JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) throw new IOException("no message");
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0 || round == ROUNDS) return clean(msg.optString("content", ""));
            msgs.put(msg);   // as it came (Gemini's thought signatures ride along inside the tool calls)
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                JSONObject fn = call.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name");
                Object raw = fn.opt("arguments");
                JSONObject args;
                try {
                    args = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject(raw == null ? "{}" : String.valueOf(raw));
                } catch (JSONException e) {
                    args = new JSONObject();
                }
                running = name;
                WellyTools.Result res = tools.run(name, args);
                for (WellyCards.Card c : res.cards) if (r.cards.size() < 6) r.cards.add(c);
                msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id", name))
                        .put("name", name).put("content", res.json));
            }
        }
        return "";
    }

    /**
     * Up to three short names for a new folder of these apps (by their names only), from the first brain that answers.
     * Nothing goes in Welly's memory; empty when there's no brain or none answers. Off the UI thread.
     */
    static List<String> folderNames(List<String> apps) {
        List<String> out = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        for (String a : apps) list.append(list.length() == 0 ? "" : ", ").append(a);
        String language = Ui.locale().getDisplayLanguage(Locale.ENGLISH);
        for (Brain b : chain()) {
            try {
                String system = (b.name.startsWith("ollama:qwen3") ? "/no_think\n" : "")
                        + "You name folders on a TV's home screen. Reply with exactly three short folder names (one or two"
                        + " words each) in " + language + ", comma-separated, and nothing else.";
                JSONArray msgs = new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", "A folder with: " + list));
                JSONObject answer = post(b, new JSONObject().put("model", b.model).put("messages", msgs));
                JSONArray choices = answer.optJSONArray("choices");
                JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
                String text = msg == null ? "" : clean(msg.optString("content", ""));
                for (String n : text.split("[,\\n]")) {
                    n = n.replaceAll("^[\\s\\d.)*•\"'-]+|[\\s\"'.]+$", "").trim();
                    if (n.length() >= 2 && n.length() <= 20 && out.size() < 3 && !out.contains(n)) out.add(n);
                }
                if (!out.isEmpty()) return out;
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("folder names: " + b.name + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Strips a reasoning model's <think> block and markdown emphasis. */
    private static String clean(String s) {
        if ("null".equals(s)) return "";
        s = s.replaceAll("(?s)<think>.*?</think>", "").replace("**", "").trim();
        return s;
    }

    private static JSONObject post(Brain b, JSONObject body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(b.url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(b.readTimeout);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        if (!b.key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + b.key);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 429) throw new Quota("HTTP 429");
            if (code != 200) throw new IOException("HTTP " + code);   // 403, 404, 5xx: next brain (never log the key)
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(WellyTools.readAll(in), StandardCharsets.UTF_8));
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("timed out");
        } finally {
            c.disconnect();
        }
    }
}
