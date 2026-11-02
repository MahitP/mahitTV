package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What Welly can do (WELLY-HOME-REMOTE-SPEC.md §3.3): each tool returns JSON for the model and cards for the screen.
 * Runs on WellyBrain's thread; anything that opens an activity or touches the screen goes to the main thread.
 */
final class WellyTools {
    static final class Result {
        String json;
        final List<WellyCards.Card> cards = new ArrayList<>();
        boolean acted;   // it changed something (lights, an app opened): Welly's mood is "On it"
        boolean failed;
        String say = "";   // continue_watching: Welly's own line when no brain words it

        Result(String json) {
            this.json = json;
        }
    }

    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/w780";
    private static final int TIMEOUT = 6000;

    private final Activity activity;
    final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Recs.Pick> seen = new ConcurrentHashMap<>();   // titles from searches, by TMDB id
    volatile WellyMemory memory;

    WellyTools(Activity a) {
        activity = a;
        context = a.getApplicationContext();
    }

    // MARK: - The tools, as the model sees them

    static JSONArray schema() {
        JSONArray tools = new JSONArray();
        try {
            tools.put(tool("search_titles", "Find movies or shows (TMDB), filtered to the streaming apps on this TV. Use a query, or leave it empty and give a genre/runtime to discover.",
                    "query:string:Title, person or keywords", "kind:string:movie or tv", "max_runtime:integer:Longest runtime in minutes (movies)",
                    "genre:string:e.g. comedy, thriller, sci-fi, drama, horror, family, documentary"));
            tools.put(tool("open_title", "Open a title from search_titles in its app.", "!title_id:integer:TMDB id", "!kind:string:movie or tv"));
            tools.put(tool("whats_playing", "What is playing on the TV right now (title, artist, app)."));
            tools.put(tool("continue_watching", "This person's own Continue Watching: what they opened or watched lately on this TV, newest first "
                    + "(title, app, minutes in and left). Use it for \"what am I watching\", \"continue watching\", \"pick up where I left off\"."));
            tools.put(tool("whats_on", "TV listings for tonight in the US (TVmaze): broadcast and cable, or streaming premieres.",
                    "date:string:YYYY-MM-DD, default today", "streaming:boolean:true for streaming services instead of broadcast/cable"));
            tools.put(tool("scores", "Live and recent scores (ESPN).", "!league:string:nfl, nba, mlb, nhl, mls, wnba, ncaaf, ncaab, epl"));
            tools.put(tool("weather", "Current weather and the next hours. Defaults to the TV's city.", "place:string:A city, if not the TV's"));
            tools.put(tool("cast_crossref", "The cast of a title and what else they were in, matched against what this person has watched.",
                    "!title_id:integer:TMDB id", "!kind:string:movie or tv"));
            tools.put(tool("recap", "Spoiler-free material for one season of a show (episode summaries of that season only).",
                    "!title_id:integer:TMDB id of the show", "!season:integer:Season number"));
            tools.put(tool("lights", "Turn lights on or off.", "!room:string:parents, mine, living or all",
                    "fixture:string:lamp or bulb (leave out for every light in the room)", "!on:boolean:true to turn on"));
            tools.put(tool("scene", "Set a lighting scene: movie (lamps on, ceiling bulbs off), bright (all on), off (all off), sleep (all off and a 30 minute sleep timer).",
                    "!name:string:movie, bright, off or sleep"));
            tools.put(tool("launch_app", "Open an app on the TV.", "!name:string:The app's name"));
            tools.put(tool("input", "Switch to an input.", "!name:string:HDMI 1, HDMI 2…, or Live TV"));
            tools.put(tool("sleep_timer", "Turn the TV off after some minutes; 0 cancels.", "!minutes:integer:Minutes"));
            tools.put(tool("music", "Control what's playing.", "!action:string:play, pause, next or previous"));
            tools.put(tool("settings", "Open the TV's own settings."));
            tools.put(tool("remember", "Remember a lasting fact about this person (a taste, a show they're on). Only when they tell you something worth keeping.",
                    "!fact:string:One short sentence"));
        } catch (JSONException ignored) {
        }
        return tools;
    }

    /** One tool: params are "name:type:description", "!" first for a required one. */
    private static JSONObject tool(String name, String description, String... params) throws JSONException {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        for (String p : params) {
            String[] s = p.split(":", 3);
            String n = s[0];
            if (n.startsWith("!")) {
                n = n.substring(1);
                required.put(n);
            }
            props.put(n, new JSONObject().put("type", s[1]).put("description", s[2]));
        }
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", props);
        if (required.length() > 0) parameters.put("required", required);
        return new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", name).put("description", description).put("parameters", parameters));
    }

    /** Runs one tool call. Never throws: a failure is JSON the model can explain. */
    Result run(String name, JSONObject a) {
        try {
            switch (name) {
                case "search_titles": return searchTitles(a.optString("query"), a.optString("kind"), a.optInt("max_runtime"), a.optString("genre"));
                case "open_title": return openTitle(a.optInt("title_id"), a.optString("kind", "movie"));
                case "whats_playing": return whatsPlaying();
                case "continue_watching": return continueWatching();
                case "whats_on": return whatsOn(a.optString("date"), a.optBoolean("streaming"));
                case "scores": return scores(a.optString("league", "nfl"));
                case "weather": return weather(a.optString("place"));
                case "cast_crossref": return castCrossref(a.optInt("title_id"), a.optString("kind", "movie"));
                case "recap": return recap(a.optInt("title_id"), a.optInt("season", 1));
                case "lights": return lights(a.optString("room", "all"), a.optString("fixture"), a.optBoolean("on", true));
                case "scene": return scene(a.optString("name", "movie"));
                case "launch_app": return launchApp(a.optString("name"));
                case "input": return input(a.optString("name"));
                case "sleep_timer": return sleepTimer(a.optInt("minutes"));
                case "music": return music(a.optString("action", "pause"));
                case "settings": return settings();
                case "remember": return remember(a.optString("fact"));
                default: return error("no tool called " + name);
            }
        } catch (Exception e) {
            AppLog.w("Welly tool " + name + ": " + e.getMessage());
            return error(e.getMessage() == null ? "failed" : e.getMessage());
        }
    }

