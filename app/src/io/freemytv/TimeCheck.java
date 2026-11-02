package io.freemytv;

/**
 * The TV's time zone against the place set for the weather, and the day's sunrise and sunset as instants, which no time
 * zone can move: the sky follows the real sun even on a TV that thinks it's somewhere else (a TCL Google TV came up in
 * GMT, in New Jersey). Android doesn't let an app change the zone, so a wrong one is only pointed out.
 */
final class TimeCheck {
    private TimeCheck() {}

    private static final long DAY = 86_400_000L;
    /** Further than this from the sun's time can't be the place's own zone (western China, the furthest real case, is 2.2 h). */
    private static final double MAX_HOURS = 3.5;

    /** `offsetMinutes` (east of GMT, as TimeZone.getOffset and -Date.getTimezoneOffset) can't be the zone at `longitude`. */
    static boolean zoneLooksWrong(int offsetMinutes, double longitude) {
        double off = Math.abs(offsetMinutes / 60.0 - longitude / 15.0) % 24;
        return Math.min(off, 24 - off) > MAX_HOURS;
    }

    /** As Prefs keeps the longitude: no place, no warning. */
    static boolean zoneLooksWrong(int offsetMinutes, String longitude) {
        try {
            return zoneLooksWrong(offsetMinutes, Double.parseDouble(longitude.trim()));
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }

    /** The sunrise of the sun's day `now` falls in, its night split halfway: any day's, from any day's times (they shift ~1 min a day). */
    static long sunriseOfDay(long now, long sunrise, long sunset) {
        long since = Math.floorMod(now - sunrise, DAY), light = Math.floorMod(sunset - sunrise, DAY);
        long start = now - since;
        return since <= light + (DAY - light) / 2 ? start : start + DAY;
    }

    /** The sunset after that sunrise. */
    static long sunsetAfter(long rise, long sunrise, long sunset) {
        return rise + Math.floorMod(sunset - sunrise, DAY);
    }
}
