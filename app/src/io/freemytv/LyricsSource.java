package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics timed to the word (DESIGN-LYRICS.md), as Verci gets them: NetEase Cloud Music's word-timed lyrics ("yrc": each
 * line `[start,length]`, each word `(start,length,0)word`, in ms) for the song whose title, artist and length match, or
 * else its line-timed LRC, else LRCLIB's (lrclib.net) line-timed lyrics; line-timed ones get word timings shared out by
 * each word's length, as Verci's "interpolated from line-level". Only the title, artist and length leave the TV (never
 * audio). Cached on the TV per song (a miss is tried again after 3 days). Network on its own thread, never the UI's.
 */
final class LyricsSource {
    private LyricsSource() {}

    static final class Word {
        final long start, end;
        final String text;
        /** WordIcons' icon beside it, or null. */
        final String icon;

        Word(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
            icon = WordIcons.icon(text);
        }
    }

    static final class Line {
        final long start, end;
        final Word[] words;

        Line(long start, long end, Word[] words) {
            this.start = start;
            this.end = end;
            this.words = words;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(w.text);
            }
            return sb.toString();
        }
    }

    static final class Lyrics {
        final Line[] lines;
        /** Timed to the word by the source itself (else shared out from lines). */
        final boolean wordLevel;

        Lyrics(Line[] lines, boolean wordLevel) {
            this.lines = lines;
            this.wordLevel = wordLevel;
        }
    }

    interface Callback {
        /** On the fetching thread: `lyrics` for the song `key`, or null when there are none. */
        void done(String key, Lyrics lyrics);
    }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)";
    private static final String LRCLIB_UA = "mahitTV (https://github.com/MahitP/mahitTV)";
    private static final long MISS_DAYS = 3;

    /** The song's key: what identifies it for the cache and for a late answer. */
    static String key(String title, String artist, long durationMs) {
        return norm(title) + "|" + norm(artist) + "|" + Math.round(durationMs / 1000.0);
    }

    static void fetch(Context c, String title, String artist, long durationMs, Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = key(title, artist, durationMs);
        NET.execute(() -> {
            Lyrics out = null;
            try {
                out = load(app, title, artist, durationMs, key);
            } catch (Exception e) {
                AppLog.w("lyrics: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            cb.done(key, out);
        });
    }

    // MARK: - Finding them

    private static Lyrics load(Context c, String title, String artist, long durationMs, String key) throws Exception {
        File dir = new File(c.getCacheDir(), "lyrics");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, sha1(key) + ".txt");
        if (f.isFile()) {
            String cached = read(f);
            boolean miss = cached.startsWith("none");
            if (!miss) return parse(cached);
            if (System.currentTimeMillis() - f.lastModified() < MISS_DAYS * 86_400_000L) return null;
        }
        String found = netease(title, artist, durationMs);
        if (found == null) found = lrclib(title, artist, durationMs);
        write(f, found == null ? "none" : found);
        AppLog.w("lyrics: " + (found == null ? "none" : found.substring(0, 3)) + " for a song of " + durationMs / 1000 + " s");
        return found == null ? null : parse(found);
    }

    /** NetEase: the best of up to 3 candidates whose length matches; its word-timed lyrics, else its LRC. */
    private static String netease(String title, String artist, long durationMs) {
        try {
            String q = URLEncoder.encode(cleanTitle(title) + " " + artist, "UTF-8");
            JSONObject res = new JSONObject(get("https://music.163.com/api/search/get?s=" + q + "&type=1&limit=10&offset=0", UA, true));
            JSONArray songs = res.optJSONObject("result") == null ? null : res.getJSONObject("result").optJSONArray("songs");
            if (songs == null) return null;
            List<long[]> ranked = new ArrayList<>();   // {score, id}
            String wantTitle = norm(cleanTitle(title)), wantArtist = norm(artist);
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.getJSONObject(i);
                long d = s.optLong("duration");
                if (durationMs > 0 && d > 0 && Math.abs(d - durationMs) > 4000) continue;
                String name = norm(cleanTitle(s.optString("name")));
                StringBuilder artists = new StringBuilder();
                JSONArray as = s.optJSONArray("artists");
                for (int j = 0; as != null && j < as.length(); j++) artists.append(as.getJSONObject(j).optString("name")).append(' ');
                String art = norm(artists.toString());
                long score = 0;
                if (name.equals(wantTitle)) score += 100; else if (name.contains(wantTitle) || wantTitle.contains(name)) score += 40; else continue;
                if (!wantArtist.isEmpty() && (art.contains(wantArtist) || wantArtist.contains(art.trim()))) score += 60;
                else if (!wantArtist.isEmpty() && sharesWord(art, wantArtist)) score += 25;
                if (durationMs > 0 && d > 0) score += 30 - Math.min(30, Math.abs(d - durationMs) / 100);
                ranked.add(new long[] {score, s.getLong("id")});
            }
            ranked.sort((a, b) -> Long.compare(b[0], a[0]));
            String lineLevel = null;
            for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                JSONObject l = new JSONObject(get("https://music.163.com/api/song/lyric/v1?id=" + ranked.get(i)[1]
                        + "&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0", UA, true));
                String yrc = l.optJSONObject("yrc") == null ? "" : l.getJSONObject("yrc").optString("lyric");
                if (yrc.contains("](")) return "yrc\n" + yrc;
                String lrc = l.optJSONObject("lrc") == null ? "" : l.getJSONObject("lrc").optString("lyric");
                if (lineLevel == null && TIME.matcher(lrc).find()) lineLevel = "lrc\n" + lrc;
            }
            return lineLevel;
        } catch (Exception e) {
            AppLog.w("lyrics: NetEase " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** LRCLIB (lrclib.net): the song by its title, artist and length; its timed lyrics. */
    private static String lrclib(String title, String artist, long durationMs) {
        try {
            String url = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(cleanTitle(title), "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
                    + (durationMs > 0 ? "&duration=" + Math.round(durationMs / 1000.0) : "");
            JSONObject o = new JSONObject(get(url, LRCLIB_UA, false));
            String synced = o.optString("syncedLyrics", "");
            return TIME.matcher(synced).find() ? "lrc\n" + synced : null;
        } catch (Exception e) {
            return null;   // 404: not there
        }
    }

    private static String get(String url, String ua, boolean neteaseHeaders) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(6000);
        h.setReadTimeout(8000);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("User-Agent", ua);
        if (neteaseHeaders) h.setRequestProperty("Referer", "https://music.163.com/");
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 2_000_000) throw new java.io.IOException("too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }

    // MARK: - Reading them

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    /** Credits a lyrics file puts in as lines (作词 : …, Producer: …): not sung. */
    private static final Pattern CREDIT = Pattern.compile("^(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|发行|词|曲|"
            + "lyrics?\\s*by|written\\s*by|composed?\\s*by|composer|lyricist|producers?|produced\\s*by|arranger|mixed\\s*by)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);

    static Lyrics parse(String raw) {
        int nl = raw.indexOf('\n');
        String kind = nl < 0 ? raw : raw.substring(0, nl), body = nl < 0 ? "" : raw.substring(nl + 1);
        return "yrc".equals(kind) ? parseYrc(body) : "lrc".equals(kind) ? parseLrc(body) : null;
    }

    private static Lyrics parseYrc(String body) {
        List<Line> lines = new ArrayList<>();
        for (String row : body.split("\n")) {
            row = row.trim();
            Matcher m = YRC_LINE.matcher(row);
            if (!m.matches()) continue;   // JSON credit rows and the rest
            long ls = Long.parseLong(m.group(1)), ld = Long.parseLong(m.group(2));
            List<Word> words = new ArrayList<>();
            Matcher w = YRC_WORD.matcher(m.group(3));
            while (w.find()) {
                long ws = Long.parseLong(w.group(1)), wd = Long.parseLong(w.group(2));
                String text = w.group(3).trim();
                if (text.isEmpty()) continue;
                if (!words.isEmpty() && isPunct(text)) {   // ", " after a word: the word's, not a word of its own
                    Word last = words.remove(words.size() - 1);
                    words.add(new Word(last.start, Math.max(last.end, ws + wd), last.text + text));
                    continue;
                }
                words.add(new Word(ws, ws + wd, text));
            }
            if (words.isEmpty() || CREDIT.matcher(textOf(words)).find()) continue;
            lines.add(new Line(ls, ls + ld, words.toArray(new Word[0])));
        }
        return lines.isEmpty() ? null : new Lyrics(lines.toArray(new Line[0]), true);
    }

    /** Line-timed lyrics: each line ends where the next one starts (at most 1 s a letter), and its time is shared out. */
    private static Lyrics parseLrc(String body) {
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String row : body.split("\n")) {
            Matcher m = TIME.matcher(row);
            List<Long> at = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) ms += frac.length() == 1 ? Long.parseLong(frac) * 100 : frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
                at.add(ms);
                end = m.end();
            }
            if (at.isEmpty()) continue;
            String text = row.substring(end).replaceAll(GAPS, " ").trim();
            for (long t : at) {
                times.add(new long[] {t});
                texts.add(text);
            }
        }
        // In time order (a line sung twice can carry two tags).
        Integer[] order = new Integer[times.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(times.get(a)[0], times.get(b)[0]));
        List<Line> lines = new ArrayList<>();
        for (int k = 0; k < order.length; k++) {
            String text = texts.get(order[k]);
            if (text.isEmpty() || CREDIT.matcher(text).find()) continue;
            long start = times.get(order[k])[0];
            long next = k + 1 < order.length ? times.get(order[k + 1])[0] : start + 5000;
            String[] parts = splitWords(text);
            int letters = 0;
            for (String p : parts) letters += weight(p);
            long end = Math.min(next, start + Math.max(1200, letters * 380L));
            long span = Math.max(400, (end - start) * 9 / 10), t = start;
            Word[] words = new Word[parts.length];
            for (int i = 0; i < parts.length; i++) {
                long d = span * weight(parts[i]) / Math.max(1, letters);
                words[i] = new Word(t, t + d, parts[i]);
                t += d;
            }
            lines.add(new Line(start, end, words));
        }
        return lines.isEmpty() ? null : new Lyrics(lines.toArray(new Line[0]), false);
    }

    /** Any run of spaces, the Unicode ones too: NetEase's LRC puts four-per-em spaces (U+2005) between some words. */
    private static final String GAPS = "[\\s\\u00A0\\u2000-\\u200B\\u3000]+";

    /** A word's share of its line's time: its letters, and a little for the gap after it. */
    private static int weight(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) if (Character.isLetterOrDigit(w.charAt(i))) n++;
        return n + 2;
    }

    /** Words by spaces; text without spaces (Chinese, Japanese) by character. */
    private static String[] splitWords(String text) {
        String[] parts = text.trim().split(GAPS);
        if (parts.length == 1 && text.length() > 6 && text.codePoints().anyMatch(cp -> Character.isIdeographic(cp))) {
            List<String> chars = new ArrayList<>();
            text.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars.toArray(new String[0]);
        }
        return parts;
    }

    private static boolean isPunct(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return false;
        return true;
    }

    private static String textOf(List<Word> words) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) sb.append(w.text).append(' ');
        return sb.toString().trim();
    }

    // MARK: - Names

    /** "Song (feat. X) - Remastered 2011" → "Song": what a lyrics site files it under. */
    static String cleanTitle(String t) {
        if (t == null) return "";
        String s = t.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|from|remaster|remastered|bonus|explicit|clean)[^)\\]]*[)\\]]", "");
        s = s.replaceAll("(?i)\\s+-\\s+(remaster|remastered|\\d{4} remaster|single version|radio edit|explicit|mono|stereo).*$", "");
        return s.trim();
    }

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean sharesWord(String a, String b) {
        for (String w : b.split(" ")) if (w.length() > 2 && a.contains(w)) return true;
        return false;
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    private static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("lyrics: cache " + e.getMessage());
        }
    }
}
