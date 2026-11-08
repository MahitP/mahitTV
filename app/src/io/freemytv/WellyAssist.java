package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Welly as the TV's assistant: the remote's microphone button (and anything that asks for the assistant) opens the home
 * screen on Welly's tab, listening. On Google TV the voice remote hands its mic button straight to the assistant, never as
 * a key, so this is the only way in; mahitTV mode makes it the assistant (HomeMode.assistant), Original gives it back.
 */
public final class WellyAssist extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        AppLog.w("assistant: Welly (" + getIntent().getAction() + ")");
        try {
            startActivity(new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_WELLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("couldn't open Welly: " + e.getMessage());
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
