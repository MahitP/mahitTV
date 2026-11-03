package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The YouTube tile's videos: what a channel's public feed says, and which of them show. */
final class YouTubeFeedTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static String entry(String id, String title, String channel, String published, boolean shorts) {
        return "<entry><id>yt:video:" + id + "</id><yt:videoId>" + id + "</yt:videoId><yt:channelId>UC1</yt:channelId>"
                + "<title>" + title + "</title><link rel=\"alternate\" href=\"https://www.youtube.com/"
                + (shorts ? "shorts/" + id : "watch?v=" + id) + "\"/><author><name>" + channel + "</name><uri>x</uri></author>"
                + "<published>" + published + "</published><updated>2026-11-02T00:00:00+00:00</updated>"
                + "<media:group><media:title>" + title + "</media:title></media:group></entry>";
    }

    private static String feed(String... entries) {
        return "<?xml version=\"1.0\"?><feed><title>The feed's own title</title><author><name>Feed Author</name></author>"
                + String.join("", entries) + "</feed>";
    }

    public static void main(String[] args) {
        List<YouTubeFeed.Video> v = YouTubeFeed.parse(feed(
                entry("aaa", "First &amp; best", "Chan A", "2026-11-02T10:00:00+00:00", false),
                entry("sss", "A short", "Chan A", "2026-11-02T11:00:00+00:00", true),
                entry("bbb", "It&#39;s &quot;two&quot; &lt;3", "Chan A", "2026-11-01T10:00:00+00:00", false)));
        check(v.size() == 2, "Shorts are left out: " + v.size());
        check(v.get(0).id.equals("aaa"), "the video's id");
        check(v.get(0).title.equals("First & best"), "an entry's own title, not the feed's: " + v.get(0).title);
        check(v.get(0).channel.equals("Chan A"), "the entry's channel, not the feed's author: " + v.get(0).channel);
        check(v.get(0).published.startsWith("2026-11-02"), "when it came out");
        check(v.get(1).title.equals("It's \"two\" <3"), "entities: " + v.get(1).title);
        check(YouTubeFeed.parse("").isEmpty() && YouTubeFeed.parse("<feed></feed>").isEmpty(), "nothing to read");
        check(YouTubeFeed.parse(feed("<entry><title>no id</title></entry>")).isEmpty(), "an entry without an id");

        List<List<YouTubeFeed.Video>> feeds = new ArrayList<>();
        feeds.add(YouTubeFeed.parse(feed(entry("a1", "a1", "A", "2026-11-05T00:00:00+00:00", false),
                entry("a2", "a2", "A", "2026-11-04T00:00:00+00:00", false))));
        feeds.add(YouTubeFeed.parse(feed(entry("b1", "b1", "B", "2026-11-03T00:00:00+00:00", false))));
        feeds.add(YouTubeFeed.parse(feed(entry("c1", "c1", "C", "2026-11-06T00:00:00+00:00", false))));
        feeds.add(new ArrayList<>());
        List<YouTubeFeed.Video> newest = YouTubeFeed.newest(feeds, 2);
        check(newest.size() == 2 && newest.get(0).id.equals("c1") && newest.get(1).id.equals("a1"), "the newest first, one a channel");
        List<YouTubeFeed.Video> all = YouTubeFeed.newest(feeds, 9);
        check(all.size() == 3 && all.get(2).id.equals("b1"), "one a channel, even with room for more: " + all.size());

        check(Arrays.equals(YouTubeFeed.channels(""), YouTubeFeed.CHANNELS), "no list of their own: the built-in channels");
        check(Arrays.equals(YouTubeFeed.channels(" UCa ,UCb,, "), new String[] {"UCa", "UCb"}), "their own list, tidied");
        for (String c : YouTubeFeed.CHANNELS) check(c.startsWith("UC") && c.length() == 24, "a channel id: " + c);
        System.out.println("YouTubeFeedTest: " + checks + " checks passed");
    }
}
