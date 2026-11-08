package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    /** Its windows over other apps (the volume and the brightness, KeysOverlay) are made on the first key that needs them. */
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        RemoteKeys.serviceStarted(this);
    }

    @Override public void onDestroy() {
        RemoteKeys.serviceStopped();
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    /** Which window is in front (only its package and class): the home screen shows the volume and brightness itself. */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            RemoteKeys.windowChanged(event.getPackageName(), event.getClassName());
        }
    }

    @Override public void onInterrupt() {}
}
