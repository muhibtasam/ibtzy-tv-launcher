import io.ibtzy.tvlauncher.AppsLogic;
import java.util.TimeZone;

/** Tests for the Apps section's Spotlight logic (no Android): run by tools/tests/run.sh. */
public class AppsLogicTest {
    private static int failed;
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Karachi");
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    private static void check(String name, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) { failed++; System.out.println("FAIL " + name + ": " + got + " (want " + want + ")"); }
    }

    public static void main(String[] args) {
        // Categories: known packages first, then the app's own declared category (Android's), else Tools.
        check("netflix", AppsLogic.category("com.netflix.ninja", -1), AppsLogic.WATCH);
        check("youtube", AppsLogic.category("com.google.android.youtube.tv", -1), AppsLogic.WATCH);
        check("youtube music", AppsLogic.category("com.google.android.youtube.tvmusic", -1), AppsLogic.LISTEN);
        check("spotify", AppsLogic.category("com.spotify.tv.android", -1), AppsLogic.LISTEN);
        check("cricfy", AppsLogic.category("app.cricfy.tv", -1), AppsLogic.SPORTS);
        check("sportzfy", AppsLogic.category("com.blaze.sportzfy", -1), AppsLogic.SPORTS);
        check("vpn", AppsLogic.category("com.nordvpn.android", -1), AppsLogic.TOOLS);
        check("declared video", AppsLogic.category("org.example.player", 2), AppsLogic.WATCH);   // CATEGORY_VIDEO
        check("declared audio", AppsLogic.category("org.example.radio", 1), AppsLogic.LISTEN);   // CATEGORY_AUDIO
        check("declared game", AppsLogic.category("org.example.game", 0), AppsLogic.TOOLS);       // games: not a chip
        check("unknown", AppsLogic.category("org.example.thing", -1), AppsLogic.TOOLS);
        check("null", AppsLogic.category(null, -1), AppsLogic.TOOLS);
        check("all matches", AppsLogic.matches(AppsLogic.ALL, AppsLogic.LISTEN), true);
        check("filter", AppsLogic.matches(AppsLogic.WATCH, AppsLogic.LISTEN), false);
        check("chip names", String.join(",", AppsLogic.CHIPS), "All,Watch,Listen,Sports,Tools");

        // When it was last opened, in the tile's corner.
        long now = 1_794_510_000_000L + 15 * HOUR;   // mid-afternoon local
        check("just now", AppsLogic.lastUsed(now - 2 * MIN, now, TZ), "Just now");
        check("minutes", AppsLogic.lastUsed(now - 25 * MIN, now, TZ), "25 min ago");
        check("hours", AppsLogic.lastUsed(now - 3 * HOUR, now, TZ), "3h ago");
        check("yesterday", AppsLogic.lastUsed(now - DAY, now, TZ), "Yesterday");
        check("days", AppsLogic.lastUsed(now - 4 * DAY, now, TZ), "4 days ago");
        check("weeks", AppsLogic.lastUsed(now - 20 * DAY, now, TZ), "2 weeks ago");
        check("long ago", AppsLogic.lastUsed(now - 90 * DAY, now, TZ), "");
        check("never", AppsLogic.lastUsed(0, now, TZ), "");
        check("future (clock change)", AppsLogic.lastUsed(now + HOUR, now, TZ), "");

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("AppsLogicTest: all passed");
    }
}