    private static Result error(String why) {
        Result r = new Result("{\"error\":" + JSONObject.quote(why) + "}");
        r.failed = true;
        return r;
    }

    // MARK: - Titles (TMDB)

    private static final String[][] GENRES = {   // name, movie id, tv id
        {"action", "28", "10759"}, {"adventure", "12", "10759"}, {"animation", "16", "16"}, {"comedy", "35", "35"},
        {"crime", "80", "80"}, {"documentary", "99", "99"}, {"drama", "18", "18"}, {"family", "10751", "10751"},
        {"fantasy", "14", "10765"}, {"horror", "27", "9648"}, {"mystery", "9648", "9648"}, {"romance", "10749", "10749"},
        {"sci", "878", "10765"}, {"thriller", "53", "80"}, {"war", "10752", "10768"}, {"western", "37", "37"},
    };
    // TMDB provider names (lowercase, contains) → app key and packages, in the order they're tried.
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott", "com.cbs.ca"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid", "com.peacock.peacockfiretv"},
        {"max", "max", "com.wbd.stream", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv", "com.apple.atve.amazon.appletv"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
    };

    private Result searchTitles(String query, String kind, int maxRuntime, String genre) throws Exception {
        if (Recs.key().isEmpty()) return error("no TMDB key on this TV, so no title search");
        String lang = "&language=" + tmdbLanguage() + "&include_adult=false";
        JSONArray found;
        if (!query.trim().isEmpty()) {
            String path = "/search/" + ("movie".equals(kind) || "tv".equals(kind) ? kind : "multi") + "?query=" + enc(query) + lang;
            found = tmdb(path).optJSONArray("results");
        } else {
            String k = "tv".equals(kind) ? "tv" : "movie";
            StringBuilder path = new StringBuilder("/discover/" + k + "?sort_by=popularity.desc&watch_region=" + Recs.REGION
                    + "&with_watch_monetization_types=flatrate&vote_count.gte=200" + lang);
            if (maxRuntime > 0) path.append("&with_runtime.lte=").append(maxRuntime);
            String g = genre.toLowerCase(Locale.ROOT);
            for (String[] row : GENRES) if (!g.isEmpty() && g.contains(row[0])) path.append("&with_genres=").append(k.equals("tv") ? row[2] : row[1]);
            found = tmdb(path.toString()).optJSONArray("results");
            if (found != null) for (int i = 0; i < found.length(); i++) found.getJSONObject(i).put("media_type", k);
        }
        List<Recs.Pick> withApp = new ArrayList<>(), without = new ArrayList<>();
        for (int i = 0; found != null && i < found.length() && withApp.size() + without.size() < 6; i++) {
            JSONObject o = found.getJSONObject(i);
            String type = o.optString("media_type", kind);
            if (!"movie".equals(type) && !"tv".equals(type)) continue;
            Recs.Pick p = pick(o, type);
            findApp(p);
            (p.pkg != null ? withApp : without).add(p);
        }
        withApp.addAll(without);
        JSONArray out = new JSONArray();
        Result r = new Result("");
        for (Recs.Pick p : withApp) {
            seen.put(p.id, p);
            out.put(new JSONObject().put("title_id", p.id).put("kind", p.kind).put("title", p.title).put("year", p.year)
                    .put("rating", Math.round(p.rating * 10) / 10.0).put("app", p.pkg == null ? JSONObject.NULL : Recs.serviceName(p))
                    .put("overview", cut(p.overview, 160)));
            if (r.cards.size() < 3 && (p.pkg != null || withApp.indexOf(p) < 3)) r.cards.add(mediaCard(p, true));
        }
        r.json = new JSONObject().put("results", out).put("note", out.length() == 0 ? "nothing found" : "cards show the first three").toString();
        return r;
    }

    private Recs.Pick pick(JSONObject o, String kind) {
        Recs.Pick p = new Recs.Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString("movie".equals(kind) ? "title" : "name", o.optString("title", o.optString("name")));
        String date = o.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        p.backdrop = o.optString("backdrop_path", "");
        if ("null".equals(p.backdrop)) p.backdrop = "";
        p.overview = o.optString("overview", "");
        p.rating = o.optDouble("vote_average", 0);
        return p;
    }

    /** The first app on this TV that streams the pick in the region (TMDB's watch providers). */
    private void findApp(Recs.Pick p) {
        try {
            JSONObject region = tmdb("/" + p.kind + "/" + p.id + "/watch/providers").optJSONObject("results");
            JSONObject here = region == null ? null : region.optJSONObject(Recs.REGION);
            JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
            for (int i = 0; flat != null && i < flat.length(); i++) {
                String name = flat.getJSONObject(i).optString("provider_name").toLowerCase(Locale.ROOT);
                if (name.contains(" channel")) continue;   // sold inside another app
                for (String[] s : SERVICES) {
                    if (!(s[0].equals("max") ? name.matches(".*\\bmax\\b.*") : name.contains(s[0]))) continue;
                    for (int j = 2; j < s.length; j++) {
                        if (HomeApps.launchIntent(context.getPackageManager(), s[j]) != null) {
                            p.app = s[1];
                            p.pkg = s[j];
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("Welly providers: " + e.getMessage());
        }
    }

    private WellyCards.Card mediaCard(Recs.Pick p, boolean download) {
        WellyCards.Card c = new WellyCards.Card();
        c.type = WellyCards.MEDIA;
        c.pick = p;
        c.title = p.title;
        String service = Recs.serviceName(p);
        c.meta = p.meta != null && !p.meta.isEmpty() ? p.meta
                : TextUtils.join(" · ", nonEmpty(p.year, service == null ? "" : service));
        if (download && p.backdrop != null && !p.backdrop.isEmpty()) c.image = image(BACKDROPS + p.backdrop);
        return c;
    }

    private Result openTitle(int id, String kind) throws Exception {
        Recs.Pick p = seen.get(id);
        if (p == null) {
            if (Recs.key().isEmpty()) return error("no TMDB key on this TV");
            p = pick(tmdb("/" + kind + "/" + id + "?language=" + tmdbLanguage()), kind);
            findApp(p);
            seen.put(id, p);
        }
        if (p.pkg == null) return error(p.title + " isn't on any streaming app on this TV");
        if (!open(titled(HomeApps.app(p.pkg, Recs.serviceName(p)), p))) return error("the app didn't open");
        WellyMemory m = memory;
        if (m != null) m.addFact("Opened " + p.title + " (" + p.kind + ") on " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date()));
        Result r = new Result(new JSONObject().put("opened", p.title).put("app", Recs.serviceName(p)).toString());
        r.cards.add(mediaCard(p, true));
        r.acted = true;
        return r;
    }

    /** Opens a title's app (the media card's OK). Main thread. */
    boolean openPick(Recs.Pick p) {
        if (p == null || p.pkg == null) return false;
        try {
            return HomeApps.open(activity, titled(HomeApps.app(p.pkg, Recs.serviceName(p), p.link), p), null);
        } catch (Exception e) {
            return false;
        }
    }

    /** The pick goes into this profile's Continue Watching as the title it is, not just its app. */
    private static HomeApps.Item titled(HomeApps.Item it, Recs.Pick p) {
        it.title = p.title;
        it.tmdbId = p.id;
        it.mediaKind = p.kind;
        return it;
    }

    // MARK: - Continue Watching (WatchLog: the current profile's only, nothing for Guest)

    /** Up to six of this profile's latest titles and apps, newest first, as media cards. Off the UI thread. */
    Result continueWatching() throws JSONException {
        Result r = new Result("");
        if (ProfileManager.current(context).guest()) {
            r.say = Ui.tr("Guest Mode keeps no history, so there's nothing to pick up. Watch whatever guilty pleasure you want.");
            r.json = new JSONObject().put("entries", new JSONArray())
                    .put("note", "Guest Mode: nothing is kept, so there's nothing to continue.").toString();
            return r;
        }
        long now = System.currentTimeMillis();
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.US);
        JSONArray out = new JSONArray();
        WatchLog.Entry latest = null;
        for (WatchLog.Entry e : WatchLog.list(context)) {
            if (out.length() >= 6) break;
            String title = e.title.isEmpty() ? e.app : e.title;
            if (title.isEmpty()) continue;
            if (latest == null) latest = e;
            JSONObject o = new JSONObject().put("title", title).put("app", e.app).put("kind", e.kind)
                    .put("opened", day.format(new Date(e.at)));
            if (e.duration > 0) {
                o.put("minutes_in", e.position / 60_000).put("minutes_left", minutesLeft(e))
                        .put("percent_watched", Math.round(e.progress() * 100));
            }
            out.put(o);
            r.cards.add(watchCard(e, now));
        }
        r.say = sayWatching(latest);
        r.json = new JSONObject().put("entries", out).toString();
        return r;
    }

    private static long minutesLeft(WatchLog.Entry e) {
        return Math.max(0, (e.duration - e.position + 59_999) / 60_000);
    }

    /** One witty line about the newest entry (Welly's answer without a brain, and in Preview). */
    private static String sayWatching(WatchLog.Entry e) {
        if (e == null) return Ui.tr("Nothing to pick up yet. Open something and I'll keep track.");
        if (e.title.isEmpty()) return Ui.trf("Last time you opened {app}. Back for more?", "app", e.app);
        if (e.duration > 0 && e.position >= 60_000 && minutesLeft(e) >= 1) {
            return Ui.trf("You were {n} minutes into {title}. Bold of you to stop there.", "n", e.position / 60_000, "title", e.title);
        }
        return Ui.trf("Last time you opened {title} on {app}. Picking up where you left off?", "title", e.title, "app", e.app);
    }

    private WellyCards.Card watchCard(WatchLog.Entry e, long now) {
        WellyCards.Card c = new WellyCards.Card();
        c.type = WellyCards.MEDIA;
        c.entry = e;
        c.title = e.title.isEmpty() ? e.app : e.title;
        long left = minutesLeft(e);
        String when;
        if (e.duration > 0 && left >= 1) when = Ui.trf("{n} min left", "n", left);
        else if (e.duration > 0) when = Ui.trf("{p}% watched", "p", Math.round(e.progress() * 100));
        else when = Ui.trf("Opened {when}", "when", DateUtils.getRelativeTimeSpanString(e.at, now, DateUtils.MINUTE_IN_MILLIS).toString());
        c.meta = TextUtils.join(" · ", nonEmpty(e.title.isEmpty() ? "" : e.app, when));
        c.image = watchImage(e);
        return c;
    }

    /** The title's backdrop: the sample photo in Preview, else TMDB's (with a key), else none. */
    private Bitmap watchImage(WatchLog.Entry e) {
        if (e.tmdbId <= 0) return null;
        if (Config.PREVIEW) {
            List<Recs.Pick> all = new ArrayList<>(Preview.picks("movie"));
            all.addAll(Preview.picks("tv"));
            for (Recs.Pick p : all) {
                if (p.id != e.tmdbId) continue;
                Bitmap b = Preview.image(context, p);
                return b == null ? null : Ui.downscale(b, 640, Math.round(640f * b.getHeight() / b.getWidth()));
            }
            return null;
        }
        if (Recs.key().isEmpty() || !("movie".equals(e.kind) || "tv".equals(e.kind))) return null;
        try {
            Recs.Pick seenPick = seen.get((int) e.tmdbId);
            String path = seenPick != null ? seenPick.backdrop : tmdb("/" + e.kind + "/" + e.tmdbId).optString("backdrop_path");
            return path == null || path.isEmpty() || "null".equals(path) ? null : image(BACKDROPS + path);
        } catch (Exception x) {
            AppLog.w("Welly continue watching: " + x.getMessage());
            return null;
        }
    }

    /** A Continue Watching card's OK: the title's page when the app gave one, else the app. Main thread. */
    boolean openWatched(WatchLog.Entry e) {
        if (e == null || e.pkg == null || e.pkg.isEmpty()) return false;
        try {
            HomeApps.Item it = e.uri == null || e.uri.isEmpty() ? HomeApps.app(e.pkg, e.app) : HomeApps.app(e.pkg, e.app, e.uri);
            if (!e.title.isEmpty()) {
                it.title = e.title;
                it.tmdbId = e.tmdbId;
                it.mediaKind = e.kind;
            }
            return HomeApps.open(activity, it, null);
        } catch (Exception x) {
            return false;
        }
    }

    private Result castCrossref(int id, String kind) throws Exception {
        if (Recs.key().isEmpty()) return error("no TMDB key on this TV");
        JSONArray cast = tmdb("/" + kind + "/" + id + ("tv".equals(kind) ? "/aggregate_credits" : "/credits")).optJSONArray("cast");
        String history = history().toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        for (int i = 0; cast != null && i < cast.length() && i < 5; i++) {
            JSONObject person = cast.getJSONObject(i);
            String character = person.optString("character");
            JSONArray roles = person.optJSONArray("roles");
            if (character.isEmpty() && roles != null && roles.length() > 0) character = roles.getJSONObject(0).optString("character");
            JSONArray credits = tmdb("/person/" + person.optInt("id") + "/combined_credits?language=" + tmdbLanguage()).optJSONArray("cast");
            List<JSONObject> list = new ArrayList<>();
            for (int j = 0; credits != null && j < credits.length(); j++) list.add(credits.getJSONObject(j));
            Collections.sort(list, (x, y) -> Double.compare(y.optDouble("popularity"), x.optDouble("popularity")));
            JSONArray known = new JSONArray(), watched = new JSONArray();
            for (JSONObject credit : list) {
                String t = credit.optString("title", credit.optString("name"));
                if (t.isEmpty() || credit.optInt("id") == id) continue;
                if (known.length() < 4) known.put(t);
                if (t.length() > 2 && history.contains(t.toLowerCase(Locale.ROOT)) && watched.length() < 3) watched.put(t);
            }
            out.put(new JSONObject().put("name", person.optString("name")).put("character", character)
                    .put("known_for", known).put("in_their_history", watched));
        }
        return new Result(new JSONObject().put("cast", out).toString());
    }

    private Result recap(int id, int season) throws Exception {
        if (Recs.key().isEmpty()) return error("no TMDB key on this TV");
        JSONObject s = tmdb("/tv/" + id + "/season/" + season + "?language=" + tmdbLanguage());
        JSONArray eps = s.optJSONArray("episodes"), out = new JSONArray();
        for (int i = 0; eps != null && i < eps.length() && i < 14; i++) {
            JSONObject e = eps.getJSONObject(i);
            out.put(new JSONObject().put("episode", e.optInt("episode_number")).put("name", e.optString("name"))
                    .put("summary", cut(e.optString("overview"), 220)));
        }
        return new Result(new JSONObject().put("season", season).put("overview", cut(s.optString("overview"), 400))
                .put("episodes", out).put("rule", "summarise only this season; nothing from later seasons").toString());
    }

    private JSONObject tmdb(String path) throws IOException, JSONException {
        String key = Recs.key();
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = TMDB + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + enc(key));
        return new JSONObject(get(url, bearer ? "Bearer " + key : null));
    }

    private static String tmdbLanguage() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        return lang.indexOf('-') > 0 ? lang : lang + "-" + Ui.locale().getCountry();
    }

    // MARK: - Listings (TVmaze), scores (ESPN), weather (Open-Meteo)

    private Result whatsOn(String date, boolean streaming) throws Exception {
        String day = date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        JSONArray all = new JSONArray(get("https://api.tvmaze.com/schedule" + (streaming ? "/web" : "") + "?country=US&date=" + day, null));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        long now = System.currentTimeMillis();
        List<Object[]> items = new ArrayList<>();   // {at, show, network}
        java.util.Set<String> shows = new java.util.HashSet<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.getJSONObject(i);
            JSONObject show = e.optJSONObject("show");
            if (show == null && e.optJSONObject("_embedded") != null) show = e.getJSONObject("_embedded").optJSONObject("show");
            if (show == null) continue;
            long at;
            try {
                at = iso.parse(e.optString("airstamp")).getTime();
            } catch (Exception ex) {
                continue;
            }
            if (!streaming && at < now - 30 * 60_000L) continue;
            String name = show.optString("name");
            if (!shows.add(name)) continue;
            JSONObject net = show.optJSONObject("network");
            if (net == null) net = show.optJSONObject("webChannel");
            items.add(new Object[] {at, name, net == null ? "" : net.optString("name")});
        }
        Collections.sort(items, (x, y) -> Long.compare((Long) x[0], (Long) y[0]));
        JSONArray out = new JSONArray();
        WellyCards.Card card = new WellyCards.Card();
        card.type = WellyCards.SCHEDULE;
        List<String[]> rows = new ArrayList<>();
        for (Object[] it : items) {
            if (out.length() >= 20) break;
            String time = Ui.time((Long) it[0]);
            out.put(new JSONObject().put("time", time).put("show", it[1]).put("network", it[2]));
            if (rows.size() < 5) rows.add(new String[] {time, (String) it[1], (String) it[2]});
        }
        card.rows = rows.toArray(new String[0][]);
        Result r = new Result(new JSONObject().put("date", day).put("listings", out).toString());
        if (!rows.isEmpty()) r.cards.add(card);
        return r;
    }

    private static final String[][] LEAGUES = {
        {"nfl", "football/nfl", "NFL"}, {"ncaaf", "football/college-football", "NCAAF"}, {"nba", "basketball/nba", "NBA"},
        {"wnba", "basketball/wnba", "WNBA"}, {"ncaab", "basketball/mens-college-basketball", "NCAAB"},
        {"mlb", "baseball/mlb", "MLB"}, {"nhl", "hockey/nhl", "NHL"}, {"mls", "soccer/usa.1", "MLS"},
        {"epl", "soccer/eng.1", "Premier League"},
    };

    private Result scores(String league) throws Exception {
        String[] l = null;
        for (String[] row : LEAGUES) if (row[0].equalsIgnoreCase(league.trim())) l = row;
        if (l == null) return error("unknown league " + league + "; try nfl, nba, mlb, nhl, mls, wnba, ncaaf, ncaab or epl");
        JSONObject board = new JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/" + l[1] + "/scoreboard", null));
        JSONArray events = board.optJSONArray("events"), out = new JSONArray();
        List<WellyCards.Card> live = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.optJSONArray("competitions") == null ? null : e.getJSONArray("competitions").optJSONObject(0);
            if (comp == null) continue;
            JSONObject status = e.optJSONObject("status");
            JSONObject type = status == null ? null : status.optJSONObject("type");
            String state = type == null ? "" : type.optString("state");
            WellyCards.Card c = new WellyCards.Card();
            c.type = WellyCards.SCORE;
            c.league = l[2];
            c.status = type == null ? "" : type.optString("shortDetail");
            c.live = "in".equals(state);
            JSONArray teams = comp.optJSONArray("competitors");
            for (int j = 0; teams != null && j < teams.length(); j++) {
                JSONObject t = teams.getJSONObject(j);
                JSONObject team = t.optJSONObject("team");
                String abbr = team == null ? "?" : team.optString("abbreviation", "?");
                String pts = "pre".equals(state) ? "" : t.optString("score", "");
                if ("home".equals(t.optString("homeAway"))) {
                    c.home = abbr;
                    c.homeScore = pts;
                } else {
                    c.away = abbr;
                    c.awayScore = pts;
                }
            }
            if (out.length() < 12) {
                out.put(new JSONObject().put("away", c.away).put("home", c.home).put("away_score", c.awayScore)
                        .put("home_score", c.homeScore).put("status", c.status).put("name", e.optString("name")));
            }
            (c.live ? live : rest).add(c);
        }
        Result r = new Result(new JSONObject().put("league", l[2]).put("games", out).toString());
        live.addAll(rest);
        for (WellyCards.Card c : live) if (r.cards.size() < 4) r.cards.add(c);
        return r;
    }

    private Result weather(String place) throws Exception {
        String city = Prefs.city(), lat = Prefs.latitude(), lon = Prefs.longitude();
        if (!place.trim().isEmpty() && !place.trim().equalsIgnoreCase(city)) {
            JSONObject geo = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=" + Ui.language
                    + "&name=" + enc(place.trim()), null));
            JSONArray hits = geo.optJSONArray("results");
            if (hits == null || hits.length() == 0) return error("no place called " + place);
            JSONObject hit = hits.getJSONObject(0);
            city = hit.optString("name", place);
            lat = String.valueOf(hit.optDouble("latitude"));
            lon = String.valueOf(hit.optDouble("longitude"));
        }
        if (lat.isEmpty() || lon.isEmpty()) return error("the TV has no city set");
        JSONObject f = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto&timeformat=unixtime", null));
        JSONObject now = f.getJSONObject("current");
        JSONObject daily = f.getJSONObject("daily");
        double temp = now.optDouble("temperature_2m"), high = daily.getJSONArray("temperature_2m_max").optDouble(0),
                low = daily.getJSONArray("temperature_2m_min").optDouble(0);
        int code = now.optInt("weather_code", -1);
        boolean day = now.optInt("is_day", 1) == 1;
        WellyCards.Card c = new WellyCards.Card();
        c.type = WellyCards.WEATHER;
        c.place = city;
        c.temp = HomeData.degrees(temp);
        c.code = code;
        c.day = day;
        c.condition = HomeData.condition(code, day);
        c.highLow = Ui.trf("H:{max}", "max", HomeData.degrees(high)) + "  " + Ui.trf("L:{min}", "min", HomeData.degrees(low));
        JSONObject hourly = f.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m"), codes = hourly.getJSONArray("weather_code");
        List<Integer> next = new ArrayList<>();
        long nowS = System.currentTimeMillis() / 1000;
        for (int i = 0; i < times.length() && next.size() < 4; i++) if (times.getLong(i) > nowS) next.add(i);
        c.hourLabel = new String[next.size()];
        c.hourTemp = new String[next.size()];
        c.hourCode = new int[next.size()];
        JSONArray hours = new JSONArray();
        for (int n = 0; n < next.size(); n++) {
            int i = next.get(n);
            c.hourLabel[n] = HomeData.hour(times.getLong(i) * 1000);
            c.hourTemp[n] = HomeData.degrees(temps.optDouble(i));
            c.hourCode[n] = codes.optInt(i, -1);
            hours.put(new JSONObject().put("time", c.hourLabel[n]).put("temp", c.hourTemp[n]).put("sky", HomeData.condition(c.hourCode[n], true)));
        }
        Result r = new Result(new JSONObject().put("place", city).put("now", c.temp).put("sky", c.condition)
                .put("high", HomeData.degrees(high)).put("low", HomeData.degrees(low)).put("next_hours", hours).toString());
        r.cards.add(c);
        return r;
    }

    // MARK: - Lights (SmartHome)

    private Result lights(String room, String fixture, boolean on) throws Exception {
        SmartHome.Bridge bridge = SmartHome.get(context);
        List<SmartHome.Device> pick = new ArrayList<>();
        for (SmartHome.Device d : bridge.devices()) {
            String[] id = d.fixture.split("\\.", 2);
            boolean inRoom = room.isEmpty() || "all".equals(room) || id[0].equals(room);
            boolean kind = fixture.isEmpty() || id.length > 1 && id[1].startsWith(fixture);
            if (inRoom && kind) pick.add(d);
        }
        if (bridge.devices().isEmpty()) return error("no lights are set up on this TV (kasa= in config.properties)");
        if (pick.isEmpty()) return error("no light matches " + room + (fixture.isEmpty() ? "" : " " + fixture));
        boolean[] want = new boolean[pick.size()];
        java.util.Arrays.fill(want, on);
        return switchLights(pick, want);
    }

    private Result scene(String name) throws Exception {
        SmartHome.Bridge bridge = SmartHome.get(context);
        List<SmartHome.Device> all = bridge.devices();
        boolean[] want = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            boolean bulb = "bulb".equals(all.get(i).kind) || all.get(i).fixture.endsWith(".bulb");
            want[i] = "bright".equals(name) || "movie".equals(name) && !bulb;
        }
        Result r = all.isEmpty() ? new Result("{\"lights\":\"none set up\"}") : switchLights(all, want);
        if ("sleep".equals(name)) {
            onMain(() -> {
                SleepTimer.set(context, 30);
                return true;
            });
            r.json = new JSONObject(r.json).put("sleep_timer_minutes", 30).toString();
            r.acted = true;
        }
        return r;
    }

    private Result switchLights(List<SmartHome.Device> devices, boolean[] want) throws Exception {
        SmartHome.Bridge bridge = SmartHome.get(context);
        CountDownLatch done = new CountDownLatch(devices.size());
        boolean[] ok = new boolean[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            final int n = i;
            bridge.set(devices.get(i), want[i], (d, answered) -> {
                ok[n] = answered;
                done.countDown();
            });
        }
        done.await(TIMEOUT, TimeUnit.MILLISECONDS);
        JSONArray out = new JSONArray();
        Result r = new Result("");
        for (int i = 0; i < devices.size(); i++) {
            SmartHome.Device d = devices.get(i);
            out.put(new JSONObject().put("light", d.fixture).put("on", want[i]).put("ok", ok[i]).put("problem", d.problem));
            WellyCards.Card c = homeCard(context, d.fixture, want[i]);
            c.ok = ok[i];
            c.problem = ok[i] ? "" : d.problem.isEmpty() ? Ui.tr("No answer") : d.problem;
            r.cards.add(c);
        }
        r.json = new JSONObject().put("lights", out).toString();
        r.acted = true;
        return r;
    }

    /** OK on a home card: the light goes back the other way. Main thread; `then` runs when the device answered. */
    void toggle(WellyCards.Card card, Runnable then) {
        SmartHome.Bridge bridge = SmartHome.get(context);
        SmartHome.Device d = bridge.device(card.fixture);
        if (d == null) {   // Preview, or a light that isn't set up: the card shows the wish
            card.on = !card.on;
            then.run();
            return;
        }
        boolean want = !card.on;
        bridge.set(d, want, (dev, answered) -> {
            card.ok = answered;
            card.on = answered ? want : card.on;
            card.problem = answered ? "" : dev.problem.isEmpty() ? Ui.tr("No answer") : dev.problem;
            then.run();
        });
    }

    private static Map<String, String[]> fixtures;   // "mine.lamp" → {room name, fixture name} from house.json

    static synchronized String[] fixtureName(Context c, String id) {
        if (fixtures == null) {
            fixtures = new HashMap<>();
            try (InputStream in = c.getAssets().open("house.json")) {
                JSONObject house = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                Map<String, String> rooms = new HashMap<>();
                JSONArray r = house.optJSONArray("rooms");
                for (int i = 0; r != null && i < r.length(); i++) rooms.put(r.getJSONObject(i).optString("id"), r.getJSONObject(i).optString("name"));
                JSONArray f = house.optJSONArray("fixtures");
                for (int i = 0; f != null && i < f.length(); i++) {
                    JSONObject o = f.getJSONObject(i);
                    fixtures.put(o.optString("id"), new String[] {rooms.get(o.optString("room")), o.optString("name")});
                }
            } catch (Exception e) {
                AppLog.w("Welly house: " + e.getMessage());
            }
        }
        String[] n = fixtures.get(id);
        return n != null ? n : new String[] {id.split("\\.")[0], id};
    }

    static WellyCards.Card homeCard(Context c, String fixture, boolean on) {
        WellyCards.Card card = new WellyCards.Card();
        card.type = WellyCards.HOME;
        card.fixture = fixture;
        String[] name = fixtureName(c, fixture);
        card.room = name[0] == null ? "" : Ui.tr(name[0]);
        card.title = Ui.tr(name[1]);
        card.on = on;
        return card;
    }

    // MARK: - The TV (apps, inputs, sleep, settings, media sessions)

    private Result launchApp(String name) throws Exception {
        String want = name.trim().toLowerCase(Locale.ROOT);
        String[] best = null;
        int bestScore = 0;
        for (String[] app : HomeApps.launchable(context)) {
            String label = app[1].toLowerCase(Locale.ROOT);
            int score = label.equals(want) ? 3 : label.startsWith(want) ? 2 : label.contains(want) || want.contains(label) ? 1 : 0;
            if (score > bestScore) {
                best = app;
                bestScore = score;
            }
        }
        if (best == null) return error("no app called " + name + " on this TV");
        if (!open(HomeApps.app(best[0], best[1]))) return error(best[1] + " didn't open");
        Result r = new Result(new JSONObject().put("opened", best[1]).toString());
        r.acted = true;
        return r;
    }

    private Result input(String name) throws Exception {
        String want = name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        List<HomeApps.Item> inputs = onMainList(() -> HomeApps.allInputs(context));
        for (HomeApps.Item it : inputs) {
            if (it.label == null || !it.label.toLowerCase(Locale.ROOT).replace(" ", "").contains(want)) continue;
            if (!open(it)) return error(it.label + " didn't open");
            Result r = new Result(new JSONObject().put("switched_to", it.label).toString());
            r.acted = true;
            return r;
        }
        JSONArray names = new JSONArray();
        for (HomeApps.Item it : inputs) names.put(it.label);
        return error("no input called " + name + "; this TV has " + names);
    }

    private Result sleepTimer(int minutes) throws Exception {
        int m = Math.max(0, Math.min(minutes, 240));
        onMain(() -> {
            SleepTimer.set(context, m);
            return true;
        });
        Result r = new Result(new JSONObject().put("sleep_timer_minutes", m).toString());
        r.acted = true;
        return r;
    }

    private Result settings() throws Exception {
        if (!open(HomeApps.tvSettings())) return error("the TV's settings didn't open");
        Result r = new Result("{\"opened\":\"TV Settings\"}");
        r.acted = true;
        return r;
    }

    private Result remember(String fact) throws Exception {
        WellyMemory m = memory;
        if (m == null || fact.trim().isEmpty()) return error("nothing to remember");
        m.addFact(fact);
        return new Result(new JSONObject().put("remembered", fact).toString());
    }

    private MediaController session() {
        MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
        List<MediaController> all = m.getActiveSessions(NowPlaying.component(context));   // SecurityException: not allowed
        for (MediaController mc : all) {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return mc;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private Result whatsPlaying() throws Exception {
        MediaController mc;
        try {
            mc = session();
        } catch (SecurityException e) {
            return error("Now Playing is off in Home Settings, so I can't see what's playing");
        }
        if (mc == null) return new Result("{\"playing\":null,\"note\":\"nothing is playing\"}");
        WellyCards.Card c = musicCard(mc);
        Result r = new Result(new JSONObject().put("title", c.title).put("artist", c.status).put("app", c.meta)
                .put("playing", c.playing).toString());
        r.cards.add(c);
        return r;
    }

    private Result music(String action) throws Exception {
        MediaController mc;
        try {
            mc = session();
        } catch (SecurityException e) {
            return error("Now Playing is off in Home Settings, so I can't control the player");
        }
        if (mc == null) return error("nothing is playing");
        MediaController.TransportControls t = mc.getTransportControls();
        switch (action) {
            case "play": t.play(); break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
            default: t.pause(); break;
        }
        WellyCards.Card c = musicCard(mc);
        if ("play".equals(action) || "pause".equals(action)) c.playing = "play".equals(action);
        Result r = new Result(new JSONObject().put("done", action).put("title", c.title).toString());
        r.cards.add(c);
        r.acted = true;
        return r;
    }

    private WellyCards.Card musicCard(MediaController mc) {
        WellyCards.Card c = new WellyCards.Card();
        c.type = WellyCards.MUSIC;
        MediaMetadata m = mc.getMetadata();
        MediaDescription d = m == null ? null : m.getDescription();
        c.title = d != null && d.getTitle() != null ? d.getTitle().toString() : "";
        c.status = d != null && d.getSubtitle() != null ? d.getSubtitle().toString() : "";
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            c.meta = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
        } catch (Exception e) {
            c.meta = "";
        }
        PlaybackState s = mc.getPlaybackState();
        c.playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        c.pick = null;
        musicPackage = mc.getPackageName();
        return c;
    }

    private volatile String musicPackage;

    /** OK on a music card: play/pause. Main thread. */
    void playPause(WellyCards.Card card) {
        try {
            MediaController mc = session();
            if (mc == null) return;
            if (card.playing) mc.getTransportControls().pause(); else mc.getTransportControls().play();
            card.playing = !card.playing;
        } catch (Exception e) {
            AppLog.w("Welly music: " + e.getMessage());
        }
    }

    // MARK: - Helpers

    interface MainCall<T> { T call(); }

    /** Runs `call` on the main thread and waits for it (at most 6 s). */
    private <T> T onMainResult(MainCall<T> call, T otherwise) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.call();
        Object[] out = {otherwise};
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                out[0] = call.call();
            } catch (Exception e) {
                AppLog.w("Welly: " + e.getMessage());
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private boolean onMain(MainCall<Boolean> call) {
        Boolean b = onMainResult(call, false);
        return b != null && b;
    }

    private List<HomeApps.Item> onMainList(MainCall<List<HomeApps.Item>> call) {
        List<HomeApps.Item> l = onMainResult(call, new ArrayList<>());
        return l == null ? new ArrayList<>() : l;
    }

    private boolean open(HomeApps.Item it) {
        AtomicBoolean ok = new AtomicBoolean();
        onMain(() -> {
            try {
                ok.set(HomeApps.open(activity, it, null));
            } catch (Exception e) {
                AppLog.w("Welly open: " + e.getMessage());
            }
            return ok.get();
        });
        return ok.get();
    }

    private String history() {
        WellyMemory m = memory;
        if (m == null) return "";
        StringBuilder b = new StringBuilder();
        for (String f : m.facts()) b.append(f).append('\n');
        for (String[] t : m.turns()) b.append(t[1]).append('\n');
        for (WatchLog.Entry e : WatchLog.list(context)) b.append(e.title).append('\n');
        return b.toString();
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return out;
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** GETs `url` (6 s timeouts) and returns the body; `auth` is an Authorization header or null. */
    static String get(String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV-Welly");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException(new URL(url).getHost() + " answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** A backdrop for a card (w780 is plenty for a 400 px card), or null. */
    private static Bitmap image(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            try (InputStream in = c.getInputStream()) {
                byte[] b = readAll(in);
                return BitmapFactory.decodeByteArray(b, 0, b.length);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("Welly image: " + e.getMessage());
            return null;
        }
    }

    // MARK: - Preview (assets/welly-preview.json)

    /** One card of the sample conversation; media cards take Preview's sample picks. */
    static WellyCards.Card fromPreview(Context c, JSONObject o) throws JSONException {
        String type = o.optString("type");
        WellyCards.Card card;
        switch (type) {
            case "media": {
                Recs.Pick p = null;
                List<Recs.Pick> all = new ArrayList<>(Preview.picks("movie"));
                all.addAll(Preview.picks("tv"));
                for (Recs.Pick s : all) if (s.backdrop.equals("preview/" + o.optString("pick") + ".jpg")) p = s;
                if (p == null) return null;
                card = new WellyCards.Card();
                card.type = WellyCards.MEDIA;
                card.pick = p;
                card.title = p.title;
                card.meta = p.meta;
                Bitmap b = Preview.image(c, p);
                card.image = b == null ? null : Ui.downscale(b, 640, Math.round(640f * b.getHeight() / b.getWidth()));
                return card;
            }
            case "home":
                return homeCard(c, o.optString("fixture"), o.optBoolean("on"));
            case "score":
                card = new WellyCards.Card();
                card.type = WellyCards.SCORE;
                card.league = o.optString("league");
                card.home = o.optString("home");
                card.away = o.optString("away");
                card.homeScore = String.valueOf(o.opt("homeScore"));
                card.awayScore = String.valueOf(o.opt("awayScore"));
                card.status = o.optString("status");
                card.live = !card.status.toLowerCase(Locale.ROOT).startsWith("final");
                return card;
            case "weather": {
                card = new WellyCards.Card();
                card.type = WellyCards.WEATHER;
                card.place = o.optString("place");
                card.temp = o.optInt("temp") + "°";
                card.code = o.optInt("code", -1);
                card.condition = HomeData.condition(card.code, true);
                card.highLow = Ui.trf("H:{max}", "max", o.optInt("high") + "°") + "  " + Ui.trf("L:{min}", "min", o.optInt("low") + "°");
                JSONArray h = o.optJSONArray("hours");
                int n = h == null ? 0 : Math.min(4, h.length());
                card.hourLabel = new String[n];
                card.hourTemp = new String[n];
                card.hourCode = new int[n];
                for (int i = 0; i < n; i++) {
                    JSONArray e = h.getJSONArray(i);
                    card.hourLabel[i] = e.optString(0);
                    card.hourCode[i] = e.optInt(1, -1);
                    card.hourTemp[i] = e.optInt(2) + "°";
                }
                return card;
            }
            case "schedule": {
                card = new WellyCards.Card();
                card.type = WellyCards.SCHEDULE;
                JSONArray items = o.optJSONArray("items");
                int n = items == null ? 0 : Math.min(5, items.length());
                card.rows = new String[n][];
                for (int i = 0; i < n; i++) {
                    JSONArray e = items.getJSONArray(i);
                    card.rows[i] = new String[] {e.optString(0), e.optString(1), e.optString(2)};
                }
                return card;
            }
            default:
                return null;
        }
    }
}
