package io.ibtzy.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * Also: the microphone key on the home screen's Apps page searches the apps by voice (AppsVoice) instead of the
 * Assistant; only while our window is in front and Apps is showing.
 */
public class RemoteKeyService extends AccessibilityService {
    private volatile CharSequence front = "";   // the package of the window in front (window-state events)

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (AppsVoice.isMic(event.getKeyCode()) && getPackageName().contentEquals(front) && AppsVoice.active(this)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sendBroadcast(new Intent(AppsVoice.ACTION).setPackage(getPackageName()));
                AppLog.w("apps: microphone key " + event.getKeyCode() + " → voice search");
            }
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            front = event.getPackageName();
        }
    }

    @Override public void onInterrupt() {}
}
