package io.freemytv;

/** A TV can sit in the wrong time zone (a TCL Google TV came up in GMT, in New Jersey): the check, and a sky that ignores it. */
public final class TimeCheckTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        double newJersey = -74.5, lisbon = -9.1, urumqi = 87.6, auckland = 174.8, apia = -171.8, honolulu = -157.9;
        check(TimeCheck.zoneLooksWrong(0, newJersey), "New Jersey in GMT");
        check(!TimeCheck.zoneLooksWrong(-300, newJersey), "New Jersey in EST");
        check(!TimeCheck.zoneLooksWrong(-240, newJersey), "New Jersey in EDT");
        check(!TimeCheck.zoneLooksWrong(0, lisbon), "Lisbon in winter");
        check(!TimeCheck.zoneLooksWrong(60, lisbon), "Lisbon in summer");
        check(!TimeCheck.zoneLooksWrong(480, urumqi), "Western China keeps Beijing's time, and that's right");
        check(!TimeCheck.zoneLooksWrong(780, auckland), "Auckland in summer");
        check(!TimeCheck.zoneLooksWrong(780, apia), "Samoa, across the date line");
        check(!TimeCheck.zoneLooksWrong(-600, honolulu), "Honolulu");
        check(TimeCheck.zoneLooksWrong(-300, 2.3), "Paris in EST");
        check(!TimeCheck.zoneLooksWrong(0, "not a number"), "no place, no warning");
        check(!TimeCheck.zoneLooksWrong(0, ""), "an empty place, no warning");
        check(TimeCheck.zoneLooksWrong(0, "-74.5"), "as Prefs stores it");

        long hour = 3_600_000L, day = 24 * hour;
        long rise = 1_793_000_000_000L, set = rise + 10 * hour;   // a ten-hour day
        check(TimeCheck.sunriseOfDay(rise + hour, rise, set) == rise, "just after sunrise");
        check(TimeCheck.sunriseOfDay(set + 2 * hour, rise, set) == rise, "after sunset, the same day");
        check(TimeCheck.sunriseOfDay(rise - hour, rise, set) == rise, "just before sunrise");
        check(TimeCheck.sunriseOfDay(rise + 3 * day + hour, rise, set) == rise + 3 * day, "days after the forecast");
        check(TimeCheck.sunriseOfDay(rise - 2 * day + hour, rise, set) == rise - 2 * day, "a clock set back");
        // A summer evening in New Jersey sets after midnight GMT: sunset's time of day is smaller than sunrise's.
        long summerRise = 1_780_000_000_000L, summerSet = summerRise + 15 * hour;
        check(TimeCheck.sunriseOfDay(summerSet - hour, summerRise, summerSet) == summerRise, "summer, before sunset");
        check(TimeCheck.sunriseOfDay(summerSet + hour, summerRise, summerSet) == summerRise, "summer, after sunset");
        check(TimeCheck.sunsetAfter(summerRise, summerRise, summerSet) == summerSet, "sunset follows its sunrise");
        check(TimeCheck.sunsetAfter(summerRise + day, summerRise, summerSet) == summerSet + day, "a day later");
        System.out.println("TimeCheckTest: " + checks + " checks passed");
    }
}
