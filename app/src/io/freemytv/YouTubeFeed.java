package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The YouTube tile's Top Shelf: the newest videos of a few channels, from each channel's public feed
 * (youtube.com/feeds/videos.xml), which needs no account or key. Apart from Recs so it can be tested without a TV.
 */
final class YouTubeFeed {
    private YouTubeFeed() {}

    /** MrBeast, MKBHD, Veritasium, Kurzgesagt, Mark Rober, Linus Tech Tips, Dude Perfect, Apple, NASA, TED: youtube_channels replaces them. */
    static final String[] CHANNELS = {
        "UCX6OQ3DkcsbYNE6H8uQQuVA", "UCBJycsmduvYEL83R_U4JriQ", "UCHnyfMqiRRG1u-2MsSQLbXA", "UCsXVk37bltHxD1rDPwtNM8Q",
        "UCY1kMZp36IQSyNx_9h4mpCg", "UCXuqSBlHAE6Xw-yeJA0Tunw", "UCRijo3ddMTht_IHyNSNXpNQ", "UCE_M8A5yxnLfW0KghEeajjw",
        "UCLA_DiR1FfKNvjuUpBHmylQ", "UCAuUUnT6oDeKwE6v1NGQxug",
    };

    static final class Video {
        final String id, title, channel, published;

        Video(String id, String title, String channel, String published) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.published = published;
        }
    }

    /** config.properties' comma-separated channel ids, or the built-in ones. */
    static String[] channels(String config) {
        List<String> out = new ArrayList<>();
        for (String c : config == null ? new String[0] : config.split(",")) if (!c.trim().isEmpty()) out.add(c.trim());
        return out.isEmpty() ? CHANNELS : out.toArray(new String[0]);
    }

    /** A feed's videos, as it lists them. Shorts are left out: they're vertical, and this is a full-screen photo. */
    static List<Video> parse(String xml) {
        List<Video> out = new ArrayList<>();
        if (xml == null) return out;
        int at = 0;
        while ((at = xml.indexOf("<entry>", at)) >= 0) {
            int end = xml.indexOf("</entry>", at);
            if (end < 0) break;
            String e = xml.substring(at, end);
            at = end;
            String id = between(e, "<yt:videoId>", "</yt:videoId>");
            if (id.isEmpty() || e.contains("/shorts/")) continue;
            String author = between(e, "<author>", "</author>");
            out.add(new Video(id, text(between(e, "<title>", "</title>")), text(between(author, "<name>", "</name>")),
                    between(e, "<published>", "</published>")));
        }
        return out;
    }

    /** The newest video of each channel, the newest first, up to `max`. */
    static List<Video> newest(List<List<Video>> feeds, int max) {
        List<Video> firsts = new ArrayList<>();
        for (List<Video> feed : feeds) if (!feed.isEmpty()) firsts.add(feed.get(0));
        Collections.sort(firsts, (a, b) -> b.published.compareTo(a.published));
        return firsts.size() > max ? new ArrayList<>(firsts.subList(0, max)) : firsts;
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return "";
        a += open.length();
        int b = s.indexOf(close, a);
        return b < 0 ? "" : s.substring(a, b).trim();
    }

    private static String text(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
