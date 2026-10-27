package io.freemytv;

import android.content.Context;
import android.view.KeyEvent;

import java.io.File;
import java.io.IOException;

/**
 * Voice search in the Apps section. While Apps is on screen, the remote's microphone (search / assistant key) searches
 * the installed apps instead of opening the Assistant. The home screen marks that with a file (it runs in its own
 * process; the key filter, RemoteKeyService, in another); the filter also checks that our window is the one in front,
 * so a mark left behind (a crash) can never take the key away from other apps.
 */
final class AppsVoice {
    private AppsVoice() {}

    /** Broadcast by the key filter to the home screen: the microphone was pressed on the Apps page. */
    static final String ACTION = "io.freemytv.APPS_VOICE";
    /** HomeActivity's request code for the system's speech recognizer. */
    static final int REQUEST = 4701;

    /** The remote's microphone: the search key, or the assistant keys some remotes send for it. */
    static boolean isMic(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_ASSIST;
    }

    private static File mark(Context c) {
        return new File(c.getFilesDir(), "apps-voice");
    }

    /** The Apps page is showing (and the home screen is in front). */
    static boolean active(Context c) {
        return mark(c).exists();
    }

    static void setActive(Context c, boolean on) {
        File f = mark(c);
        try {
            if (on) {
                if (!f.exists()) f.createNewFile();
            } else if (f.exists()) {
                f.delete();
            }
        } catch (IOException | SecurityException ignored) {
        }
    }
}
