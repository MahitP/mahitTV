package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Lyrics, as an app of its own (All Apps): LyricsView full screen. OK plays or pauses, ← → the previous or next song,
 * ↑ ↓ the style (Words or Lens), Back leaves. The screen stays on while it's open, as Verci keeps the display awake.
 */
public class LyricsActivity extends Activity {
    private LyricsView view;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF050507));
        view = new LyricsView(this, false, null);
        setContentView(view);
        view.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view != null && view.key(e)) return true;
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        return ours || super.dispatchKeyEvent(e);   // the arrows and OK are Lyrics'; Back, Home and volume pass
    }
}
