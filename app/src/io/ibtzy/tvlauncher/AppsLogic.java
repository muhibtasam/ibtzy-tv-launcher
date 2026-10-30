package io.ibtzy.tvlauncher;

import java.util.TimeZone;

/** The Apps section's Spotlight, without Android: each app's category (the chips) and when it was last opened. */
public final class AppsLogic {
    private AppsLogic() {
    }

    public static final int ALL = 0, WATCH = 1, LISTEN = 2, SPORTS = 3, TOOLS = 4;
    public static final String[] CHIPS = {"All", "Watch", "Listen", "Sports", "Tools"};

    private static final String[][] KNOWN = {
            {"com.netflix", "1"}, {"com.google.android.youtube.tvmusic", "2"}, {"com.google.android.youtube", "1"},
            {"com.amazon.amazonvideo", "1"}, {"com.crunchyroll", "1"}, {"com.stremio", "1"}, {"com.pitelevision.tapmad", "1"},
            {"app.netmirror", "1"}, {"com.community.mbox", "1"}, {"com.nst.iptvsmarters", "3"}, {"org.videolan.vlc", "1"},
            {"com.tcl.ui_mediaCenter", "1"}, {"com.disney", "1"}, {"com.hbo", "1"}, {"com.plexapp", "1"}, {"com.mxtech", "1"},
            {"com.spotify", "2"}, {"com.soundcloud", "2"}, {"deezer", "2"}, {"com.apple.android.music", "2"},
            {"app.cricfy", "3"}, {"com.blaze.sportzfy", "3"}, {"com.tcl.tv", "3"}, {"com.dazn", "3"}, {"espn", "3"}};

    /** Known apps first (by package prefix), then Android's declared category (1 audio, 2 video), else Tools. */
    public static int category(String pkg, int androidCategory) {
        if (pkg != null) for (String[] k : KNOWN) if (pkg.startsWith(k[0]) || pkg.contains(k[0])) return Integer.parseInt(k[1]);
        if (androidCategory == 2) return WATCH;    // ApplicationInfo.CATEGORY_VIDEO
        if (androidCategory == 1) return LISTEN;   // CATEGORY_AUDIO
        return TOOLS;
    }

    public static boolean matches(int chip, int category) {
        return chip == ALL || chip == category;
    }

    /** "Just now", "25 min ago", "3h ago", "Yesterday", "4 days ago", "2 weeks ago"; "" when older, never or unknown. */
    public static String lastUsed(long lastMs, long nowMs, TimeZone tz) {
        if (lastMs <= 0 || lastMs > nowMs) return "";
        long ago = nowMs - lastMs;
        if (ago < 5 * 60_000L) return "Just now";
        if (ago < 3_600_000L) return ago / 60_000L + " min ago";
        long days = Math.floorDiv(nowMs + tz.getOffset(nowMs), 86_400_000L) - Math.floorDiv(lastMs + tz.getOffset(lastMs), 86_400_000L);
        if (days == 0) return ago / 3_600_000L + "h ago";
        if (days == 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        if (days < 35) return days / 7 + " weeks ago";
        return "";
    }
}
