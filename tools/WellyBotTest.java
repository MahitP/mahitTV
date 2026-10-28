package io.freemytv;

public final class WellyBotTest {
    private static void ok(boolean v, String m) { if (!v) throw new AssertionError(m); }
    private static void near(double a, double b, double e, String m) {
        ok(Math.abs(a - b) <= e, m + ": " + a + " != " + b);
    }
    public static void main(String[] args) {
        ok(WellyBot.PROFILE_SAMPLES == 64, "64 samples");
        ok(WellyBot.COLOR == 0xff2fbfa0, "second green");
        ok(WellyBot.State.values().length == 15, "15 states");
        near(WellyBot.NOTIF_POP, 1.14, 0, "notify pop");
        near(WellyBot.BLINK_DURATION, .18, 0, "blink");
        WellyBot bot = new WellyBot();
        WellyBot.Frame f = new WellyBot.Frame();
        ok(bot.sample(0, f) == f && f.count == 64, "caller-owned frame");
        double[] pebble = f.radii.clone();
        for (WellyBot.State state : WellyBot.State.values()) {
            bot.setState(state, state.ordinal() * 2 + 1);
            bot.sample(state.ordinal() * 2 + 1.8, f);
            for (int i=0;i<64;i++) near(f.radii[i], pebble[i], 1e-12, state + " pebble " + i);
            ok(f.eyeAlpha > 0, state + " face visible");
        }
        ok(WellyBot.Expression.values().length == 16, "16 expressions");
        for (WellyBot.Expression expression : WellyBot.Expression.values()) {
            bot.setExpression(expression, 50 + expression.ordinal());
            bot.sample(50.5 + expression.ordinal(), f);
            ok(f.expression == expression, expression + " sampled");
        }
        bot.setState(WellyBot.State.NOTIFY, 0);
        bot.sample(.9, f);
        near(f.notifScale, 1, 1e-9, "canonical notify");
        bot.setState(WellyBot.State.COMET, 1);
        bot.sample(2.15, f);
        near(f.centerX, 0, 0, "stationary comet center");
        bot.setReducedMotion(true);
        bot.setState(WellyBot.State.BURST, 3);
        bot.sample(3.45, f);
        ok(f.dotCount == 0 && f.arcCount == 0 && f.eyeAlpha > 0, "reduce motion");
        System.out.println("WellyBot Android contract: OK");
    }
}
