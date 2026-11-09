package io.freemytv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The icon beside a sung word (Lyrics' Words style, as Verci's Visual style puts an SF Symbol beside about 770 words):
 * common words of songs mapped by hand to Material Symbols Rounded icons, filled (assets/symbols/w, made by
 * tools/make_symbols.py … words). Only nouns and verbs a picture says plainly; English for now.
 */
final class WordIcons {
    private WordIcons() {}

    private static final Map<String, String> MAP = new HashMap<>();

    private static void put(String icon, String... words) {
        for (String w : words) MAP.put(w, "w/" + icon);
    }

    static {
        put("favorite", "love", "loved", "loving", "lover", "lovers", "heart", "hearts", "kiss", "kisses", "kissed", "valentine");
        put("heart_broken", "heartbreak", "heartbroken", "broken", "breakup");
        put("nights_stay", "night", "nights", "tonight", "midnight", "moon", "moonlight");
        put("dark_mode", "dark", "darkness", "shadow", "shadows");
        put("sunny", "sun", "sunshine", "summer", "sunlight");
        put("star", "star", "stars", "starlight", "stardust");
        put("cloud", "cloud", "clouds", "sky", "skies");
        put("rainy", "rain", "raining", "rainy", "rainfall");
        put("thunderstorm", "storm", "storms", "thunder", "lightning");
        put("ac_unit", "snow", "cold", "ice", "frozen", "freeze", "winter", "freezing");
        put("local_fire_department", "fire", "fires", "burn", "burning", "burned", "burnt", "flame", "flames", "hot", "heat", "blaze");
        put("water_drop", "tears", "tear", "teardrop", "teardrops", "cry", "crying", "cried", "water");
        put("waves", "ocean", "oceans", "sea", "seas", "wave", "waves", "tide", "river");
        put("flight", "fly", "flying", "flew", "plane", "airplane", "wings");
        put("directions_car", "car", "cars", "drive", "driving", "drove", "ride", "riding", "highway");
        put("home", "home", "house");
        put("call", "call", "calling", "called", "phone", "telephone");
        put("music_note", "music", "song", "songs", "sing", "singing", "sang", "melody", "radio", "beat");
        put("headphones", "listen", "listening", "headphones");
        put("mic", "mic", "microphone", "voice", "shout", "scream");
        put("schedule", "time", "minute", "minutes", "hour", "hours", "clock", "seconds", "o'clock");
        put("hourglass_top", "wait", "waiting", "waited");
        put("celebration", "party", "parties", "celebrate", "celebration");
        put("local_bar", "drink", "drinks", "drinking", "drunk", "wine", "whiskey", "champagne", "bottle", "bar", "tequila");
        put("coffee", "coffee", "tea");
        put("payments", "money", "cash", "dollar", "dollars", "rich", "pay", "paid", "bills");
        put("diamond", "diamond", "diamonds", "gold", "golden", "jewel", "jewels", "ring");
        put("public", "world", "earth", "globe", "planet");
        put("visibility", "eyes", "eye", "see", "seeing", "saw", "look", "looking", "watch", "watching");
        put("hearing", "hear", "hearing", "heard");
        put("mood", "smile", "smiling", "happy", "happiness", "laugh", "laughing", "joy");
        put("sentiment_sad", "sad", "sadness", "lonely", "alone", "loneliness");
        put("bolt", "electric", "energy", "shock", "power");
        put("all_inclusive", "forever", "infinity", "always", "eternity", "eternal", "endless");
        put("rocket_launch", "rocket", "rockets", "launch");
        put("auto_awesome", "magic", "dream", "dreams", "dreaming", "dreamer", "shine", "shining", "sparkle", "wish");
        put("bed", "bed", "sleep", "sleeping", "asleep", "pillow");
        put("directions_run", "run", "running", "ran", "runaway", "chase", "chasing", "escape");
        put("pets", "dog", "dogs", "cat", "cats", "puppy");
        put("local_florist", "flower", "flowers", "rose", "roses", "bloom", "blossom", "garden");
        put("park", "tree", "trees", "forest", "woods", "leaves");
        put("landscape", "mountain", "mountains", "hill", "hills", "valley");
        put("beach_access", "beach", "sand", "island");
        put("sailing", "boat", "boats", "sail", "sailing", "ship", "ships");
        put("photo_camera", "camera", "picture", "pictures", "photo", "photograph");
        put("movie", "movie", "movies", "film", "hollywood");
        put("key", "key", "keys");
        put("lock", "lock", "locked", "chains", "cage");
        put("door_front", "door", "doors", "doorway");
        put("mail", "letter", "letters", "mail");
        put("group", "friends", "friend", "crowd", "together", "everybody", "everyone");
        put("child_care", "baby", "babies", "child", "kid", "kids");
        put("warning", "danger", "dangerous", "warning");
        put("shield", "safe", "protect", "shelter");
        put("sports_mma", "fight", "fighting", "fought", "battle", "war");
        put("target", "target", "aim", "bullseye");
        put("history", "yesterday", "past", "memories", "memory", "remember", "remembered");
        put("update", "tomorrow", "future", "someday");
        put("today", "today", "day", "days");
        put("calendar_month", "week", "weeks", "month", "months", "year", "years", "weekend");
        put("spa", "peace", "calm", "breathe", "breath");
        put("skull", "dead", "death", "die", "dying", "died", "kill", "killer", "grave");
        put("medication", "pills", "pill", "medicine", "drugs");
        put("smoking_rooms", "smoke", "smoking", "cigarette", "cigarettes");
        put("church", "church", "pray", "prayer", "praying", "god", "heaven", "angel", "angels");
        put("handshake", "promise", "promised", "deal");
        put("volunteer_activism", "give", "giving", "gave", "care");
        put("crown", "king", "queen", "crown", "royal", "throne");
        put("trophy", "win", "winning", "winner", "champion", "won");
        put("cake", "birthday", "cake");
        put("restaurant", "eat", "eating", "dinner", "food");
        put("fitness_center", "strong", "stronger", "gym");
        put("two_wheeler", "bike", "motorcycle");
        put("train", "train", "trains", "station");
        put("flag", "flag", "flags");
        put("explore", "lost", "wander", "wandering", "north", "south", "compass");
        put("eyeglasses", "glasses");
        put("wb_twilight", "sunset", "sunrise", "dawn", "dusk", "horizon");
        put("psychology", "mind", "think", "thinking", "thought", "thoughts", "brain");
        put("cyclone", "crazy", "spinning", "spin", "tornado", "dizzy");
        put("tsunami", "flood", "drown", "drowning");
    }

    /** The icon for a sung word ("love," → favorite), or null. */
    static String icon(String word) {
        if (word == null || word.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (Character.isLetter(ch) || ch == '\'') sb.append(ch);
        }
        String w = sb.toString().toLowerCase(Locale.ROOT);
        String hit = MAP.get(w);
        if (hit == null && w.endsWith("'")) hit = MAP.get(w.substring(0, w.length() - 1) + "g");   // runnin' → running
        return hit;
    }
}
