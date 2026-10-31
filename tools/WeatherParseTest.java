package io.freemytv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * HomeData's Open-Meteo parser (HomeData.WeatherJson) on tools/weather-fixture.json: the forecast overlay's 24 hours
 * and 7 days, the widget's 6 hours and 5 days, and the nulls Open-Meteo sends (skipped, never NaN or "0°"). The
 * fixture's times are filled in from now, since the parser keeps only what's still ahead. Runs on a plain JVM with
 * tools/testjson ahead of android.jar (whose org.json is only stubs).
 */
public final class WeatherParseTest {
    private static int checks;

    private static void ok(boolean v, String m) {
        if (!v) throw new AssertionError(m);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        String template = new String(Files.readAllBytes(Paths.get("tools/weather-fixture.json")), StandardCharsets.UTF_8);
        SimpleDateFormat hour = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US), day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        hour.setTimeZone(TimeZone.getTimeZone("GMT"));
        day.setTimeZone(TimeZone.getTimeZone("GMT"));
        long now = System.currentTimeMillis(), h = 3_600_000L, d = 24 * h;
        long thisHour = now / h * h, today = now / d * d;
        StringBuilder hours = new StringBuilder("["), days = new StringBuilder("["), rises = new StringBuilder("["), sets = new StringBuilder("[");
        for (int i = 0; i < 48; i++) {   // from the hour before this one: the first two are already over
            hours.append(i == 0 ? "" : ",").append('"').append(hour.format(thisHour + (i - 1) * h)).append('"');
        }
        for (int i = 0; i < 8; i++) {
            String sep = i == 0 ? "" : ",";
            days.append(sep).append('"').append(day.format(today + i * d)).append('"');
            rises.append(sep).append('"').append(hour.format(today + i * d + 6 * h)).append('"');
            sets.append(sep).append('"').append(hour.format(today + i * d + 18 * h)).append('"');
        }
        String json = template.replace("{NOW}", '"' + hour.format(thisHour) + '"').replace("{HOURS}", hours + "]")
                .replace("{DAYS}", days + "]").replace("{SUNRISES}", rises + "]").replace("{SUNSETS}", sets + "]");

        HomeData.Weather w = HomeData.WeatherJson.parse(json);

        // Now.
        ok(w.temp == 12.5, "temperature now");
        ok(w.code == 3, "condition now");
        ok(w.humidity == 55, "humidity");
        ok(w.wind == 10.4, "wind in km/h as Open-Meteo sends it (HomeData.wind converts for °F)");
        ok(w.max == 20 && w.min == 10, "today's high and low");

        // The overlay's 24 hours: only hours ahead, the null one (index 6 of the fixture) skipped, never NaN.
        ok(w.hours24.size() == 24, "24 hours, got " + w.hours24.size());
        long nullHour = thisHour + 5 * h;   // fixture index 6 is the 5th hour after this one
        for (int i = 0; i < w.hours24.size(); i++) {
            ok(w.hours24.get(i) > now, "hour " + i + " is ahead");
            ok(!Double.isNaN(w.hour24Temp.get(i)), "hour " + i + " has a temperature");
            ok(w.hours24.get(i) != nullHour, "the null hour is skipped");
            if (i > 0) ok(w.hours24.get(i) > w.hours24.get(i - 1), "hours in order");
            int rain = w.hour24Rain.get(i);
            ok(rain >= -1 && rain <= 100, "rain is a chance or -1");
        }
        ok(w.hour24Rain.contains(-1), "the null chance of rain is -1");
        ok(w.hour24Temp.size() == 24 && w.hour24Code.size() == 24 && w.hour24Day.size() == 24, "the hour lists line up");

        // The widget's 6 hours, the same rules.
        ok(w.hours.size() == 6, "6 hours for the widget");
        for (double t : w.hourTemp) ok(!Double.isNaN(t), "widget hours have temperatures");

        // The overlay's week: today and the 6 days after it, the day with a null high (index 3) skipped.
        ok(w.week.size() == 7, "7 days, got " + w.week.size());
        for (int i = 0; i < w.week.size(); i++) {
            ok(!Double.isNaN(w.weekMax.get(i)) && !Double.isNaN(w.weekMin.get(i)), "day " + i + " has a high and a low");
        }
        ok(w.weekMax.toString().equals("[20.0, 21.0, 22.0, 24.0, 25.0, 26.0, 27.0]"), "the week's highs, the null day left out: " + w.weekMax);
        ok(w.weekRain.contains(-1), "the null chance of rain is -1");
        ok(w.weekMax.size() == 7 && w.weekMin.size() == 7 && w.weekCode.size() == 7 && w.weekRain.size() == 7, "the day lists line up");

        // The widget's days: from tomorrow, the null day skipped, 5 of them.
        ok(w.days.size() == 5, "5 days for the widget, got " + w.days.size());
        for (double t : w.dayMax) ok(!Double.isNaN(t), "widget days have highs");
        ok(w.dayMax.toString().equals("[21.0, 22.0, 24.0, 25.0, 26.0]"), "the widget's highs from tomorrow: " + w.dayMax);

        System.out.println("WeatherParseTest: " + checks + " checks passed");
    }
}
