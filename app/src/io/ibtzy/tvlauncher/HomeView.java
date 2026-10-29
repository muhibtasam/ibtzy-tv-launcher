package io.ibtzy.tvlauncher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.speech.RecognizerIntent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        Discover.Listener, TitleLaunch.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    // macOS-style dock: square icons (rounded like macOS's, radius 22.37% of the side), the glass hugging them, and
    // magnification: the focused icon grows 50% from its bottom and pops up, its neighbors grow less, and the row
    // spreads (and the glass widens) so nothing overlaps.
    // Compact, in a macOS Dock's proportions (measured: padding 29% of an icon, gaps 33%): 80 px icons, 23 px of glass
    // around them, 26 px apart, floating 44 px above the screen's bottom edge (inside a TV's safe area).
    private static final float SHELF_PAD = 12, TILE_GAP = 16, DOCK_ICON = 56, DOCK_BOTTOM = 1036;
    // iOS 27's icon corner, measured on an iPhone: a radius of 24.5% of the side (19.6 of 80).
    private static final float TILE_RADIUS = 19.6f;
    private static final float DOCK_NEAR = 0.55f, DOCK_FAR = 0.15f, DOCK_POP = 10;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.5f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT). The weather and crypto cards are gone from it
    // (DRAWER_CARDS): the rows took their place at the bottom (they were at 518/540 with SHIFT 560, cards at 708..1008),
    // so the dock rises only by SHIFT and nothing is left empty under the rows. SHIFT 272 (was 218) and the title a
    // little higher: more air between the dock and "Inputs", and between "Inputs" and the cards.
    private static final float SHIFT = 272, ROW_TITLE = 850, ROW_TOP = 884, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    /** The drawer's weather and crypto cards (removed; their code stays, set to true to bring them back). */
    private static final boolean DRAWER_CARDS = false;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    // Apps section (the sheet), top to bottom: heading, search bar, suggestions, then the grid (1080p units). The whole
    // page scrolls as one (scroll: px), so the focused row is never under the screen's edge.
    private static final float APPS_TITLE = 112, SEARCH_TOP = 146, SEARCH_H = 76, SUGGEST_LABEL = 270, CHIPS_TOP = 290,
            CHIP_H = 64, APPS_GRID_TOP = 404;
    /** Where focus is in the Apps section; what a suggestion chip does. */
    private static final int A_SEARCH = 0, A_SUGGEST = 1, A_GRID = 2, CHIP_APP = 0, CHIP_STORE = 1, CHIP_CLEAR = 2;
    private static final int SUGGEST_MAX = 6;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 480;   // crossfades (background, hero): 800 before
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, BOOT = 8, CC = 9, TOPFADE = 10;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Recs.lowPriority(1);   // below the UI: Home's animation first
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    // Even margins: "For You" (its capitals) and the round buttons start 44 from the top, as the dock's glass ends 44
    // from the bottom (DOCK_BOTTOM 1036). Then the tabs, then the grid, which grows down to just above the dock.
    private static final float L_TITLE_BASE = 88, L_TABBAR_TOP = 118, L_TABBAR_H = 72, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 78;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, null, null};
    /**
     * The tabs: Home (picks from the TV's streaming apps), For You (a global discovery feed: Discover) and Apps (the
     * installed apps, with search); the round buttons come after them. Movies and Shows are gone (For You replaced them).
     */
    private static final int TABS = 3, FOR_YOU_TAB = 1, APPS_TAB = 2;
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = Scene.take();   // usually compiled already, during the boot skeleton
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    /** Home Design V1: the dark fade at the top that what scrolled up passes under (drawn once; only its opacity moves). */
    private final Layer topFade;
    /**
     * First start only (no recommendations cached yet): the whole screen as a skeleton, on top of everything, until
     * the first cards arrive (or the list fails, a key is pressed, or BOOT_MAX_MS passes); then it fades out quickly.
     * Never on tab switches or coming back Home: those show what's cached, with per-card skeletons where needed.
     */
    private final Layer bootLayer;
    /** The Control Center (the round Settings button): above everything but the toasts; the screen under it stays. */
    private final Layer ccLayer;
    private boolean booting;
    private long bootFadeAt, bootStartedAt;
    private static final long BOOT_MAX_MS = 8000, BOOT_FADE_MS = 220;
    /**
     * The dock's glass, drawn again once the drawer stops (where it ended up), fading in over the old one: the glass
     * shows the background behind it, and swapping it in one frame was a visible jump at the end of the slide.
     */
    private final Layer platterNext;
    private long glassFadeAt;   // uptime the new glass started fading in; 0: no fade
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[TABS + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[TABS + HomeApps.MAX_TOP];
    // Explore is a 10-card bento grid; Movies and Shows keep their three cards.
    private final Spring[] cardFocus = new Spring[SLOTS];
    private final RectF[] cardRects = new RectF[SLOTS];
    /**
     * Explore's bento grid, 6 columns × 3 rows: {column, row, columns spanned, rows spanned} for each card, the large
     * one first. One 2×2, five 2×1 and four 1×1: every service gets a mix of sizes (Recs.SLOT_SERVICE).
     */
    // 12 cards on one page, no scrolling: 6 columns by 3 rows. {column, row, columns wide, rows tall}: the large card,
    // 3 wide ones and 8 small ones; each row's wide card in another place, so the rows don't line up as stripes.
    private static final int BENTO_COLS = 8;
    // Home: 10 picks as a mosaic in 8 columns × 2 rows (the large card, a tall one, two wide ones in different places and
    // six small ones), then Continue Watching below.
    private static final int[][] BENTO = {
        {0, 0, 2, 2}, {2, 0, 1, 2}, {3, 0, 2, 1}, {5, 0, 1, 1}, {6, 0, 1, 1}, {7, 0, 1, 1},
        {3, 1, 1, 1}, {4, 1, 1, 1}, {5, 1, 2, 1}, {7, 1, 1, 1},
    };
    /** Cards on Home: the Continue row's (0 to ContinueLogic.MAX, first in the list, drawn at the bottom) and the bento's. */
    private static final int SLOTS = ContinueLogic.MAX + BENTO.length;
    // With Continue Watching, one space between what you see: the tab row's bottom (174) and the bento, the bento and the
    // row's heading, the row and the dock's glass (910). The heading sits 24 above its cards (room for a focused card).
    // Home Design V1 (locked 2026-10-27): one 40 px space between the tab row's bottom (174), the bento (214, two rows
    // of 188), Continue Watching (654, 262 tall: its heading column, then four cards) and the dock's glass (its real top
    // is 956: DOCK_BOTTOM less the icon and its padding; measuring to 910 left an ~88 px gap). 24 px between all tiles.
    private static final float V1_GAP = 24, V1_BENTO_TOP = 214, V1_BENTO_H = 400, V1_CONT_TOP = 654, V1_CONT_H = 262;
    private static final float V1_CONT_COL = 296, V1_CONT_COL_GAP = 32;
    private float contHeadBase;   // Continue Watching's top (layoutBento)
    /** How many of the cards are the Continue row's (Home only; 0: the bento has the whole area). */
    private int contShown;
    /** Each card's chip ("New this week", "New season"): only what the title's own data says, on the larger cards. */
    private String[] bentoChips = new String[0];
    // Entering Explore's grid: the background (already the frosted photo) follows the focused card and dims a little.
    private final Spring exploreDim = new Spring(0.45f, 1f, 0);
    private TextPaint bTitleBig, bTitle, bMeta, bNote, bTitleSmall, bMetaSmall;
    private String[] bentoLines = new String[0];
    private int bentoLinesGeneration = -1;
    private android.text.StaticLayout bentoNoteLayout;
    private String bentoNoteText = "";
    private LinearGradient[] bentoShades = new LinearGradient[SLOTS];
    private final float[] bentoShadeH = new float[SLOTS];
    private final Rect bentoSrc = new Rect();
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF(), glassRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    // The dock holds every app: when they don't fit, the row scrolls inside the glass to keep the focused one well in
    // view (All Apps' scroll spring: smooth, no overshoot). Separately, the whole bar slides down out of view after a
    // few idle seconds above it, and back up when focus heads down (the bottom drawer's spring). Both only move cached
    // layers or rects: no glass redraw while they run.
    private final Spring dockScroll = new Spring(0.26f, 1f, 0);   // measured: settles in 20 frames, no overshoot
    private final Spring dockHide = new Spring(0.46f, 0.92f, 0);
    private float[] dockBaseX = new float[0];
    private float dockMaxScroll, dockStep, dockVisibleL, dockVisibleR;
    private int dockVisibleCount;
    private long lastKeyAt;
    /** Idle this long with focus above the dock (Explore's cards, the tabs) and it slides away. */
    private static final long DOCK_HIDE_MS = 4000;
    /** The dock sliding away after DOCK_HIDE_MS idle on the cards or tabs: off, the dock is always shown. */
    private static final boolean DOCK_AUTO_HIDE = false;
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.28f, 1f, 0);   // Apps' page: quick, no bounce
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    // Apps section: every app (appsAll), the ones matching the search (others, what the grid shows), the search and
    // the suggestions under it.
    private List<HomeApps.Item> appsAll = new ArrayList<>();
    private String appsQuery = "";
    private int appsZone = A_GRID, chipIndex;
    private boolean typing;             // the TV's keyboard is up for the search (or the field still has focus)
    private EditText searchInput;       // invisible: it only brings up the TV's keyboard and receives its text
    private final Spring searchFocus = new Spring(0.26f, 0.85f, 0);
    private final Spring keyboardFocus = new Spring(0.26f, 0.85f, 0);   // the keyboard button inside the search bar
    private int searchPart;             // on the search bar: 0 = the bar (OK speaks, or types without voice), 1 = keyboard
    private boolean voiceAvailable;     // the TV has a speech recognizer (looked up, never assumed)
    private boolean voiceReturning;     // the recognizer was opened from here: coming back keeps Apps as it was
    private final List<Chip> chips = new ArrayList<>();
    private List<String> suggestPkgs = new ArrayList<>();
    private String suggestLabel = "";
    private String[] appStore;          // HomeApps.appStore: what can search for apps on this TV, or null
    private final RectF searchRect = new RectF();
    private Paint searchText, searchHint, chipText, appsSection, emptyTitle, emptyText, appName, appNameOn, appLetter;

    /** A suggestion under the search bar: an app, the store's search, or clearing the search. */
    private static final class Chip {
        final int action;
        final HomeApps.Item app;
        final String label;
        final RectF rect = new RectF();
        final Spring focus = new Spring(0.26f, 0.85f, 0);

        Chip(int action, HomeApps.Item app, String label) {
            this.action = action;
            this.app = app;
            this.label = label;
        }
    }
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint, dockLabel;
    // Dock magnification, worked out from the focus springs on each frame: each icon's scale and center, the glass edges.
    private float[] dockS = new float[0], dockCx = new float[0];
    private float dockSpread, drawnSpread = -1;
    // Nothing pops out on the dock until the remote is used there (the home screen opens with it at rest).
    private boolean dockAwake;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }


    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        // Home's layer fades (55%) as the page scrolls: its cards are single cached bitmaps that don't overlap, so the
        // fade can go to each drawing instead of an offscreen copy of the whole layer on every frame.
        @Override public boolean hasOverlappingRendering() {
            return kind != HERO;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case BOOT: drawBoot(c); break;
                case CC: drawCC(c); break;
                case TOPFADE: drawTopFade(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        Startup.mark("HomeView fields ready");   // from onCreate: the class, its fields and the view base
        activity = a;
        long began = Startup.begin();
        data = HomeData.get(a);
        Startup.took("HomeData.get", began);
        began = Startup.begin();
        recs = Recs.get(a);
        Startup.took("Recs.get (cache)", began);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        topFade = new Layer(a, TOPFADE);
        platter = new Layer(a, PLATTER);
        platterNext = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        bootLayer = new Layer(a, BOOT);
        ccLayer = new Layer(a, CC);
        // No hardware layers. They were meant to redraw only the layer that changed, but on a focus move the background
        // crossfades and every glass layer follows it, so each layer was redrawn into its own texture and then
        // composited: twice the GPU work. Measured on the TCL C6K (Mali-G52, Android 14), frame time p50 before → after:
        // Home cards 75.6 → 27.1 ms, Apps 82.4 → 27.2, Movies 67.2 → 46.9, tab switch 96.2 → 63.5; the dock was within
        // its run-to-run noise (38–68 ms either way). Older GPUs (Android 8 to 11) never had them either.
        for (View v : new View[] {heroLayer, status, platter, platterNext, tiles, drawerLayer, sheetLayer, ccLayer}) v.setLayerType(LAYER_TYPE_NONE, null);
        // Except the background (2026-10-28): its photo, a small image stretched over the whole screen through a filter,
        // cost the GPU ~12 ms on every frame of a focus move (Home: 22.7 ms frames, 10.9 without it). In a layer it's
        // rendered once and composited as is; only its crossfades redraw it.
        sky.setLayerType(LAYER_TYPE_HARDWARE, null);
        topFade.setVisibility(INVISIBLE);
        for (View v : new View[] {sky, heroLayer, status, topFade, platter, platterNext, tiles, drawerLayer, sheetLayer, ccLayer, toastLayer, bootLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("ibtzy TV Launcher");
        Startup.mark("HomeView layers ready");
        // Apps' search field (an EditText: the TV's keyboard): made the first time you type (startTyping), not at start.
        // Genuine first load only: the picks are on (a TMDB key) but nothing is cached yet.
        booting = picksOn() && recs.listLoading();
        bootStartedAt = SystemClock.uptimeMillis();
        bootLayer.setVisibility(booting ? VISIBLE : GONE);
        ccLayer.setVisibility(GONE);
        handler.postDelayed(this::findAirplay, 10_000);   // looked up early, so the AirPlay tile is ready when opened
        drawerLayer.setVisibility(INVISIBLE);
        platterNext.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < cardFocus.length; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        began = Startup.begin();
        setLightButtons(HomeApps.topButtons(a));
        Startup.took("top buttons", began);
        began = Startup.begin();
        recs.setLight(true);
        Startup.took("recs.setLight", began);
        began = Startup.begin();
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        Startup.took("night and palette", began);
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        // The dock as last built (from disk, milliseconds); the real build (package queries and icon rendering, seconds
        // at boot) runs in the background (reload) and replaces it. First start ever: an empty dock until then, so the
        // first frame never waits for it.
        began = Startup.begin();
        List<HomeApps.Item> savedDock = HomeApps.savedDockNow(a);   // usually read already, during the boot skeleton
        setShelf(savedDock != null ? savedDock : new ArrayList<>());
        if (savedDock != null) appsDeferredUntil = SystemClock.uptimeMillis() + 20_000;   // its rebuild: in 20 s
        Startup.took("dock apps (saved)", began);
        Startup.line("startup:   dock at first frame: " + (savedDock != null ? savedDock.size() + " apps, saved" : "none saved yet"));
        // The status items and the inputs (TV input queries): in the background, not before the first frame. Neither
        // is on screen at first (the inputs are in the drawer, opened later); they arrive in a moment.
        final Context appContext = a.getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> st = statusList(appContext);
            final List<HomeApps.Item> d = drawerList(appContext);
            handler.post(() -> {
                setStatusItems(st);
                setDrawerItems(d);
            });
        });
        // Fresh start: the same entry state as the Home button (reset): the Home tab selected, focus on it; the dock
        // only without picks (no tabs then). Before, `zone` kept its field default (the dock) until Home was pressed.
        // Resuming (back from an app) doesn't come here: the position is kept.
        tab = 0;
        topIndex = 0;
        shelfIndex = 0;
        dockAwake = false;
        zone = picksOn() ? Z_TOP : Z_SHELF;
        retarget(true);
    }

    // MARK: - Lifecycle (called by HomeActivity)

    /** The return-to-home animation lasts ~300-500 ms on TVs: the heavier work starts after it. */
    private static final long RETURN_SETTLE_MS = 800;
    private boolean afterReturnReload, firstFrameLogged;
    private boolean discoverOn, otherTabsWarm;
    private long appsDeferredUntil;   // uptime: the apps' rebuild waits until then (started with the saved dock)
    private final Runnable deferredReload = () -> {
        appsDeferredUntil = 0;
        if (running) reload();
    };

    /** For You's data (its cache read from disk) and its updates: from the first time they're needed. */
    private void discoverReady() {
        discoverOn = true;
        Discover.get(getContext()).addListener(this);   // once: addListener ignores a second time
    }

    /**
     * Home loads first; the other tabs once it's on screen (1.5 s after its first frame): For You's data, read from
     * its cache in the background and refreshed if stale, so it's there when you switch to it. (Apps' list comes with
     * the dock's background load; its search field when you first type.)
     */
    private void warmOtherTabs() {
        if (otherTabsWarm) return;
        otherTabsWarm = true;
        final Context c = getContext().getApplicationContext();
        handler.postDelayed(() -> LOADER.execute(() -> {
            Discover.get(c);   // its cache, off the main thread
            Games.get(c);      // Games' too
            handler.post(() -> {
                if (!running) return;
                discoverReady();
                Discover.get(c).refreshIfStale();
                Games.get(c).refreshIfStale();
                Startup.mark("other tabs ready (For You)");
            });
        }), 1_500);
    }
    private long resumedNanos;
    private final Runnable afterReturn = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            if (afterReturnReload) reload();
            afterReturnReload = false;
        }
    };

    /**
     * For You back to Movies & Shows, its page at the top, focus on the switch next time, a game's details closed. View
     * state only: nothing reloads (both sections' rows and pictures stay cached).
     */
    private void resetForYou() {
        if (gameSheet != null) closeGameSheet();
        if (gamesOn) setFeedSection(false);
        feedRow = -1;
        feedScrollY.snap(0);
    }

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        // Back on the home screen from an app (Home pressed there, or Back): For You starts again from Movies & Shows.
        // Not done only in onNewIntent: Home pressed in another app may bring this screen back without one (then
        // only this runs). Not after the voice search (that's within Apps), nor for a burst's repeats.
        Startup.games("nav: resume" + (again ? " (burst repeat)" : "") + (voiceReturning ? " (voice)" : "")
                + ", For You was " + (gamesOn ? "Games" : "Movies & Shows"));
        if (!again && !voiceReturning) resetForYou();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        if (tab == FOR_YOU_TAB || discoverOn) discoverReady();   // For You's data: now if open (or already read)
        TitleLaunch.get(getContext()).addListener(this);
        if (tab == FOR_YOU_TAB) Discover.get(getContext()).refreshIfStale();
        deviceName = readDeviceName();
        restartGreeting();
        resumedNanos = System.nanoTime();
        firstFrameLogged = false;
        lastKeyAt = SystemClock.uptimeMillis();
        dockHide.snap(fullPage() ? 1 : 0);   // back Home: the bar is there (not on For You or Apps)
        motionDirty = true;
        // Coming back (Home pressed in an app): only what the first frame needs now. Checking for fresh data, the app
        // list (package queries, icons) and notices wait until the system's return-to-home animation is over, so the
        // home screen's first frames and that animation get the CPU.
        handler.removeCallbacks(afterReturn);
        handler.postDelayed(afterReturn, again ? 0 : RETURN_SETTLE_MS);
        afterReturnReload = !again;
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        watchRadios(true);
        handler.postDelayed(tick, 15_000);
        updatePalette();
        updatePicks();
        if (tab == 0 && !cards.isEmpty()) {   // the check for "Explore reloads": Home's cards still in memory on return?
            int ready = 0;
            for (Recs.Pick p : cards) if (recs.backdrop(p) != null) ready++;
            Startup.games("nav: Home cards in memory " + ready + "/" + cards.size());
        }
        // Back from an app: All Apps (full screen) closes. Apps in its tab stays as it was, and so does Apps after the
        // speech recognizer (it was opened from here).
        if (sheetOpen && !appsInTab() && !voiceReturning) closeSheet();
        voiceReturning = false;
        sheet.snap(sheet.target);
        watchVoiceKey(true);
        updateVoiceMark();
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(afterReturn);
        handler.removeCallbacks(followRested);
        handler.removeCallbacks(feedRested);
        data.removeListener(this);
        recs.removeListener(this);
        if (discoverOn) Discover.get(getContext()).removeListener(this);
        TitleLaunch.get(getContext()).removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        watchRadios(false);
        watchVoiceKey(false);
        updateVoiceMark();      // not running: the microphone is the Assistant's again
    }

    // MARK: - Wi-Fi and Bluetooth buttons (their real state, read again when the system says it changed)

    private boolean radiosWatched;
    private final BroadcastReceiver radioChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshRadios();
        }
    };

    /** While the home screen shows: the system's Wi-Fi, network and Bluetooth broadcasts update the two buttons. */
    private void watchRadios(boolean on) {
        Context c = getContext();
        if (on && !radiosWatched) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            try {
                c.registerReceiver(radioChange, f, null, handler);   // its first, sticky broadcasts read the state now
                radiosWatched = true;
            } catch (RuntimeException e) {
                AppLog.w("can't watch Wi-Fi/Bluetooth: " + e);
            }
            refreshRadios();   // back from their settings pages: the state they were left in
        } else if (!on && radiosWatched) {
            radiosWatched = false;
            handler.removeCallbacks(readRadios);
            try {
                c.unregisterReceiver(radioChange);
            } catch (RuntimeException ignored) {}
        }
    }

    /** Several broadcasts come at once (a network change is Wi-Fi + connectivity): read the state once, after them. */
    private void refreshRadios() {
        handler.removeCallbacks(readRadios);
        handler.postDelayed(readRadios, RADIO_SETTLE_MS);
    }

    private static final long RADIO_SETTLE_MS = 250;
    private final Runnable readRadios = () -> {
        if (!running || lightButtons.isEmpty()) return;
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {   // system calls: off the main thread
            final int wifi = Radios.wifi(c), bluetooth = Radios.bluetooth(c);
            handler.post(() -> {
                for (HomeApps.Item it : lightButtons) {
                    if (HomeApps.TOP_WIFI.equals(it.key)) Radios.show(it, wifi);
                    else if (HomeApps.TOP_BLUETOOTH.equals(it.key)) Radios.show(it, bluetooth);
                }
                statusDirty = true;
                wake();
            });
        });
    };

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        Startup.games("nav: Home (new intent), For You was " + (gamesOn ? "Games" : "Movies & Shows"));
        resetForYou();
        wake();
        closeCC(!animated);
        cancelReorder(!animated);
        // Home on the remote: straight to Explore, with focus on its tab (the dock's first app when there's no Explore).
        zone = picksOn() ? Z_TOP : Z_SHELF;
        topIndex = 0;
        shelfIndex = 0;
        dockAwake = false;
        lastKeyAt = SystemClock.uptimeMillis();
        aimDockScroll(!animated);
        if (!animated) dockHide.snap(0);
        stopTyping();
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= TABS + lightButtons.size()) {
            topIndex = TABS - 1 + lightButtons.size();
            if (topIndex < TABS && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TABS) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Home"), Ui.tr("For You"), Ui.tr("Apps")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        invalidateGlass();
    }

    @Override public void onDiscover() {
        Startup.mark("For You rows ready");
        wake();
        if (tab == FOR_YOU_TAB) {
            heroDirty = true;
            requestTitleLinks();
            if (gamesOn) sky.invalidate();   // the hero's sharp art may be ready now
        }
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, height);   // the whole height: For You's rows reach the bottom (no dock there)
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        measure(topFade, width, Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(platterNext, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(360 * kk));   // room above for the popped icon and its name
        measure(drawerLayer, width, Math.round(300 * kk));   // from 780: the rows' titles, or V1's Inputs (804..1040) and shadows
        measure(sheetLayer, width, height);
        if (searchInput != null) measure(searchInput, Math.round(1660 * kk), Math.round(SEARCH_H * kk));
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(bootLayer, width, height);
        measure(ccLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(topFade, 0, 0);
        place(platter, 0, Math.round(750 * kk));
        place(platterNext, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(720 * kk));
        place(drawerLayer, 0, Math.round(780 * kk));
        sheetLayer.layout(0, 0, width, height);
        if (searchInput != null) place(searchInput, Math.round(130 * kk), Math.round(SEARCH_TOP * kk));
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        bootLayer.layout(0, 0, width, height);
        ccLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 60 * k, L_INK);   // "For You": 60 (was 66, before that 76), same face and color
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 26 * k, L_DIM);       // the tabs: 26 (was 30)
        lTabOn = Ui.text(Ui.semibold, 26 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        // Explore's grid: text on the artwork, over a dark gradient.
        bTitleBig = new TextPaint(Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF));
        bTitle = new TextPaint(Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF));
        bMeta = new TextPaint(Ui.text(Ui.medium, 19 * k, 0xCCFFFFFF));
        bTitleSmall = new TextPaint(Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF));
        bMetaSmall = new TextPaint(Ui.text(Ui.medium, 16 * k, 0xCCFFFFFF));
        bNote = new TextPaint(Ui.text(Ui.medium, 19 * k, 0x99FFFFFF));
        bentoNoteText = "";
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 48 * k, 0xFFFFFFFF);   // "Apps", left-aligned with the grid
        searchText = Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF);
        searchHint = Ui.text(Ui.medium, 28 * k, 0x8CFFFFFF);
        chipText = Ui.text(Ui.medium, 24 * k, 0xF2FFFFFF);
        appsSection = Ui.text(Ui.semibold, 21 * k, 0x99FFFFFF);   // "Recently used": sentence case, a quiet label
        emptyTitle = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
        emptyTitle.setTextAlign(Paint.Align.CENTER);
        emptyText = Ui.text(Ui.medium, 24 * k, 0x99FFFFFF);
        emptyText.setTextAlign(Paint.Align.CENTER);
        appName = Ui.text(Ui.medium, 21 * k, 0x8CFFFFFF);
        appNameOn = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        appLetter = Ui.text(Ui.semibold, 60 * k, 0xE6FFFFFF);
        for (Paint p : new Paint[] {appName, appNameOn}) p.setColorFilter(IVORY_TEXT);
        for (Paint p : new Paint[] {searchText, searchHint, chipText, appsSection, emptyTitle, emptyText}) p.setColorFilter(IVORY_TEXT);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        dockLabel = new TextPaint(Ui.text(Ui.medium, 21 * k, 0xFF1D1D1F));   // sized to the compact dock
        dockLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        // Text in ivory (#FFFFE0) instead of pure white: headlines and body, everywhere on the home screen. A filter
        // on the paint, so every white the code sets (at any opacity) comes out ivory and dark text stays dark. The
        // input cards' text (consoleName, consoleSub) and the icons keep pure white.
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint, lTitle, lTab, lTabOn, lCardTitle,
                lCardMeta, bTitleBig, bTitle, bMeta, bTitleSmall, bMetaSmall, bNote, lQuote, lTip, lDate, lClock, lWeather,
                lClockQuote, capsuleText, tipPaint, quotePaint, staleQuote, rowTitle, drawerLabel, cardTitle, cardText,
                cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, pricePaint, changePaint, sheetTitle, gridLabel,
                toastPaint}) {
            if (p != null) p.setColorFilter(IVORY_TEXT);
        }
    }

    /**
     * The accent: the text's ivory (#FFFFE0), also for active and accent icons and the tabs' focus pill (instead of
     * white). One value: IVORY_TEXT below is made from it. Plain text, artwork and glass highlights stay as they are.
     */
    private static final int ACCENT = 0xFFFFFFE0;
    /** #FFFFE0: white text multiplied by it becomes ivory; black stays black. */
    private static final android.graphics.ColorFilter IVORY_TEXT = new android.graphics.LightingColorFilter(ACCENT & 0xFFFFFF, 0);

    /** The accent at an opacity (for icons that were white at that opacity). */
    private static int accent(float alpha) {
        return Ui.alpha(ACCENT, alpha);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            AppLog.w("home: first frame " + (System.nanoTime() - resumedNanos) / 1_000_000 + " ms after returning");
            Startup.mark(booting ? "first frame (boot skeleton)" : "first frame (home screen)");
            warmOtherTabs();
        }
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (dockScroll.step(dt)) {
            applyDockScroll();
            tilesMoving = true;
        }
        // The bar slides away after a few idle seconds with focus on Explore (its cards or tabs); any focus on it (or
        // moving apps, the drawer, All Apps) keeps it up, and so do the round Wi-Fi/Bluetooth/Settings buttons
        // (Z_TOP from index 3): they're controls of the home screen, not content to watch.
        long idleFor = SystemClock.uptimeMillis() - lastKeyAt;
        boolean onContent = zone == Z_CARDS || (zone == Z_TOP && topIndex < TABS);
        // The dock stays put on every tab (Explore, Movies, Shows): auto-hide is off. DOCK_AUTO_HIDE brings it back.
        boolean away = DOCK_AUTO_HIDE && onContent && !reordering && !sheetOpen && !drawerOpen
                && drawer.value < 0.01f && idleFor > DOCK_HIDE_MS;
        dockHide.target = away || fullPage() ? 1 : 0;   // For You and Apps fill the page: no dock
        boolean hideMoving = dockHide.step(dt);
        if (hideMoving) motionDirty = true;
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt) | stepTabs(dt), cardsMoving = step(cardFocus, dt) | stepFeed(dt);
        boolean dimMoving = exploreDim.step(dt);
        if (dimMoving) sky.invalidate();
        boolean skeleton = bento() && ((cards.size() <= contShown && recs.listLoading()) || cardsWaiting());
        if (skeleton) heroLayer.invalidate();
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean appsSkeleton = appsAll.isEmpty() && appsShowing();   // its pulse: frames only while it's on screen
        boolean gridMoving = appsSkeleton | step(gridFocus, dt) | scroll.step(dt) | searchFocus.step(dt) | keyboardFocus.step(dt) | stepChips(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) {
            tiles.invalidate();
            computeDock();
            // The glass follows the magnified row: redrawn only while its width changes.
            if (Math.abs(dockSpread - drawnSpread) > 1.5f) invalidateGlass();   // not on every sub-pixel
        }
        boolean glassFollows = bgFading && !followFade;   // a card's background: the glass catches up when it ends
        if (statusMoving || statusDirty || glassFollows) status.invalidate();
        boolean greetFading = stepGreeting();
        if (heroMoving || heroDirty || heroFading || greetFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || glassFollows) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if ((drawerMoving && !picksOn()) || bgFading || heroArtMoving) sky.invalidate();   // V1: no veil as the page scrolls
        if (picksOn() && topFade.getVisibility() == VISIBLE && (drawerMoving != drawerWasMoving || (heroDirty && !drawerMoving))) {
            topFade.invalidate();   // its blurred copy: when the page stops or starts, or the cards under it change
        }
        if (glassFollows) {
            invalidateGlass();
            // The tab bar and For You's switch (this layer) and Apps' search bar are glass over the background too:
            // they weren't redrawn through its crossfade, so after a tab switch they kept the last tab's glass.
            heroLayer.invalidate();
            if (appsShowing()) sheetLayer.invalidate();
        }
        if (drawerWasMoving && !drawerMoving) startGlassFade(up);
        boolean glassFading = stepGlassFade(up);
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        boolean boot = stepBoot(up);
        boolean ccMoving = stepCC(dt);
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            heroLayer.invalidate();   // the clock (and, with picks, the weather and time pill)
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = boot || ccMoving || ccOpen || tilesMoving || hideMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || greetFading || bgFading || nightMoving || dimMoving || glassFading || skeleton
                || (!appsInTab() && (sheetOpen || sheet.value > 0.001f))   // Apps in its tab rests like the other tabs
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, Math.min(1000, untilNextEmoji()));
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), sv = Ui.clamp(sheet.value), s = appsInTab() ? 0 : sv;
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        float peek = dc * dc * (3 - 2 * dc);
        if (picksOn()) {
            // Home Design V1: the page scrolls like a web page. Everything rises by SHIFT together (sections keep their
            // spacing), what scrolled up dims to 55% and passes under a dark fade at the top (topFade). Moving and
            // fading layers redraws nothing.
            show(heroLayer, home * (1 - 0.45f * peek), lift);
            show(status, home * (1 - 0.45f * peek), lift);
            show(topFade, home * peek, 0);
        } else {
            show(heroLayer, top, lift);   // no Explore (the clock instead): it fades away as before
            show(status, top, lift);
            show(topFade, 0, 0);
        }
        float hidden = Ui.clamp(dockHide.value) * (h - platterRect.top + 40 * k);   // all the way below the screen
        show(platter, home, lift + hidden);
        platterNext.setTranslationY(lift + hidden);
        show(tiles, home, lift + hidden);
        // V1: scrolled in, not faded in (and nothing of it while closed: its top would show at the screen's bottom edge)
        float da = picksOn() && inputRail ? (dc > 0.002f || drawerOpen ? home : 0) : home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        // Opening: visible (all but transparent) from the first frame, so its content is drawn then, while nothing has
        // moved yet, instead of in the middle of the slide when it fades in (that redraw was a dropped frame).
        boolean drawerUp = da > 0.002f || (drawerOpen && home > 0.5f);
        drawerLayer.setAlpha(Math.max(da, drawerOpen ? 0.004f : 0));
        drawerLayer.setVisibility(drawerUp ? VISIBLE : INVISIBLE);
        // Apps in its tab: it fades in under the tabs, with the drawer's lift (the home screen around it stays).
        sheetLayer.setVisibility(sv > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(appsInTab() ? sv * (1 - smoothstep(0, 0.32f, dc)) : sv);
        sheetLayer.setTranslationY(appsInTab() ? lift : 0);
        float scale = appsInTab() ? 1 : 1.04f - 0.04f * sv;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private LinearGradient topFadeShader;
    private boolean topBlur;

    /**
     * The band at the top of the scrolled page (Home Design V1): what passes under it is blurred (18 px), darkened
     * (rgba(10,9,10,0.85) at the top) and fades out from 45% of its 240 px, as the mockup's backdrop-filter. Android 12+
     * blurs this small layer's own copy of the bento cards under it (from their cached bitmaps): only this 1920x240
     * layer is redrawn while the page moves. Older systems: the fade alone.
     */
    private void drawTopFade(Canvas c) {
        float hh = 240 * k;
        // Blurred once the page has stopped (a blur on every frame of the scroll doubled its frame time: 56 ms against
        // 28); the fade alone while it moves.
        boolean settled = Math.abs(drawer.value - drawer.target) < 0.002f;
        if (android.os.Build.VERSION.SDK_INT >= 31 && topBlur && !settled) {
            topBlur = false;
            topFade.setRenderEffect(null);
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && settled && bento() && tab == 0) {
            if (!topBlur) {
                topBlur = true;
                android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(18 * k, 18 * k, Shader.TileMode.CLAMP);
                android.graphics.RenderEffect mask = android.graphics.RenderEffect.createShaderEffect(new LinearGradient(0, 0.45f * hh, 0, hh,
                        0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                topFade.setRenderEffect(android.graphics.RenderEffect.createBlendModeEffect(blur, mask, android.graphics.BlendMode.DST_IN));
            }
            c.save();
            c.clipRect(0, 0, w, hh);
            c.translate(0, heroLayer.getTranslationY());
            for (int i = contShown; i < Math.min(cards.size(), SLOTS); i++) if (cardRects[i].top < hh - heroLayer.getTranslationY()) drawBentoCard(c, i);
            c.restore();
            fill.setShader(null);
            fill.setColor(0x73000000);   // the page dims to 55% as it scrolls up: the copy too
            c.drawRect(0, 0, w, hh, fill);
        }
        if (topFadeShader == null) topFadeShader = new LinearGradient(0, 0, 0, hh, new int[] {0xD90A090A, 0x780A090A, 0x000A090A},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        fill.setColor(0xFF000000);
        fill.setShader(topFadeShader);
        c.drawRect(0, 0, w, hh, fill);
        fill.setShader(null);
    }

    private static final long GLASS_FADE_MS = 170;
    /** A card's image fading in over its skeleton (250 before). */
    private static final float CARD_REVEAL_MS = 180;

    private void invalidateGlass() {
        platter.invalidate();
        if (glassFadeAt != 0) platterNext.invalidate();
    }

    /** The drawer stopped: the glass where it is now, in the second layer, fading in over the cached one. */
    private void startGlassFade(long up) {
        if (Ui.LEGACY || platter.getVisibility() != VISIBLE) {   // no cached layers there: plain redraw
            platter.invalidate();
            return;
        }
        glassFadeAt = up;
        platterNext.setAlpha(0);
        platterNext.setVisibility(VISIBLE);
        platterNext.invalidate();
    }

    /** Fading: true while it runs. At the end the first layer is redrawn the same and the second one hidden. */
    private boolean stepGlassFade(long up) {
        if (glassFadeAt == 0) return false;
        float t = Math.min(1, (up - glassFadeAt) / (float) GLASS_FADE_MS);
        platterNext.setAlpha(platter.getAlpha() * t * t * (3 - 2 * t));
        if (t < 1) return true;
        glassFadeAt = 0;
        platter.invalidate();                 // now the same as the new one: swapping them shows no change
        platterNext.setVisibility(INVISIBLE);
        return false;
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        followFade = false;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null) {
            // To no photo (Apps): the glass fades from the photo to the background's own gray with the background, not
            // all at once when the fade ends (the search bar's glass went flat a moment after the switch).
            if (bgFrom != null && glassOf(bgFrom) != null) {
                java.util.Arrays.fill(neutralGlassPx, blend(0xFFEBEBF0, 0xFF141416, night));
                scene.setGlassImages(glassOf(bgFrom), neutralGlassPx);
                scene.setGlassMix(0);
            }
            return;
        }
        if (glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        invalidateGlass();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        } else if (bgTo == null && bgFrom != null && glassOf(bgFrom) != null) {
            scene.setGlassMix(e);   // to the plain background (showBackdrop(null))
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (followFade) {   // the glass, once, with the new background
                followFade = false;
                invalidateGlass();
                heroLayer.invalidate();
                status.invalidate();
            }
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }


    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        // The dock: the first DOCK_MAX of the shelf's apps (installed ones, in the saved order). Only what's shown is
        // cut: the saved list keeps all of them (reordering keeps the others in their slots) and Apps lists every app.
        if (list.size() > HomeApps.DOCK_MAX) list = new ArrayList<>(list.subList(0, HomeApps.DOCK_MAX));
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.28f, 0.78f, 0);   // the pop: 13 frames to settle (was 21), 1% bounce
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        invalidateGlass();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Icons always at their size (they used to shrink to fit, then the dock was cut at 10 apps): the glass is as
        // wide as the row, at most the content width less room for the magnified icons; past that, the row scrolls.
        float tw = DOCK_ICON * k, th = tw;
        float width = n * tw + Math.max(0, n - 1) * gap;
        // The glass shows a whole number of icons (it used to be "whatever width fits", so its edge cut an icon in
        // half): as many as fit with room left for the magnified ones; more apps scroll.
        dockStep = tw + gap;
        int fit = Math.max(1, (int) Math.floor((full - 2 * pad - 2 * tw * FOCUS_GROW + gap) / dockStep));
        dockVisibleCount = Math.min(n, fit);
        float visible = n <= fit ? width : fit * dockStep - gap;
        float left = (w - visible) / 2f;
        float bottom = DOCK_BOTTOM * k, top = bottom - th - 2 * pad;
        platterRect.set(left - 2 * pad, top, left + visible + 2 * pad, bottom);   // 24 at the ends, 12 above and below
        dockVisibleL = left;
        dockVisibleR = left + visible;
        dockMaxScroll = n <= fit ? 0 : (n - fit) * dockStep;   // whole icons: it can rest at any of them
        if (dockBaseX.length != n) dockBaseX = new float[n];
        for (int i = 0; i < n; i++) {
            dockBaseX[i] = left + i * dockStep;
            tileRects[i].set(dockBaseX[i], top + pad, dockBaseX[i] + tw, top + pad + th);
        }
        aimDockScroll(true);
        crispLogos.clear();
        computeDock();
    }

    /** Coming down to the dock: the visible app nearest to `x` (never one scrolled out of the glass). */
    private int dockNearest(float x) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < shelf.size() && i < tileRects.length; i++) {
            float cx = tileRects[i].centerX();
            if (dockMaxScroll > 0 && (cx < dockVisibleL || cx > dockVisibleR)) continue;
            float d = Math.abs(cx - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) best = nearest(tileRects, Math.min(shelf.size(), tileRects.length), x);
        final int pick = best;
        handler.post(() -> aimDockScroll(false));   // after shelfIndex is set by the caller
        return pick;
    }

    /**
     * Scrolls by whole icons so the focused app keeps one app of room on each side (or sits at the row's end): the row
     * only ever comes to rest with every icon in the glass whole.
     */
    private void aimDockScroll(boolean snap) {
        if (dockBaseX.length == 0 || shelfIndex >= dockBaseX.length || dockStep <= 0) {
            dockScroll.snap(0);
            applyDockScroll();
            return;
        }
        int n = dockBaseX.length, fit = Math.max(1, dockVisibleCount);
        int first = Math.round(dockScroll.target / dockStep);   // the first whole icon in the glass now
        int room = fit >= 3 ? 1 : 0;
        if (shelfIndex - room < first) first = shelfIndex - room;
        else if (shelfIndex + room > first + fit - 1) first = shelfIndex + room - fit + 1;
        first = Math.max(0, Math.min(n - fit, first));
        float target = first * dockStep;
        dockScroll.target = target;
        if (snap) dockScroll.snap(target);
        applyDockScroll();
        wake();
    }

    /** The tiles where the scroll puts them (screen positions: focus, moves and TalkBack all use them as they are). */
    private void applyDockScroll() {
        for (int i = 0; i < dockBaseX.length && i < tileRects.length; i++) {
            tileRects[i].offsetTo(Math.round(dockBaseX[i] - dockScroll.value), tileRects[i].top);
        }
    }

    private float dockFocus(int i) {
        return i < 0 || i >= tileFocus.length ? 0 : Math.max(0, tileFocus[i].value);
    }

    /** Magnification: each icon's scale from its own focus and its neighbors', and the row spread so none overlap. */
    private void computeDock() {
        int n = shelf.size();
        if (dockS.length != n) {
            dockS = new float[n];
            dockCx = new float[n];
        }
        if (n == 0 || tileRects.length != n || tileFocus.length != n) {
            dockSpread = 0;
            return;
        }
        float bw = tileRects[0].width(), total = 0;
        for (int i = 0; i < n; i++) {
            float m = dockFocus(i) + DOCK_NEAR * (dockFocus(i - 1) + dockFocus(i + 1)) + DOCK_FAR * (dockFocus(i - 2) + dockFocus(i + 2));
            float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
            dockS[i] = 1 + FOCUS_GROW * Math.min(m, 1.2f) + LIFT_GROW * l;   // the focused app and its neighbours grow
            total += bw * (dockS[i] - 1);
        }
        float run = -total / 2;
        for (int i = 0; i < n; i++) {
            float extra = bw * (dockS[i] - 1);
            dockCx[i] = tileRects[i].centerX() + run + extra / 2;
            run += extra;
        }
        dockSpread = total;
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        computeDock();
        drawnSpread = dockSpread;
        float radius = platterRect.height() / 2;   // a full pill, as the tabs
        glassRect.set(platterRect.left - dockSpread / 2, platterRect.top, platterRect.right + dockSpread / 2, platterRect.bottom);
        // Home Design V1: the same glass as the Inputs bar (a very light translucent fill, a faint hairline and a touch
        // of light along the top edge); no refraction, no shadow.
        drawFlatGlass(c, glassRect, radius);
    }

    private LinearGradient glassTopLight;
    private float glassTopLightH;
    private final Matrix glassTopLightM = new Matrix();

    /** Home Design V1's glass (the dock and the Inputs bar): rgba(243,237,226,0.07), a 0.16 hairline, a lit top edge. */
    private void drawFlatGlass(Canvas c, RectF r, float radius) {
        fill.setShader(null);
        fill.setColor(tone(0x99FFFFFF, 0x12F3EDE2));
        c.drawRoundRect(r, radius, radius, fill);
        border.setStrokeWidth(1 * k);
        border.setColor(tone(0x1F000000, 0x29F3EDE2));
        c.drawRoundRect(r, radius, radius, border);
        if (glassTopLight == null || glassTopLightH != r.height()) {   // inset 0 1px 0 rgba(255,255,255,0.06)
            glassTopLight = new LinearGradient(0, 0, 0, Math.min(r.height(), 2 * radius) * 0.5f, 0x0FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            glassTopLightH = r.height();
        }
        glassTopLightM.setTranslate(0, r.top + 1 * k);
        glassTopLight.setLocalMatrix(glassTopLightM);
        border.setShader(glassTopLight);
        tmp.set(r.left + 1 * k, r.top + 1 * k, r.right - 1 * k, r.bottom - 1 * k);
        c.drawRoundRect(tmp, radius - 1 * k, radius - 1 * k, border);
        border.setShader(null);
        border.setStrokeWidth(2 * k);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = TABS; i < TABS + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        computeDock();
        boolean scrolls = dockMaxScroll > 0;
        if (scrolls) {
            // Apps scrolled out of the glass are cut inside its padding, so at rest every icon shown is whole and the
            // next one is entirely out (they're a whole gap past this edge); the magnified ones spread with the glass.
            c.save();
            float inset = SHELF_PAD * k * 0.5f;
            c.clipRect(dockVisibleL - dockSpread / 2 - inset, 0, dockVisibleR + dockSpread / 2 + inset, getHeight() * 2);
        }
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        // Only the apps in (or entering) the glass: with many apps, drawing all of them, each with its shadow, every
        // frame was what made the dock stutter on the TV.
        float visL = scrolls ? dockVisibleL - dockSpread / 2 - dockStep : -Float.MAX_VALUE;
        float visR = scrolls ? dockVisibleR + dockSpread / 2 + dockStep : Float.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float dx0 = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + dx0 < visL || tileRects[i].left + dx0 > visR) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, i < dockCx.length ? dockCx[i] : tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        if (top >= 0 && liftIndex < 0) drawDockLabel(c, top);   // macOS: the name over the magnified icon, no ring
    }

    /** The focused app's name in a small pill above its popped icon, as macOS shows it over the Dock. */
    private void drawDockLabel(Canvas c, int i) {
        float fc = Ui.clamp(tileFocus[i].value);
        if (fc < 0.02f || dockLabel == null || i >= dockS.length) return;
        String name = shelf.get(i).label;
        if (name == null || name.isEmpty()) return;
        RectF base = tileRects[i];
        float iconTop = base.bottom - base.height() * dockS[i] - DOCK_POP * k * tileFocus[i].value;
        dockLabel.setColor(Ui.alpha(tone(0xFF1D1D1F, 0xFFF5F5F7), fc));
        float tw = dockLabel.measureText(name), ph = 38 * k, pw = tw + 30 * k;
        float bottom = iconTop - 10 * k, cx = dockCx[i];
        tmp.set(cx - pw / 2, bottom - ph, cx + pw / 2, bottom);
        tileFill.setShader(null);
        tileFill.setColor(Ui.alpha(tone(0xF2FFFFFF, 0xE62C2C2E), fc));
        c.drawRoundRect(tmp, ph / 2, ph / 2, tileFill);
        c.drawText(name, cx, tmp.centerY() + Ui.halfCap(dockLabel), dockLabel);
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f);
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = bw / 2;   // round icons
        // Magnified from its bottom edge (it grows up out of the glass) and popped up a little when focused.
        float s = i < dockS.length ? dockS[i] : 1;
        float cx = i < dockCx.length ? dockCx[i] : base.centerX();
        float cy = base.bottom - bh * s / 2 - DOCK_POP * k * Math.max(0, f);
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.icon != null) {
            // Dark icons in the dark look (as iOS), with the thin light rim iOS draws around them.
            Bitmap art = dark() > 0.5f && it.iconDark != null ? it.iconDark : it.icon;
            // Pre-rendered at the exact on-screen size, corners smoothed: drawn pixel for pixel at rest and when focused.
            boolean rest = s < 1.02f;   // growing ones shrink from the focused version (sharper than enlarging)
            int size = Math.round(bw * (rest ? 1 : 1 + FOCUS_GROW));
            Bitmap ready = crispIcon(art, size, r0 * size / bw);
            if (ready == null) {
                roundImage(c, art, cell, r);   // its sharp copy is on the way: the plain icon for a frame or two
            } else {
                float scale = s * bw / size;
                if (Math.abs(scale - 1) < 0.003f) {
                    tmp.set(Math.round(cell.left), Math.round(cell.top), Math.round(cell.left) + size, Math.round(cell.top) + size);
                } else {
                    tmp.set(cell);
                }
                c.drawBitmap(ready, null, tmp, bitmapPaint);
            }
            // The thin light rim iOS draws around icons, lit from the top left like the glass; brighter when focused.
            glassEdge(c, cell, r, tone(0.3f, 0.42f) + 0.4f * fc, 1.5f * k * s);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private final Map<String, Bitmap> crispIcons = new HashMap<>();
    private final java.util.Set<String> crispPending = new java.util.HashSet<>();

    /** The icon downscaled in halves (no aliasing) to `size` px and cut to its rounded square with smooth edges. */
    private Bitmap crispIcon(Bitmap art, int size, float radius) {
        String key = System.identityHashCode(art) + "@" + size + "r" + Math.round(radius);
        Bitmap cached = crispIcons.get(key);
        if (cached != null) return cached;
        // Not ready: made in the background (the area resize of 10+ icons cost a long first frame after Home); the
        // caller draws the plain icon meanwhile, and this one replaces it when ready.
        if (!crispPending.add(key)) return null;
        LOADER.execute(() -> {
            Bitmap scaled = areaScale(art, size);
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            new Canvas(out).drawRoundRect(new RectF(0, 0, size, size), radius, radius, p);
            if (scaled != art) scaled.recycle();
            handler.post(() -> {
                // Apps shows every app's icon (plus the focused size): room for them all, so the cache never empties and
                // rebuilds while they're on screen (at 156 px about 0.1 MB each).
                if (crispIcons.size() > 320) crispIcons.clear();
                crispIcons.put(key, out);
                crispPending.remove(key);
                tilesDirty = heroDirty = gridDirty = true;   // the dock, Home and Apps all draw these
                wake();
            });
        });
        return null;
    }

    /**
     * Square image to `size` px by area averaging: each new pixel is the exact average of the source pixels it covers
     * (fractions included). Bilinear resizing near 2:1 skips pixels and left the resting icons' edges jagged.
     */
    private static Bitmap areaScale(Bitmap src, int size) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= size || sh <= size) return Bitmap.createScaledBitmap(src, size, size, true);
        int[] in = new int[sw * sh];
        src.getPixels(in, 0, sw, 0, 0, sw, sh);
        int[] out = new int[size * size];
        float fx = (float) sw / size, fy = (float) sh / size;
        for (int oy = 0; oy < size; oy++) {
            float y0 = oy * fy, y1 = y0 + fy;
            for (int ox = 0; ox < size; ox++) {
                float x0 = ox * fx, x1 = x0 + fx;
                float a = 0, r = 0, g = 0, b = 0, total = 0;
                for (int y = (int) y0; y < Math.min(sh, (int) Math.ceil(y1)); y++) {
                    float wy = Math.min(y + 1, y1) - Math.max(y, y0);
                    for (int x = (int) x0; x < Math.min(sw, (int) Math.ceil(x1)); x++) {
                        float wt = wy * (Math.min(x + 1, x1) - Math.max(x, x0));
                        int p = in[y * sw + x];
                        float pa = (p >>> 24) / 255f * wt;   // premultiplied, so see-through edges don't darken
                        a += pa;
                        r += ((p >> 16) & 255) * pa;
                        g += ((p >> 8) & 255) * pa;
                        b += (p & 255) * pa;
                        total += wt;
                    }
                }
                int A = Math.round(255 * a / Math.max(1e-6f, total));
                if (a <= 0) { out[oy * size + ox] = 0; continue; }
                out[oy * size + ox] = (A << 24) | (Math.round(r / a) << 16) | (Math.round(g / a) << 8) | Math.round(b / a);
            }
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.15f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.8f / lw, bh * 0.62f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.consoles(c);   // PlayStation, Xbox, Nintendo, PC (was: live TV and HDMI ports)
        // No Shortcuts row any more: the dock already holds every app (All Apps' job), and ibtzy TV Launcher's own icon in it
        // opens Home Settings. HomeApps.drawerShortcuts() is kept to bring the row back.
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        // Inputs are wide cards (colored, with the HDMI name), the shortcuts stay square glass buttons: the shortcuts
        // get SHORTCUT_W, the inputs share the rest of the row.
        int ins = drawerInputs, outs = n - drawerInputs;
        float sw = ins > 0 ? Math.min(tw, SHORTCUT_W * k) : tw;
        float shortcuts = outs * sw + Math.max(0, outs - 1) * gap;
        // Alone in the row (no shortcuts), the inputs share it end to end, from margin to margin.
        if (outs == 0) gap = INPUT_GAP * k;
        if (outs == 0 && ins > 0) {   // inputs only: Home Design V1's Editorial rail (heading column, one glass bar)
            float top = V1_IN_TOP * k, pad = 8 * k, g = 8 * k;
            inputBar.set((MARGIN + V1_CONT_COL + V1_CONT_COL_GAP) * k, top, right, top + V1_IN_H * k);
            float sw4 = (inputBar.width() - 2 * pad - (ins - 1) * g) / ins;
            for (int i = 0; i < n; i++) {
                float x = inputBar.left + pad + i * (sw4 + g);
                drawerRects[i].set(x, top + pad, x + sw4, inputBar.bottom - pad);
            }
            inputRail = true;
            drawerDirty = true;
            return;
        }
        inputRail = false;
        float iw = ins == 0 ? 0 : (right - left - (outs > 0 ? shortcuts + groupGap : 0) - (ins - 1) * gap) / ins;
        if (outs > 0) iw = Math.min(CONSOLE_MAX_W * k, iw);
        float x = left, top = ROW_TOP * k;
        // Shortcuts end at the right margin (inputs on the left), never closer to the inputs than groupGap.
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x = Math.max(x - gap + groupGap, right - shortcuts);
            float wi = i < ins ? iw : sw;
            drawerRects[i].set(x, top, x + wi, top + ROW_H * k);
            x += wi + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = 0;   // every row and section starts at its first item
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        dockAwake = true;   // up from the inputs: straight onto an app
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        shelfIndex = 0;          // up from the inputs: the dock's first app, as from Explore
        aimDockScroll(false);    // scrolled along? back to the start, smoothly
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    // MARK: - Shared glass edge and body (the light comes from the top left, as in Shaders.LIQUID)

    private final Paint glassEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glassMatrix = new Matrix();
    private final RectF glassTmp = new RectF();
    private LinearGradient glassEdgeShader, glassBodyShader;

    /**
     * The edge of a small glass surface (a Control Center tile, an app icon): a hairline that catches the light on
     * its top-left edges, fades along the sides and echoes faintly at the bottom right, instead of an even white
     * outline. One gradient in unit space, stretched over the shape (nothing allocated per frame). strength 0 to 1.
     */
    private void glassEdge(Canvas c, RectF r, float radius, float strength, float width) {
        if (strength <= 0.004f || r.width() <= 0 || r.height() <= 0) return;
        if (glassEdgeShader == null) {
            glassEdgeShader = new LinearGradient(0, 0, 1, 1, new int[] {0xFFFFFFFF, 0x2EFFFFFF, 0x1AFFFFFF, 0x6BFFFFFF},
                    new float[] {0, 0.45f, 0.7f, 1}, Shader.TileMode.CLAMP);
            glassEdgePaint.setStyle(Paint.Style.STROKE);
        }
        glassMatrix.setScale(r.width(), r.height());
        glassMatrix.postTranslate(r.left, r.top);
        glassEdgeShader.setLocalMatrix(glassMatrix);
        glassEdgePaint.setShader(glassEdgeShader);
        glassEdgePaint.setStrokeWidth(width);
        glassEdgePaint.setAlpha(Math.round(255 * Math.min(1, strength)));
        glassTmp.set(r);
        glassTmp.inset(width / 2, width / 2);
        float rr = Math.max(0, radius - width / 2);
        c.drawRoundRect(glassTmp, rr, rr, glassEdgePaint);
    }

    /**
     * The body of a glass tile inside a glass panel: a light layer brighter at the top (where the light falls) than
     * at the bottom, not a flat white wash. `top` = its opacity at the top edge (the bottom gets 55% of it).
     */
    private void glassBody(Canvas c, RectF r, float radius, float top) {
        if (top <= 0.004f || r.height() <= 0) return;
        if (glassBodyShader == null) {
            glassBodyShader = new LinearGradient(0, 0, 0, 1, 0xFFFFFFFF, 0x8CFFFFFF, Shader.TileMode.CLAMP);
        }
        glassMatrix.setScale(1, r.height());
        glassMatrix.postTranslate(0, r.top);
        glassBodyShader.setLocalMatrix(glassMatrix);
        glassBodyPaint.setShader(glassBodyShader);
        glassBodyPaint.setAlpha(Math.round(255 * Math.min(1, top)));
        c.drawRoundRect(r, radius, radius, glassBodyPaint);
    }

    /** Focus on a glass tile: a soft even outline under the light-catching edge, so it reads from across the room. */
    private void focusOutline(Canvas c, RectF r, float radius, float f) {
        if (f <= 0.01f) return;
        border.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * f));
        border.setStrokeWidth(1.6f * k);
        glassTmp.set(r);
        glassTmp.inset(0.8f * k, 0.8f * k);
        c.drawRoundRect(glassTmp, radius, radius, border);
        border.setStrokeWidth(2 * k);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        if (inputRail) {
            drawInputsRail(c);
            return;
        }
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (DRAWER_CARDS && Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (DRAWER_CARDS && Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Home Design V1's Inputs (Editorial rail): "Your inputs" heading column, one glass bar, the inputs in it. */
    private void drawInputsRail(Canvas c) {
        if (contHead == null) drawContinueLabelPaints();
        float x = MARGIN * k, lineH = 56 * k * 0.95f, subH = 17 * k * 1.4f;
        float block = 4 * k + 16 * k + 2 * lineH + 16 * k + 2 * subH;
        float y = inputBar.top + (inputBar.height() - block) / 2;
        fill.setShader(null);
        fill.setColor(ACCENT);
        tmp.set(x, y, x + 48 * k, y + 4 * k);
        c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
        y += 4 * k + 16 * k;
        c.drawText(Ui.tr("Plugged"), x, y + lineBase(contHead, lineH), contHead);
        y += lineH;
        c.drawText(Ui.tr("in"), x, y + lineBase(contHeadItalic, lineH), contHeadItalic);
        y += lineH + 16 * k;
        c.drawText(Ui.tr("Consoles that haven't seen"), x, y + lineBase(contSub, subH), contSub);
        c.drawText(Ui.tr("daylight since 2023"), x, y + subH + lineBase(contSub, subH), contSub);
        drawFlatGlass(c, inputBar, 28 * k);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawInputSegment(c, i);
        if (top >= 0) drawInputSegment(c, top);
    }

    private android.graphics.RadialGradient discGloss;
    private final Matrix discGlossM = new Matrix();
    private TextPaint inputName, inputStatus;

    private final Map<String, android.graphics.RadialGradient> inputGlows = new HashMap<>();
    private final Matrix inputGlowM = new Matrix();

    /** The input's colour for its focus glow: vivid PlayStation blue, Xbox green, Switch red, PC purple. */
    private static int inputGlow(String key) {
        switch (key == null ? "" : key) {
            case "console-ps": return 0xF2246BFF;
            case "console-xbox": return 0xF241C83A;
            case "console-nintendo": return 0xF2FF3048;
            default: return 0xF2866CFF;
        }
    }

    /** The logo's colour where it is drawn as a symbol (PC). */
    private static int inputInk(String key) {
        switch (key == null ? "" : key) {
            case "console-ps": return 0xFF7FA6FF;
            case "console-xbox": return 0xFF8EDB6A;
            case "console-nintendo": return 0xFFFF7A82;
            default: return 0xFFAFA6FF;
        }
    }

    /**
     * One input in the bar: its real logo in a round 3D disc, the name, the port and whether there's a signal. Focused:
     * its colour glows up from the bottom left on dark, with an inset accent ring and a soft shadow (it doesn't grow).
     */
    private void drawInputSegment(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        RectF r = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), rr = 22 * k;
        boolean on = it.connected && it.inputId != null;
        if (f > 0.01f) {
            tmp.set(r);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.drawCached(c, tmp, rr, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF18161A, f));
            c.drawRoundRect(r, rr, rr, fill);
            // Home Design V1's glow from the bottom left, grown to half and half: the console's colour over half the
            // segment, fading into the dark over the other half (the mockup's was about 20/80).
            android.graphics.RadialGradient glow = inputGlows.get(it.key);
            if (glow == null) {
                int col = inputGlow(it.key);
                glow = new android.graphics.RadialGradient(0, 0, 1, new int[] {col, (col & 0x00FFFFFF) | 0xCC000000, col & 0x00FFFFFF},
                        new float[] {0, 0.5f, 0.85f}, Shader.TileMode.CLAMP);
                inputGlows.put(it.key, glow);
            }
            inputGlowM.setScale(r.width() * 1.05f, r.height() * 1.6f);
            inputGlowM.postTranslate(r.left, r.bottom + 0.15f * r.height());
            glow.setLocalMatrix(inputGlowM);
            fill.setColor(0xFF000000);
            fill.setShader(glow);
            fill.setAlpha(Math.round(255 * f));
            c.drawRoundRect(r, rr, rr, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            tmp.set(r.left + 1.5f * k, r.top + 1.5f * k, r.right - 1.5f * k, r.bottom - 1.5f * k);
            border.setColor(Ui.alpha(ACCENT, f));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(tmp, rr - 1.5f * k, rr - 1.5f * k, border);
            border.setStrokeWidth(2 * k);
        }
        float pad = 24 * k, d = 72 * k, a = on || f > 0.5f ? 1 : 0.6f;   // focused: full strength, signal or not
        float dx = r.left + pad, dy = r.top + pad;
        // The disc: radial-gradient(circle at 32% 26%, #4A4650, #25232A 48%, #121113), a shadow, a lit edge.
        tmp.set(dx, dy + 10 * k, dx + d, dy + d + 10 * k);
        shadowSoft.setColor(Ui.alpha(0xFF000000, 0.55f * a));
        shadows.drawCached(c, tmp, d / 2, 12 * k, shadowSoft);
        if (discGloss == null) discGloss = new android.graphics.RadialGradient(0.32f, 0.26f, 0.9f, new int[] {0xFF4A4650, 0xFF25232A, 0xFF121113},
                new float[] {0, 0.48f, 1}, Shader.TileMode.CLAMP);
        discGlossM.setScale(d, d);
        discGlossM.postTranslate(dx, dy);
        discGloss.setLocalMatrix(discGlossM);
        fill.setColor(0xFF000000);
        fill.setShader(discGloss);
        c.drawCircle(dx + d / 2, dy + d / 2, d / 2, fill);
        fill.setShader(null);
        border.setStrokeWidth(1 * k);
        border.setColor(0x38FFFFFF);
        c.drawCircle(dx + d / 2, dy + d / 2, d / 2 - 0.5f * k, border);
        border.setStrokeWidth(2 * k);
        Bitmap logo = it.key != null && it.key.startsWith("console-") ? consoleLogo(it.key) : null;
        if (logo != null) {   // the real logo the launcher already uses, fitted inside the disc, never stretched
            float box = d * 0.56f, lw = box, lh = box * logo.getHeight() / Math.max(1f, logo.getWidth());
            if (lh > box) {
                lh = box;
                lw = box * logo.getWidth() / Math.max(1f, logo.getHeight());
            }
            tmp.set(dx + (d - lw) / 2, dy + (d - lh) / 2, dx + (d + lw) / 2, dy + (d + lh) / 2);
            consoleFill.setAlpha(Math.round(255 * a));
            c.drawBitmap(logo, null, tmp, consoleFill);
            consoleFill.setAlpha(255);
        } else if (it.symbol != null) {   // PC/Laptop: the monitor
            Symbols.draw(getContext(), c, it.symbol, dx + d / 2, dy + d / 2, d * 0.46f, Ui.alpha(inputInk(it.key), a));
        }
        if (inputName == null) {
            inputName = new TextPaint(Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF));
            inputStatus = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xBFFFFFFF));
            inputName.setColorFilter(IVORY_TEXT);
            inputStatus.setColorFilter(IVORY_TEXT);
        }
        float room = r.width() - 2 * pad, sb = r.bottom - pad, nb = sb - 17 * k - 10 * k;
        inputName.setAlpha(Math.round(255 * a));
        inputStatus.setAlpha(Math.round(0xBF * a));
        c.drawText(TextUtils.ellipsize(it.label, inputName, room, TextUtils.TruncateAt.END).toString(), r.left + pad, nb, inputName);
        String status = it.inputId == null || it.number == null ? Ui.tr("No HDMI port set")
                : "HDMI " + it.number + " · " + (on ? Ui.tr("Connected") : Ui.tr("No signal"));
        c.drawText(TextUtils.ellipsize(status, inputStatus, room, TextUtils.TruncateAt.END).toString(), r.left + pad, sb, inputStatus);
    }

    /** True when any input on the TV has a signal now (being set up in Settings alone doesn't count). */
    private boolean inputsHaveSignal() {
        for (HomeApps.Item it : drawerItems) {
            boolean input = it.kind == HomeApps.INPUT || (it.key != null && it.key.startsWith("console-"));
            if (!input) return true;   // shortcuts in the drawer: it opens as before
            if (it.connected && it.inputId != null) return true;
        }
        return false;
    }

    private static final float SHORTCUT_W = 190, CONSOLE_MAX_W = 420, INPUT_GAP = 24;
    // Home Design V1's Inputs: at 804 with the page scrolled up by SHIFT (272; 1076 on the page), 236 tall, 40 above the
    // screen's bottom: the heading column, then the four inputs in one glass bar.
    private static final float V1_IN_TOP = 804, V1_IN_H = 236;
    private final RectF inputBar = new RectF();
    private boolean inputRail;

    /**
     * Each card's background as a CSS linear-gradient: {angle in degrees (CSS: 90 = to the right, 135 = to the bottom
     * right)}, its colors, and their stops (null: evenly spaced).
     *   PlayStation: linear-gradient(135deg, #000080, #003791, #00439C)   (reversed: deep navy at the left)
     *   Xbox:        linear-gradient(135deg, #107C10, #9BF00B)            (reversed: lime at the right)
     *   Nintendo:    linear-gradient(135deg, #E60012, #3C3C3C)
     *   PC/Laptop:   an indigo of its own.
     */
    private static final class CssGradient {
        final float angle;
        final int[] colors;
        final float[] stops;

        CssGradient(float angle, int[] colors, float[] stops) {
            this.angle = angle;
            this.colors = colors;
            this.stops = stops;
        }

        /** The same gradient with every color mixed toward white by `amount` (the glass rim around the card). */
        CssGradient lighter(float amount) {
            int[] out = new int[colors.length];
            for (int i = 0; i < out.length; i++) {
                int c = colors[i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                out[i] = 0xFF000000 | Math.round(r + (255 - r) * amount) << 16 | Math.round(g + (255 - g) * amount) << 8
                        | Math.round(b + (255 - b) * amount);
            }
            return new CssGradient(angle, out, stops);
        }

        /** The shader for box r, with CSS's gradient line: through the centre, long enough to reach both corners. */
        LinearGradient shader(RectF r) {
            double a = Math.toRadians(angle);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float half = (Math.abs(r.width() * dx) + Math.abs(r.height() * dy)) / 2;
            return new LinearGradient(r.centerX() - dx * half, r.centerY() - dy * half, r.centerX() + dx * half,
                    r.centerY() + dy * half, colors, stops, Shader.TileMode.CLAMP);
        }
    }

    private static CssGradient consoleColors(String key) {
        switch (key) {
            case "console-ps": return new CssGradient(135, new int[] {0xFF000080, 0xFF003791, 0xFF00439C}, null);
            case "console-xbox": return new CssGradient(135, new int[] {0xFF107C10, 0xFF9BF00B}, null);
            case "console-nintendo": return new CssGradient(135, new int[] {0xFFE60012, 0xFF3C3C3C}, null);
            default: return new CssGradient(135, new int[] {0xFF1F2250, 0xFF33388A, 0xFF4F55C2}, null);
        }
    }

    private final Map<String, Bitmap> consoleLogos = new HashMap<>();

    /**
     * The logo at the card's left: assets/console/<ps|xbox|nintendo>.png (your logos, white on transparent, 160 px;
     * the large originals are in tools/console-originals). Kept in proportion. None: the card's icon, if any. Read once.
     */
    private Bitmap consoleLogo(String key) {
        String name = key.substring("console-".length());
        if (consoleLogos.containsKey(name)) return consoleLogos.get(name);
        Bitmap b = null;
        try (java.io.InputStream in = getContext().getAssets().open("console/" + name + ".png")) {
            b = android.graphics.BitmapFactory.decodeStream(in);
        } catch (java.io.IOException ignored) {
        }
        consoleLogos.put(name, b);
        return b;
    }

    private Paint consoleName, consoleSub;
    private final Matrix shaderMatrix = new Matrix();
    private final Map<String, LinearGradient[]> consoleShaders = new HashMap<>();

    /** A card's gradient and sheen, made once for its resting place (again only if the layout moves it). */
    private LinearGradient[] consoleShaders(String key, RectF place) {
        // Made in the card's own box (0, 0, w, h) and moved there by the local matrix: the same shaders wherever the card
        // is (keyed by position, a scrolling page would have made new ones, and grown the map, on every frame).
        int w = Math.round(place.width()), h = Math.round(place.height());
        String id = key + "@" + w + "x" + h;
        LinearGradient[] g = consoleShaders.get(id);
        if (g == null) {
            RectF r = new RectF(0, 0, w, h);
            g = new LinearGradient[] {
                consoleColors(key).shader(r),
                new LinearGradient(0, r.top, 0, r.top + r.height() * 0.5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP),
                consoleColors(key).lighter(0.16f).shader(r),    // rim at rest: the card's own colors, a touch lighter
                consoleColors(key).lighter(0.30f).shader(r)};   // focused: the same hues, brighter (never white)
            if (consoleShaders.size() > 16) consoleShaders.clear();
            consoleShaders.put(id, g);
        }
        return g;
    }
    private final Paint consoleFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * A console input as a colored card: its icon (or your logo) on the left, the name, and below it a dot and the HDMI
     * port (green dot: a signal right now). Not detected: the same card, faded, with a grey dot. Focused: it grows a
     * little, with a deeper shadow and a white ring (the color stays).
     */

    /**
     * A console card: painted once into three small bitmaps (glow, card, focus rim) and drawn scaled and faded on each
     * frame of the focus animation, so the text and the blurred shadow aren't painted again at every size (that made the
     * zoom jerk). Bitmaps work on every Android version and in every kind of layer. If one can't be made (memory), the
     * card is painted directly.
     */
    private void drawConsoleTile(Canvas c, int i, HomeApps.Item it) {
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.05f * f;
        ConsoleCache n = consoleCache.get(it.key);
        if (n == null) consoleCache.put(it.key, n = new ConsoleCache());
        if (n.update(it, drawerRects[i])) {
            n.draw(c, drawerRects[i], f, s);
            return;
        }
        paintConsole(c, it, drawerRects[i], f, s, P_SHADOW | P_BODY | P_RIM);
    }

    private static final int P_SHADOW = 1, P_BODY = 2, P_RIM = 4;
    private final Map<String, ConsoleCache> consoleCache = new HashMap<>();
    private final Paint cachePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private final class ConsoleCache {
        Bitmap glow, card, rim;
        int left, top, margin;
        String sig;

        /** Painted again only when what the card shows changes (name, port, signal, logo, place). False: no bitmaps. */
        boolean update(HomeApps.Item it, RectF base) {
            String now = it.label + "|" + it.number + "|" + it.connected + "|" + (it.inputId != null) + "|"
                    + Math.round(base.width()) + "x" + Math.round(base.height()) + "|" + (consoleLogo(it.key) != null) + "|" + k;
            if (now.equals(sig) && card != null) return true;
            sig = now;
            margin = Math.round(64 * k);   // room for the shadow and the glow around the card
            int m = margin;
            left = Math.round(base.left) - m;   // where it's painted from; drawn wherever the card is now (draw)
            top = Math.round(base.top) - m;
            int bw = Math.round(base.right) + m - left, bh = Math.round(base.bottom) + m - top;
            try {
                glow = paint(glow, bw, bh, it, base, 1, P_SHADOW);
                card = paint(card, bw, bh, it, base, 0, P_SHADOW | P_BODY);
                rim = paint(rim, bw, bh, it, base, 1, P_RIM);
                return true;
            } catch (OutOfMemoryError | RuntimeException e) {
                AppLog.w("console card cache: " + e);
                glow = card = rim = null;
                return false;
            }
        }

        private Bitmap paint(Bitmap b, int bw, int bh, HomeApps.Item it, RectF base, float f, int parts) {
            if (b == null || b.getWidth() != bw || b.getHeight() != bh) b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            else b.eraseColor(0);
            Canvas bc = new Canvas(b);
            bc.translate(-left, -top);
            paintConsole(bc, it, base, f, 1, parts);
            return b;
        }

        /** Focus f: the glow and the bright rim fade in, all three grow together around the card's centre. */
        void draw(Canvas c, RectF base, float f, float s) {
            int left = Math.round(base.left) - margin, top = Math.round(base.top) - margin;
            c.save();
            c.scale(s, s, base.centerX(), base.centerY());
            if (f > 0.004f) {
                cachePaint.setAlpha(Math.round(255 * f));
                c.drawBitmap(glow, left, top, cachePaint);
            }
            cachePaint.setAlpha(255);
            c.drawBitmap(card, left, top, cachePaint);
            if (f > 0.004f) {
                cachePaint.setAlpha(Math.round(255 * f));
                c.drawBitmap(rim, left, top, cachePaint);
            }
            cachePaint.setAlpha(255);
            c.restore();
        }
    }

    /** Paints a console card (or some of its parts) at focus f and size s, in screen coordinates. */
    private void paintConsole(Canvas c, HomeApps.Item it, RectF base, float f, float s, int parts) {
        if (consoleName == null) {
            consoleName = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
            consoleSub = Ui.text(Ui.medium, 19 * k, 0xFFFFFFFF);
        }
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        boolean on = it.connected && it.inputId != null;
        float a = 1;   // the brand gradient always shows in full; not connected, it's dimmed by a dark veil below
        if ((parts & P_SHADOW) != 0) {
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            // Resting: a soft dark shadow. Focused: it turns into a glow in the card's own color (no white anywhere).
            int[] tint = consoleColors(it.key).colors;
            shadow.setColor(blend(Ui.alpha(0xFF000000, on ? 0.22f : 0.1f), Ui.alpha(tint[tint.length / 2], 0.55f), f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        LinearGradient[] g = consoleShaders(it.key, base);
        shaderMatrix.setTranslate(base.left, base.top);   // from the card's own box to its place
        shaderMatrix.postScale(s, s, cx, cy);             // the focus growth, applied to the card's own gradients
        g[0].setLocalMatrix(shaderMatrix);
        g[1].setLocalMatrix(shaderMatrix);
        g[2].setLocalMatrix(shaderMatrix);
        g[3].setLocalMatrix(shaderMatrix);
        if ((parts & P_RIM) != 0 && (parts & P_BODY) == 0) {   // the focus rim alone (its own layer)
            consoleFill.setStyle(Paint.Style.STROKE);
            consoleFill.setStrokeWidth((2 + 2.5f * f) * k);
            consoleFill.setShader(g[3]);
            consoleFill.setAlpha(255);
            c.drawRoundRect(cell, r, r, consoleFill);
            consoleFill.setShader(null);
            consoleFill.setStyle(Paint.Style.FILL);
            return;
        }
        if ((parts & P_BODY) == 0) return;
        consoleFill.setShader(g[0]);
        consoleFill.setAlpha(Math.round(255 * a));
        c.drawRoundRect(cell, r, r, consoleFill);
        // Glass sheen: light from the top, and a hairline edge.
        consoleFill.setShader(g[1]);
        consoleFill.setAlpha(Math.round(255 * a));
        c.drawRoundRect(cell, r, r, consoleFill);
        // Glass rim in the card's own gradient (no white outline): lit at rest, thicker and brighter with focus.
        consoleFill.setStyle(Paint.Style.STROKE);
        consoleFill.setStrokeWidth((2 + 2.5f * f) * k);
        consoleFill.setShader(g[2]);
        consoleFill.setAlpha(Math.round(255 * (0.85f * a + 0.15f * f)));
        c.drawRoundRect(cell, r, r, consoleFill);
        if (f > 0.01f && (parts & P_RIM) != 0) {
            consoleFill.setShader(g[3]);
            consoleFill.setAlpha(Math.round(255 * f));
            c.drawRoundRect(cell, r, r, consoleFill);
        }
        consoleFill.setShader(null);
        consoleFill.setStyle(Paint.Style.FILL);
        consoleFill.setAlpha(255);
        if (!on) {   // not connected: the same colors, darker (disabled), never grey glass
            fill.setShader(null);
            fill.setColor(0x2E000000);   // light: the brand colors stay vivid; the grey dot says it's off
            c.drawRoundRect(cell, r, r, fill);
        }
        // Logo (if you added one), then the name and, on its own line, the HDMI port.
        int ink = Ui.alpha(0xFFFFFFFF, on ? 1 : 0.7f);
        float pad = 24 * k * s, icon = 54 * k * s, tx = cell.left + pad;
        Bitmap logo = consoleLogo(it.key);
        if (logo != null) {
            float lh = icon, lw = Math.min(icon * 1.5f, lh * logo.getWidth() / Math.max(1f, logo.getHeight()));
            lh = lw * logo.getHeight() / Math.max(1f, logo.getWidth());   // never stretched
            tmp.set(tx, cy - lh / 2, tx + lw, cy + lh / 2);
            consoleFill.setAlpha(Math.round(255 * (on ? 1 : 0.7f)));
            c.drawBitmap(logo, null, tmp, consoleFill);
            consoleFill.setAlpha(255);
            tx += lw + 18 * k * s;
        } else if (it.symbol != null) {   // PC/Laptop: the monitor icon
            Symbols.draw(getContext(), c, it.symbol, tx + icon / 2, cy, icon * 0.9f, ink);
            tx += icon + 18 * k * s;
        }
        float room = cell.right - pad - tx;
        consoleName.setTextSize(26 * k * s);
        float wide = consoleName.measureText(it.label);   // "Nintendo Switch 2": a little smaller rather than cut
        if (wide > room) consoleName.setTextSize(Math.max(20 * k * s, 26 * k * s * room / wide));
        consoleName.setColor(ink);
        consoleSub.setTextSize(19 * k * s);
        consoleSub.setColor(Ui.alpha(0xFFFFFFFF, on ? 0.8f : 0.6f));
        fitPaint.set(consoleName);
        String name = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(name, tx, cy - 4 * k * s, consoleName);
        String sub = it.number != null ? "HDMI " + it.number : Ui.tr("No HDMI port");
        float dot = 5 * k * s, dy = cy + 26 * k * s;
        fill.setShader(null);
        fill.setColor(on ? 0xFF34C759 : 0x99FFFFFF);   // green: a signal now
        c.drawCircle(tx + dot, dy - Ui.halfCap(consoleSub), dot, fill);
        fitPaint.set(consoleSub);
        sub = TextUtils.ellipsize(sub, fitPaint, room - 3 * dot, TextUtils.TruncateAt.END).toString();
        c.drawText(sub, tx + 3.2f * dot, dy, consoleSub);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        if (it.key != null && it.key.startsWith("console-")) {
            drawConsoleTile(c, i, it);
            return;
        }
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF && dockAwake ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        if (retargetFeed(snap)) heroDirty = true;
        // Apps' search bar, keyboard key, suggestions and grid follow the same focus. They used to be aimed only by
        // retargetGrid, so a move that only called retarget (Apps' search → the tabs, or → the top buttons) left the
        // search bar's focused glass on, stuck, until Apps was next touched.
        if (aimApps(snap)) gridDirty = true;
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        // Explore's background treatment: its grid, and the drawer's inputs and shortcuts.
        // Not for Home Design V1's Inputs: the page dims as it scrolls instead (dimming the background too redrew it,
        // full screen, on every frame of the scroll).
        exploreDim.target = (bento() && !sheetOpen && zone == Z_CARDS) || (drawerOpen && !sheetOpen && zone == Z_DRAWER && !inputRail)
                || (appsInTab() && sheetOpen) ? 1 : 0;
        if (snap) exploreDim.snap(exploreDim.target);
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    // MARK: - For You (Discover): the greeting, four rows of titles, focus and opening

    private String deviceName = "";

    /** The TV's own name (Settings > Device name), cleaned: "MIJ's TV" → "MIJ"; empty when it's only a model name. */
    private String readDeviceName() {
        String n = null;
        try {
            n = android.provider.Settings.Global.getString(getContext().getContentResolver(), "device_name");
            if (n == null || n.trim().isEmpty()) n = android.provider.Settings.Secure.getString(getContext().getContentResolver(), "bluetooth_name");
        } catch (RuntimeException ignored) {
        }
        if (n == null) return "";
        n = n.trim().replaceAll("(?i)[’']s\\s+(tv|television|google tv|android tv)$", "").replaceAll("(?i)\\s+(tv)$", "").trim();
        if (n.matches("(\\p{L} )+\\p{L}")) n = n.replace(" ", "");   // "M I J" (letters spaced out) → "MIJ"
        String l = n.toLowerCase(java.util.Locale.ROOT), model = android.os.Build.MODEL.toLowerCase(java.util.Locale.ROOT);
        if (n.isEmpty() || l.equals(model) || l.contains("tcl") || l.contains("android") || l.contains("google")
                || l.contains("smart tv") || l.matches(".*\\d.*")) return "";
        return n;
    }

    /**
     * The greetings, in this order (Latin script, so every one renders in the title face, left to right). "Hi, MIJ 👋":
     * the emoji (HELLO_EMOJIS) always after the name.
     */
    private static final String[] HELLOS = {
            "Hi",           // English
            "Hola",         // Spanish
            "Bonjour",      // French
            "Namaste",      // Hindi
            "Ciao",         // Italian
            "Hallo",        // German
            "Ol\u00e1",     // Portuguese (Olá)
            "Salam",        // Urdu, Persian, Arabic
            "Konnichiwa",   // Japanese
            "Annyeong",     // Korean
            "Ni hao",       // Mandarin (without tone marks: the title face may lack them)
            "Merhaba",      // Turkish
            "Jambo",        // Swahili
            "Hej",          // Swedish, Danish
            "Aloha",        // Hawaiian
            "Hello",        // English
    };
    /** Each greeting's emoji, after the name (the same index as HELLOS); drawn by the TV's own emoji font. */
    private static final String[] HELLO_EMOJIS = {
            "\uD83D\uDC4B",   // 👋 Hi
            "\uD83D\uDE0A",   // 😊 Hola
            "\u2728",           // ✨ Bonjour
            "\uD83D\uDE4F",   // 🙏 Namaste
            "\uD83C\uDF1F",   // 🌟 Ciao
            "\uD83D\uDE04",   // 😄 Hallo
            "\uD83C\uDF34",   // 🌴 Olá
            "\uD83E\uDD1D",   // 🤝 Salam
            "\uD83C\uDF38",   // 🌸 Konnichiwa
            "\uD83D\uDE42",   // 🙂 Annyeong
            "\uD83C\uDFEE",   // 🏮 Ni hao
            "\u2615",           // ☕ Merhaba
            "\uD83C\uDF1E",   // 🌞 Jambo
            "\uD83D\uDE00",   // 😀 Hej
            "\uD83C\uDF3A",   // 🌺 Aloha
            "\uD83D\uDE4C",   // 🙌 Hello
    };
    /**
     * The same emoji as pictures: Microsoft's Fluent Emoji (3D, MIT license; app/assets/emoji, fetched by
     * tools/fetch_emojis.sh). A missing one: the text emoji above, from the TV's font.
     */
    private static final String[] HELLO_EMOJI_ART = {"wave", "smile", "sparkles", "pray", "star", "grin", "palm",
            "handshake", "blossom", "slight_smile", "lantern", "coffee", "sun", "grinning", "hibiscus", "raised_hands"};
    private final Bitmap[] helloArt = new Bitmap[HELLO_EMOJI_ART.length];
    private final boolean[] helloArtTried = new boolean[HELLO_EMOJI_ART.length];
    private final Paint helloArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Greeting i's picture, read once (small: 128 px); null when it isn't in the app. */
    private Bitmap helloArt(int i) {
        if (!helloArtTried[i]) {
            helloArtTried[i] = true;
            try (java.io.InputStream in = getContext().getAssets().open("emoji/" + HELLO_EMOJI_ART[i] + ".png")) {
                helloArt[i] = android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException | RuntimeException e) {
                helloArt[i] = null;
            }
        }
        return helloArt[i];
    }
    private static final long GREET_MS = 30_000;          // each one stays this long (30 s)
    private static final long GREET_FADE_MS = 600;        // out (300 ms), then the next one in (300 ms)
    private int greetIndex, greetPrev = -1;
    // Shuffled order: each greeting once per cycle (greetOrder[greetPos] is the one shown); a new shuffle per cycle,
    // whose first is never the one just shown. Made once at start, not on any draw.
    private final java.util.Random greetRandom = new java.util.Random();
    private final int[] greetOrder = new int[HELLOS.length];
    private int greetPos;
    {
        shuffleGreetings(-1);
        greetIndex = greetOrder[0];
    }

    /** A new cycle: every greeting once, in random order (Fisher–Yates); `after` (the last shown) can't come first. */
    private void shuffleGreetings(int after) {
        for (int i = 0; i < greetOrder.length; i++) greetOrder[i] = i;
        for (int i = greetOrder.length - 1; i > 0; i--) {
            int j = greetRandom.nextInt(i + 1), t = greetOrder[i];
            greetOrder[i] = greetOrder[j];
            greetOrder[j] = t;
        }
        if (greetOrder.length > 1 && greetOrder[0] == after) {
            int j = 1 + greetRandom.nextInt(greetOrder.length - 1);   // swap it with any other place
            greetOrder[0] = greetOrder[j];
            greetOrder[j] = after;
        }
        greetPos = 0;
    }
    private long greetAt;
    private boolean greetWasFading;
    private TextPaint lWave;

    /**
     * Advances the greeting, from the frame loop (it runs once a second at rest, and not at all while paused): no
     * timer of its own, so leaving and coming back can't add one. Returns true while it fades (frames until it's done).
     */
    private boolean stepGreeting() {
        long now = SystemClock.uptimeMillis();
        if (greetAt == 0) greetAt = now;
        if (picksOn() && now - greetAt >= GREET_MS) {
            greetPrev = greetIndex;
            if (++greetPos >= greetOrder.length) shuffleGreetings(greetIndex);   // all shown: a new cycle
            greetIndex = greetOrder[greetPos];
            greetAt = now;
            waveCount = 0;   // its emoji plays at 2 s and 17 s (EMOJI_AT)
        }
        // The emoji's motion at its times after the greeting appeared (not with animations off in Settings).
        if (waveCount < EMOJI_AT.length && now - greetAt >= EMOJI_AT[waveCount]) {
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) waveAt = greetAt + EMOJI_AT[waveCount];
            waveCount++;
        }
        boolean fading = greetPrev >= 0 && now - greetAt < GREET_FADE_MS;
        if (!fading) greetPrev = -1;
        boolean waving = waveAt != 0 && now < waveAt + WAVE_MS;
        if (!waving) waveAt = 0;
        boolean redraw = fading || waving || greetWasFading;   // and one frame after, at rest
        greetWasFading = fading || waving;
        return redraw;
    }

    /** Each emoji's own motion, once per greeting change (700 ms), always ending exactly at rest. */
    private static final long WAVE_MS = 700;
    private static final int M_WAVE = 0, M_HOP = 1, M_BOW = 2, M_TWINKLE = 3, M_SPIN = 4, M_BOB = 5, M_SWAY = 6,
            M_SHINE = 7, M_SHAKE = 8, M_CHEER = 9, M_SWING = 10;
    /** By greeting (HELLOS' order): 👋 wave, 😊 hop, ✨ twinkle, 🙏 bow, 🌟 twinkle, 😄 hop, 🌴 sway, 🤝 shake,
     *  🌸 turn, 🙂 hop, 🏮 swing, ☕ bob, 🌞 shine, 😀 hop, 🌺 turn, 🙌 cheer. */
    private static final int[] HELLO_MOTION = {M_WAVE, M_HOP, M_TWINKLE, M_BOW, M_TWINKLE, M_HOP, M_SWAY, M_SHAKE,
            M_SPIN, M_HOP, M_SWING, M_BOB, M_SHINE, M_HOP, M_SPIN, M_CHEER};
    private long waveAt;   // uptime the current motion started (0: still)
    /** When the emoji plays, after its greeting appears: twice per greeting (each greeting shows GREET_MS, 30 s). */
    private static final long[] EMOJI_AT = {2_000, 17_000};
    private int waveCount = EMOJI_AT.length;   // how many of EMOJI_AT have played for the greeting shown
    private boolean greetStarted;

    /** How long until the emoji's next motion (for the frame loop at rest: on time, not up to a second late). */
    private long untilNextEmoji() {
        if (waveCount >= EMOJI_AT.length || !picksOn()) return Long.MAX_VALUE;
        return Math.max(1, greetAt + EMOJI_AT[waveCount] - SystemClock.uptimeMillis());
    }
    // The motion now: angle (degrees), scale, dx and dy (fractions of the emoji's size), pivot x and y (fractions).
    private final float[] motion = new float[6];

    /** The motion on the canvas, for an emoji whose box starts at (left, top) and is `size` square. */
    private void applyMotion(Canvas c, float left, float top, float size) {
        float px = left + size * motion[4], py = top + size * motion[5];
        c.translate(size * motion[2], size * motion[3]);
        if (motion[0] != 0) c.rotate(motion[0], px, py);
        if (motion[1] != 1) c.scale(motion[1], motion[1], px, py);
    }

    /** Fills `motion` for greeting `index` now; false at rest (nothing to transform). */
    private boolean emojiMotion(int index) {
        if (waveAt == 0) return false;
        long t = SystemClock.uptimeMillis() - waveAt;
        if (t <= 0 || t >= WAVE_MS) return false;
        float p = t / (float) WAVE_MS, fade = 1 - p, ease = p * p * (3 - 2 * p);
        float angle = 0, scale = 1, dx = 0, dy = 0, px = 0.5f, py = 0.5f;
        double tau = 2 * Math.PI;
        switch (HELLO_MOTION[index % HELLO_MOTION.length]) {
            case M_WAVE:      // 2½ swings from the wrist (low on the right)
                angle = 14 * (float) Math.sin(tau * 2.5 * p) * fade * fade;
                scale = 1 + 0.04f * Math.abs(angle) / 14;
                px = 0.68f; py = 0.9f;
                break;
            case M_HOP: {     // two small hops, a touch bigger in the air
                float hop = (float) Math.abs(Math.sin(tau * p)) * fade;
                dy = -0.16f * hop;
                scale = 1 + 0.05f * hop;
                px = 0.5f; py = 1f;
                break;
            }
            case M_BOW: {     // two bows forward from the base
                float bow = (float) Math.pow(Math.sin(tau * p), 2) * (1 - 0.4f * p);
                angle = 14 * bow;
                px = 0.5f; py = 1f;
                break;
            }
            case M_TWINKLE: { // two glints: a pulse with a little turn
                float pulse = (float) Math.pow(Math.sin(tau * p), 2) * fade;
                scale = 1 + 0.18f * pulse;
                angle = 12 * (float) Math.sin(tau * p) * fade;
                break;
            }
            case M_SPIN:      // a flower turns a fifth of a circle and back, opening a little (ends exactly at rest)
                angle = 36 * (float) Math.sin(Math.PI * ease);
                scale = 1 + 0.06f * (float) Math.sin(Math.PI * p);
                break;
            case M_BOB:       // a cup lifted and set down, a slight tilt
                dy = -0.1f * (float) Math.sin(3 * Math.PI * p) * fade;
                angle = 5 * (float) Math.sin(3 * Math.PI * p) * fade;
                px = 0.5f; py = 1f;
                break;
            case M_SWAY:      // a palm in the wind, from its trunk
                angle = 9 * (float) Math.sin(3 * Math.PI * p) * fade;
                px = 0.5f; py = 1f;
                break;
            case M_SHINE: {   // the sun glows twice
                float glow = (float) Math.pow(Math.sin(tau * p), 2) * fade;
                scale = 1 + 0.12f * glow;
                angle = 6 * (float) Math.sin(2 * tau * p) * fade;
                break;
            }
            case M_SHAKE:     // three quick shakes
                dy = 0.07f * (float) Math.sin(3 * tau * p) * fade;
                break;
            case M_CHEER: {   // hands up twice, rocking a little
                float up = (float) Math.abs(Math.sin(tau * p)) * fade;
                dy = -0.14f * up;
                angle = 6 * (float) Math.sin(2 * tau * p) * fade;
                px = 0.5f; py = 1f;
                break;
            }
            case M_SWING:     // a lantern swinging from its top
                angle = 12 * (float) Math.sin(3 * Math.PI * p) * fade;
                px = 0.5f; py = 0f;
                break;
            default:
                return false;
        }
        motion[0] = angle; motion[1] = scale; motion[2] = dx; motion[3] = dy; motion[4] = px; motion[5] = py;
        return true;
    }

    /** Back on the home screen: the greeting shown keeps its full time (no catching up through the ones missed). */
    private void restartGreeting() {
        greetAt = SystemClock.uptimeMillis();
        greetPrev = -1;
        greetWasFading = false;
        waveAt = 0;
        // The first greeting (the home screen starting) plays like a new one; coming back from an app doesn't.
        waveCount = greetStarted ? EMOJI_AT.length : 0;
        greetStarted = true;
    }

    /**
     * "Hola, MIJ 😊" (without a name: "Hola 😊"): each greeting with its own emoji, after the name. Left-aligned on the margin: the tabs and buttons don't move when the
     * word changes. A change fades the old one out (rising a little) and the new one in (settling). One that would reach
     * the round buttons is drawn smaller, to fit.
     */
    private void drawGreeting(Canvas c) {
        long t = SystemClock.uptimeMillis() - greetAt, half = GREET_FADE_MS / 2;
        if (greetPrev >= 0 && t < GREET_FADE_MS) {
            float u = t < half ? t / (float) half : (t - half) / (float) half;
            u = u * u * (3 - 2 * u);   // ease in and out
            if (t < half) drawGreeting(c, greetPrev, 1 - u, -6 * k * u, false);
            else drawGreeting(c, greetIndex, u, 6 * k * (1 - u), true);
        } else {
            drawGreeting(c, greetIndex, 1, 0, true);
        }
    }

    private void drawGreeting(Canvas c, int index, float alpha, float dy, boolean current) {
        boolean moving = current && emojiMotion(index);
        if (alpha <= 0.01f) return;
        String word = HELLOS[index], emoji = HELLO_EMOJIS[index];
        Bitmap art = helloArt(index);
        String text = deviceName.isEmpty() ? word + " " : word + ", " + deviceName + " ";
        float size = lTitle.getTextSize(), waveSize = size * 0.86f;   // the emoji's box is taller than the letters
        if (lWave == null) {
            lWave = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            lWave.setColor(0xFF000000);   // opaque: an emoji keeps its own colors (no ivory filter on this paint)
        }
        lWave.setTextSize(waveSize);
        // Room: from the margin to the round buttons (or the content's right edge without them).
        float left = MARGIN * k, right = CONTENT_R * k;
        for (int i = 0; i < lightButtons.size(); i++) right = Math.min(right, topRects[TABS + i].left - 32 * k);
        float artSize = size * 0.92f;   // the picture: a little under the type size, like the font's emoji box
        float width = lTitle.measureText(text) + (art != null ? artSize : lWave.measureText(emoji)), scale = 1;
        if (right > left && width > right - left) scale = (right - left) / width;
        int a0 = lTitle.getAlpha();
        if (scale < 1) {
            lTitle.setTextSize(size * scale);
            lWave.setTextSize(waveSize * scale);
        }
        lTitle.setAlpha(Math.round(a0 * alpha));
        lWave.setAlpha(Math.round(255 * alpha));
        float base = L_TITLE_BASE * k + dy, x = left - Ui.leftBearing(lTitle, text);
        c.drawText(text, x, base, lTitle);
        float ex = x + lTitle.measureText(text);
        if (art != null) {
            // Centred on the letters' middle (half the cap height above the baseline), at the scaled size.
            float a = artSize * scale, cy = base - Ui.halfCap(lTitle);
            cell.set(ex, cy - a / 2, ex + a, cy + a / 2);
            helloArtPaint.setAlpha(Math.round(255 * alpha));
            if (moving) {
                // Only the emoji moves (canvas transform, no layout): the text stays still.
                c.save();
                applyMotion(c, cell.left, cell.top, a);
                c.drawBitmap(art, null, cell, helloArtPaint);
                c.restore();
            } else {
                c.drawBitmap(art, null, cell, helloArtPaint);
            }
        } else if (moving) {
            float a = lWave.getTextSize();
            c.save();
            applyMotion(c, ex, base - a * 0.85f, a);   // the font emoji's box: about its size, above the baseline
            c.drawText(emoji, ex, base, lWave);
            c.restore();
        } else {
            c.drawText(emoji, ex, base, lWave);
        }
        lTitle.setAlpha(a0);
        if (scale < 1) lTitle.setTextSize(size);
    }


    // The boot skeleton's shapes as data, for BootSkeleton (it draws them at power-on, before anything can be read).
    static final int BOOT_SHAPE = 0, BOOT_SLOT = 1;
    private List<float[]> bootRecord;

    /**
     * This screen's boot skeleton (drawBoot) as data: {w, h, shapes: [[kind, l, t, r, b, radius, shimmer], ...]}, in
     * this screen's real places (tabs, round buttons, cards, dock). Null before the first layout.
     */
    String bootLayout() {
        if (w == 0 || h == 0 || lTitle == null || lTab == null) return null;
        int laidOut = bentoLaidOut;
        bootRecord = new ArrayList<>();
        try {
            drawBoot(null);
            org.json.JSONArray shapes = new org.json.JSONArray();
            for (float[] f : bootRecord) {
                org.json.JSONArray a = new org.json.JSONArray();
                for (float v : f) a.put(Math.round(v * 10) / 10.0);
                shapes.put(a);
            }
            return new org.json.JSONObject().put("w", w).put("h", h).put("shapes", shapes).toString();
        } catch (org.json.JSONException e) {
            return null;
        } finally {
            bootRecord = null;
            // drawBoot lays the grid out for the skeleton: the cards on screen get their own layout back.
            if (bentoLaidOut != laidOut) {
                if (laidOut >= 0) layoutBento(laidOut);
                else bentoLaidOut = -1;
            }
        }
    }

    /**
     * Coming from the boot skeleton (power-on): the same skeleton here, in its real places, fading out as soon as the
     * screen is ready (no cut from the skeleton to the screen).
     */
    void continueBootSkeleton() {
        if (!picksOn()) return;
        booting = true;
        bootStartedAt = SystemClock.uptimeMillis();
        bootFadeAt = 0;
        bootLayer.setAlpha(1);
        bootLayer.setVisibility(VISIBLE);
        wake();
    }

    /** For You and Apps fill the page under the tabs: the dock hides on them. */
    private boolean fullPage() {
        return picksOn() && (tab == APPS_TAB || tab == FOR_YOU_TAB);
    }

    // Layout (1080p units): each row a label, then its cards; the page scrolls so the focused row's label sits at
    // FEED_TOP, and each row slides sideways to keep its focused card whole.
    // FEED_LABEL: from a row's top to its cards: the title (~34) plus room for the focused card's growth, rise and rim
    // (up to ~20), so the focus never touches the row's name.
    private static final float FEED_TOP = 230, FEED_LABEL = 68, FEED_ROW_GAP = 50, FEED_GAP = 24, FEED_BOTTOM = 1040;
    // Movies V1 (For You's movie and show rows): 226 × 340 posters, the focused one 600 × 340 landscape; a serif heading
    // (40) 16 above the cards; the focused card's description (whole sentences, 3 lines of 19 at most) 24 below them,
    // clear of the focus ring; 40 between rows.
    private static final float MV_W = 226, MV_WIDE = 600, MV_H = 340, MV_LABEL = 80, MV_DESC_GAP = 24, MV_DESC_LINE = 19 * 1.45f,
            MV_INFO = MV_DESC_GAP + 3 * MV_DESC_LINE + 4, MV_ROW_GAP = 40;

    private static boolean movieRow(String row) {
        return row != null && !gameRow(row) && !railRow(row);
    }

    private static float feedLabelH(String row) {
        return movieRow(row) ? MV_LABEL : FEED_LABEL;
    }
    // For You's two sections, Movies & Shows | Games: a switch at the page's top (60 high, its rows under it). Focus on
    // it: feedRow -1. Each section's rows have their own keys, so each keeps its own cards' places and springs.
    private static final float SWITCH_H = 72, SWITCH_SPACE = 72 + 40;   // Tabs V1: the 72 px sub tabs, 40 above the rows
    private boolean gamesOn;
    private final Spring feedSwitchFocus = new Spring(0.26f, 0.85f, 0);
    private final Spring feedSection = new Spring(0.24f, 1f, 1);   // the rows fading in after a switch
    private final RectF feedSegRect = new RectF();
    private int feedRow;

    /** The rows of the section shown: Movies & Shows (Discover) or Games. */
    private List<Discover.Row> feedRows() {
        return gamesOn ? Games.get(getContext()).rows() : Discover.get(getContext()).rows();
    }

    /** The switch changed: the other section's rows, fading in, the page at its top; Games refreshed if stale. */
    private void setFeedSection(boolean games) {
        if (games == gamesOn) return;
        if (gameSheet != null) closeGameSheet();
        gamesOn = games;
        feedSwitchedAt = SystemClock.uptimeMillis();   // Tabs V1: the new section's icon plays
        feedSection.snap(0);
        feedSection.target = 1;
        feedScrollY.snap(0);
        if (games) {
            Games g = Games.get(getContext());
            Startup.games("open Games: cached rows " + g.rows().size() + (g.loading() ? ", loading" : ""));
            g.refreshIfStale();
        }
        heroDirty = true;
        feedBackdrop();
        wake();
    }
    private final java.util.HashMap<String, Integer> feedCol = new java.util.HashMap<>();
    private final java.util.HashMap<String, Spring> feedFocus = new java.util.HashMap<>();
    private final java.util.HashMap<String, Spring> feedScrollX = new java.util.HashMap<>();
    private final Spring feedScrollY = new Spring(0.24f, 1f, 0);   // quick, no bounce
    private final java.util.HashMap<String, String> feedTexts = new java.util.HashMap<>();
    private final RectF feedFocused = new RectF();
    private Paint feedLabel, feedRank, feedRankBig, feedTitle, feedTitleBig, feedTitleWide, feedMeta, feedChip;
    private Map<String, String> feedApps;   // service → the TV's app for it (opening a title)

    /** A card's resting width: tall (poster) cards; the prominent one is as wide as Trending's featured card, in proportion. */
    private static float feedNormalW(String row) {
        if (railRow(row)) return Games.RECENT.equals(row) ? RAIL_NUM + RC_CARD : POP_W;
        if (movieRow(row)) return MV_W;
        return gameRow(row) ? GAME_W : 176;
    }

    private static float feedProminentW(String row) {
        if (railRow(row)) return Games.RECENT.equals(row) ? RAIL_NUM + RAIL_WIDE : RAIL_WIDE;
        if (gameRow(row)) return GAME_W;       // game tiles are already wide (16:9): focus grows them instead
        if (movieRow(row)) return MV_WIDE;
        return feedCardH(row) * 780f / 380f;   // Trending: 780 × 380; the 264-high rows: 542 × 264
    }

    private static float feedCardH(String row) {
        if (movieRow(row)) return MV_H;
        return Discover.TRENDING.equals(row) ? 380 : Games.HERO.equals(row) ? HERO_H : railRow(row) ? RAIL_H
                : Games.UPCOMING.equals(row) ? CAL_HEAD + CAL_PER * CAL_ROW : gameRow(row) ? GAME_H : 264;
    }

    // Games (v2): wide 16:9 tiles (RAWG's art is landscape), and a featured hero at the top of the page.
    private static final float GAME_W = 400, GAME_H = 225, HERO_H = 600, HERO_GAP = 40;   // the hero row is the bento now   // v3: Coming soon peeks below

    private static boolean gameRow(String row) {
        return row != null && row.startsWith("g-");
    }

    /** A row's height on the page, its gap included: the hero has no label. */
    private static float feedRowSpan(String row) {
        return Games.HERO.equals(row) ? HERO_H + HERO_GAP : feedLabelH(row) + feedCardH(row)
                + (railRow(row) ? RAIL_INFO : movieRow(row) ? MV_INFO : 0) + (movieRow(row) ? MV_ROW_GAP : FEED_ROW_GAP);
    }

    /** Where a row's content ends (for scrolling to it): the hero's bottom, or its cards' bottom (+ the rim's room). */
    private static float feedRowEnd(String row, float top) {
        return Games.HERO.equals(row) ? top + HERO_H : top + feedLabelH(row) + feedCardH(row) + (railRow(row) ? RAIL_INFO : movieRow(row) ? MV_INFO : 24);
    }

    /**
     * Which card of a row is prominent (wide): in the focused row, the focused card; Trending, when not focused, its
     * first one (how the page opens); the other rows, when not focused, none.
     */
    private int feedProminent(List<Discover.Row> rows, int r) {
        Discover.Row row = rows.get(r);
        boolean active = !sheetOpen && zone == Z_CARDS && tab == FOR_YOU_TAB && r == feedRow;
        if (active) return feedColOf(row.key);
        return Discover.TRENDING.equals(row.key) ? 0 : -1;
    }

    /** The card's width at rest (its prominence settled): for laying the row out and scrolling to a card. */
    private float feedCardW(String row, int i, int prominent) {
        return i == prominent ? feedProminentW(row) : feedNormalW(row);
    }

    private float feedRowTop(List<Discover.Row> rows, int r) {
        return feedRowTop(rows, r, false);
    }

    /**
     * A row's top on the page. Movies V1: a movie row has its description's room only while it shows one (the row
     * with the wide card), opening and closing smoothly (`settled`: as it will be, for scrolling to a row).
     */
    private float feedRowTop(List<Discover.Row> rows, int r, boolean settled) {
        // The sub tabs' room: there while they show (the tabs or the switch focused), closed while the remote is in the rows.
        float y = FEED_TOP + SWITCH_SPACE * (settled ? feedSwitchVis.target : Ui.clamp(feedSwitchVis.value));
        for (int i = 0; i < r && i < rows.size(); i++) {
            String key = rows.get(i).key;
            y += feedRowSpan(key);
            if (movieRow(key)) {
                Spring sp = feedInfo.get(key);
                float open = sp == null ? (feedProminent(rows, i) >= 0 ? 1 : 0) : settled ? sp.target : Ui.clamp(sp.value);
                y -= MV_INFO * (1 - open);
            }
        }
        return y;
    }

    private final java.util.HashMap<String, Spring> feedInfo = new java.util.HashMap<>();

    private float feedCardX(String row, int i, int prominent) {
        float x = MARGIN;
        for (int j = 0; j < i; j++) x += feedCardW(row, j, prominent) + FEED_GAP;
        return x;
    }

    /** A card's prominence now (0 = tall, 1 = wide), animated. */
    private Spring feedProm(String row, int i, float initial) {
        Spring sp = feedProminence.get(row + i);
        if (sp == null) {
            sp = new Spring(0.24f, 1f, initial);   // first seen: already as it should be (the page opens as designed)
            feedProminence.put(row + i, sp);
        }
        return sp;
    }

    private final java.util.HashMap<String, Spring> feedProminence = new java.util.HashMap<>();

    /** For You's background: the focused title's photo (on the tabs: Trending's first), crossfading like Home's. */
    private final Runnable feedRested = () -> {
        feedBackdrop();
        followFade = bgFadeAt != 0;
        wake();
    };

    /** For You and Games: the background follows focus once it rests (as Home's cards: a crossfade per key press). */
    private void feedBackdropSoon(boolean now) {
        handler.removeCallbacks(feedRested);
        if (now) feedBackdrop();
        else handler.postDelayed(feedRested, FOLLOW_MS);
    }

    private void feedBackdrop() {
        List<Discover.Row> rows = feedRows();
        if (rows.isEmpty()) return;
        Discover.Title t;
        if (!sheetOpen && zone == Z_CARDS && tab == FOR_YOU_TAB && feedRow >= 0 && feedRow < rows.size()) {
            Discover.Row row = rows.get(feedRow);
            t = row.items.get(Math.min(feedColOf(row.key), row.items.size() - 1));
        } else {
            t = rows.get(0).items.get(0);
        }
        String path = !t.backdrop.isEmpty() ? t.backdrop : t.poster;
        if (path.isEmpty()) return;
        Recs.Pick p = feedPicks.get(path);
        if (p == null) {
            p = new Recs.Pick();
            p.id = t.id;
            p.kind = t.kind;
            p.title = t.title;
            p.backdrop = path;
            feedPicks.put(path, p);
        }
        Backdrop b = recs.backdrop(p);   // the same photo, frost and glass versions as Home's (made once, cached)
        if (b != null) showBackdrop(b);  // not ready yet: it comes with the next onRecs (this runs again then)
    }

    private final java.util.HashMap<String, Recs.Pick> feedPicks = new java.util.HashMap<>();

    private Spring feedSpring(java.util.HashMap<String, Spring> map, String key, float response, float damping) {
        Spring sp = map.get(key);
        if (sp == null) {
            sp = new Spring(response, damping, 0);
            map.put(key, sp);
        }
        return sp;
    }

    private int feedColOf(String row) {
        Integer c = feedCol.get(row);
        return c == null ? 0 : c;
    }

    /**
     * The switch (feedRow -1): left/right picks Movies & Shows or Games (like the tabs: focusing selects), down into
     * its rows, up to the tabs. The rows: up/down between rows (each remembers its card), left/right along one; up from
     * the first row: the switch.
     */
    private boolean moveFeed(int dx, int dy) {
        if (feedRow < 0) {
            if (dx != 0) {
                boolean games = dx > 0;
                if (games == gamesOn) return false;
                setFeedSection(games);
                return true;
            }
            if (dy < 0) {
                zone = Z_TOP;
                topIndex = tab;
                return true;
            }
            if (feedRows().isEmpty()) return false;
            feedRow = 0;
            followFeed(false);
            return true;
        }
        List<Discover.Row> rows = feedRows();
        if (rows.isEmpty()) {
            feedRow = -1;
            followFeed(false);
            return true;
        }
        feedRow = Math.max(0, Math.min(rows.size() - 1, feedRow));
        Discover.Row row = rows.get(feedRow);
        if (Games.HERO.equals(row.key)) {   // the bento: left/right turn it; it keeps its game when focus leaves
            int to = gbMove(Math.min(feedColOf(row.key), 5), dx, dy, Math.min(6, row.items.size()));
            if (to == -3 && feedRow >= rows.size() - 1) return false;
            if (to >= 0) feedCol.put(row.key, to);
            else {
                resetFeedRow(row);   // leaving the bento: it's back on its first game when you return
                feedRow += to == -2 ? -1 : 1;
            }
            followFeed(false);
            return true;
        }
        if (Games.UPCOMING.equals(row.key) && calendarMove(row, dx, dy)) {
            followFeed(false);
            return true;
        }
        if (dx != 0) {
            if (Games.UPCOMING.equals(row.key)) return false;   // the calendar's left or right edge
            if (railRow(row.key)) {   // a rail loops: the next game comes to the front, wide
                if (row.items.size() < 2) return false;
                int step = railStepOf(row.key) + dx;
                railStep.put(row.key, step);
                feedCol.put(row.key, GamesLogic.wrap(step, row.items.size()));
            } else {
                // The hero: left/right through the featured game's screenshots (the background changes); a row: its cards.
                int next = Math.max(0, Math.min(row.items.size() - 1, feedColOf(row.key) + dx));
                if (next == feedColOf(row.key)) return false;
                feedCol.put(row.key, next);
                if (Games.HERO.equals(row.key)) heroChanged();   // the hero: another featured game
            }
        } else if (dy < 0) {
            if (!keepsPlace(row.key)) resetFeedRow(row);
            feedRow--;   // from the first row: -1, the switch
        } else if (dy > 0) {
            if (feedRow >= rows.size() - 1) return false;
            if (!keepsPlace(row.key)) resetFeedRow(row);
            feedRow++;
        }
        followFeed(false);
        return true;
    }

    /** Coming into For You: every row at its first card (where you left one doesn't carry over). */
    private void resetFeedPlaces() {
        feedCol.clear();
        railStep.clear();
        for (Spring sp : feedScrollX.values()) sp.snap(0);
    }

    /** Leaving a row: it slides back to its first card (smoothly: its own spring), and that's where it starts again. */
    private void resetFeedRow(Discover.Row row) {
        feedCol.put(row.key, 0);
        railStep.remove(row.key);
        feedSpring(feedScrollX, row.key, 0.24f, 1f).target = 0;
    }

    /**
     * The page scrolls like a web page: only as far as needed to show the focused row whole (its title included), never
     * past the page's own end (the last row stays at the bottom, where it naturally is), never above its top.
     */
    private void followFeed(boolean snap) {
        List<Discover.Row> rows = feedRows();
        if (feedRow < 0 || rows.isEmpty()) {   // the switch: the page at its top
            feedScrollY.target = 0;
            if (snap) feedScrollY.snap(0);
            heroDirty = true;
            feedBackdropSoon(snap);
            spoken.focusMoved();
            return;
        }
        feedRow = Math.max(0, Math.min(rows.size() - 1, feedRow));
        retargetFeed(false);   // the rows' description room as it will be once the focus settles
        float top = feedRowTop(rows, feedRow, true), bottom = feedRowEnd(rows.get(feedRow).key, top);
        float end = feedRowEnd(rows.get(rows.size() - 1).key, feedRowTop(rows, rows.size() - 1, true))
                + (gamesOn ? 40 : 0);   // Games: room for RAWG's credit under the last row
        float max = Math.max(0, end - FEED_BOTTOM), y = feedScrollY.target;
        if (top - y < FEED_TOP) y = top - FEED_TOP;              // above the view: up to it
        if (bottom - y > FEED_BOTTOM) y = bottom - FEED_BOTTOM;  // below the view: just enough to show it
        if (railRow(rows.get(feedRow).key)) y = top - FEED_TOP;  // a rail: its row comes to the top (as Netflix does)
        feedScrollY.target = Math.max(0, Math.min(max, y));
        if (snap) feedScrollY.snap(feedScrollY.target);
        Discover.Row row = rows.get(feedRow);
        if (Games.HERO.equals(row.key)) {   // the hero: its screenshots change in place
            heroDirty = true;
            sky.invalidate();   // its sharp art: the screenshot chosen
            feedBackdropSoon(snap);
            spoken.focusMoved();
            return;
        }
        if (railRow(row.key) || Games.UPCOMING.equals(row.key)) {
            if (railRow(row.key)) {   // its spring is the rail's place, in cards; the description once the focus rests
                Spring rs = feedSpring(feedScrollX, row.key, 0.24f, 1f);
                rs.target = railStepOf(row.key);
                if (snap) rs.snap(rs.target);
                removeCallbacks(askAbout);
                postDelayed(askAbout, ABOUT_DELAY_MS);
            }
            heroDirty = true;
            feedBackdropSoon(snap);
            spoken.focusMoved();
            return;
        }
        int c = Math.min(feedColOf(row.key), row.items.size() - 1);
        Spring sx = feedSpring(feedScrollX, row.key, 0.24f, 1f);
        // A steady focus position: the focused card (the wide one, once settled) sits right after one resting card, so
        // the eyes stay put and the row moves under them; the first cards before that point and the row's end (no
        // empty space after its last card) are where it can't. From the row's own sizes, not fixed offsets.
        int n = row.items.size();
        float left = feedCardX(row.key, c, c), anchor = MARGIN + feedNormalW(row.key) + FEED_GAP;
        float total = feedCardX(row.key, n, c) - FEED_GAP - MARGIN;
        float maxX = Math.max(0, total - (CONTENT_R - MARGIN));
        sx.target = Math.max(0, Math.min(maxX, left - anchor));
        if (snap) sx.snap(sx.target);
        heroDirty = true;
        feedBackdropSoon(snap);
        spoken.focusMoved();
    }

    private boolean retargetFeed(boolean snap) {
        boolean changed = false;
        float sw = !sheetOpen && zone == Z_CARDS && tab == FOR_YOU_TAB && feedRow < 0 ? 1 : 0;
        float vis = zone == Z_CARDS && tab == FOR_YOU_TAB && feedRow >= 0 && !sheetOpen ? 0 : 1;
        changed |= feedSwitchVis.target != vis;
        feedSwitchVis.target = vis;
        if (snap) feedSwitchVis.snap(vis);
        changed |= feedSwitchFocus.target != sw;
        feedSwitchFocus.target = sw;
        if (snap) feedSwitchFocus.snap(sw);
        List<Discover.Row> rows = feedRows();
        boolean on = !sheetOpen && zone == Z_CARDS && tab == FOR_YOU_TAB;
        float inBento = on && feedRow == 0 && !rows.isEmpty() && Games.HERO.equals(rows.get(0).key) ? 1 : 0;
        changed |= gbActive.target != inBento;
        gbActive.target = inBento;
        if (snap) gbActive.snap(inBento);
        for (int r = 0; r < rows.size(); r++) {
            Discover.Row row = rows.get(r);
            int prominent = feedProminent(rows, r);
            if (movieRow(row.key)) {   // Movies V1: the description's room, open on the row with the wide card
                float it = prominent >= 0 ? 1 : 0;
                Spring info = feedInfo.get(row.key);
                if (info == null) {
                    info = new Spring(0.24f, 1f, it);
                    feedInfo.put(row.key, info);
                }
                changed |= info.target != it;
                info.target = it;
                if (snap) info.snap(it);
            }
            for (int i = 0; i < row.items.size(); i++) {
                Spring sp = feedSpring(feedFocus, row.key + i, 0.24f, 0.85f);
                float t = on && r == feedRow && i == feedColOf(row.key) ? 1 : 0;
                changed |= sp.target != t;
                sp.target = t;
                if (snap) sp.snap(t);
                float pt = i == prominent ? 1 : 0;
                Spring pr = feedProm(row.key, i, pt);
                changed |= pr.target != pt;
                pr.target = pt;
                if (snap) pr.snap(pt);
            }
        }
        return changed;
    }

    private boolean stepFeed(float dt) {
        // Games' hero art, sharp, over the frosted background: while the hero is on screen (the tabs, the switch or the
        // hero itself), fading away as focus goes down into the rows (their frosted backdrops take over).
        float art = 0;   // the bento has no full-screen art (it redrew the background up to 30 times a second)
        heroArt.target = art;
        heroArtMoving = heroArt.step(dt);
        boolean moving = feedScrollY.step(dt) | heroArtMoving | gameSheetIn.step(dt);
        moving |= feedSwitchFocus.step(dt);
        moving |= gbActive.step(dt);
        moving |= stepBento(dt);
        moving |= feedSection.step(dt);
        for (Spring sp : feedFocus.values()) moving |= sp.step(dt);
        for (Spring sp : feedProminence.values()) moving |= sp.step(dt);
        for (Spring sp : feedInfo.values()) moving |= sp.step(dt);
        moving |= subIconsPlaying() | feedSwitchVis.step(dt);   // Tabs V1: the sub tabs' icons, only while they play
        for (Spring sp : feedScrollX.values()) moving |= sp.step(dt);
        return moving;
    }

    // MARK: - A game's details (OK on a game): its scores, each with its source, and critics' reviews (OpenCritic)

    private Discover.Title gameSheet, gameSheetShown;
    private Games.Critics gameSheetCritics;
    private String gameSheetRowKey = "", gameSheetNote = "";
    private int gameSheetSel;
    private boolean gameSheetOkDown, gameSheetBackDown;
    private final Spring gameSheetIn = new Spring(0.3f, 1f, 0);
    private Paint gsTitle, gsBig, gsLabel, gsText, gsSmall, gsOutlet;

    private void openGameSheet() {
        List<Discover.Row> rows = feedRows();
        if (feedRow < 0 || feedRow >= rows.size()) return;
        Discover.Row row = rows.get(feedRow);
        if (row.items.isEmpty()) return;
        Discover.Title t = Games.HERO.equals(row.key) ? heroGame(row) : row.items.get(Math.min(feedColOf(row.key), row.items.size() - 1));
        gameSheet = gameSheetShown = t;
        gameSheetRowKey = row.key;
        gameSheetSel = 0;
        gameSheetNote = "";
        gameSheetOkDown = gameSheetBackDown = false;
        gameSheetCritics = Games.get(getContext()).critics(t);   // asked for now, once (kept for the session)
        gameSheetIn.target = 1;
        Startup.games("details: " + t.title + " (id " + t.id + ")");
        heroDirty = true;
        wake();
    }

    private void closeGameSheet() {
        gameSheet = null;
        gameSheetIn.target = 0;
        heroDirty = true;
        wake();
    }

    /** Keys while a game's details are open: ↑ ↓ through the reviews, OK opens one, Back closes. Nothing else moves. */
    private boolean gameSheetKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        Games.Critics cr = gameSheetCritics;
        int n = cr != null && cr.done ? cr.list.size() : 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) gameSheetBackDown = true;
                else if (gameSheetBackDown) closeGameSheet();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && n > 0) {
                    int to = Math.max(0, Math.min(n - 1, gameSheetSel + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    if (to != gameSheetSel) {
                        gameSheetSel = to;
                        gameSheetNote = "";
                        heroDirty = true;
                        wake();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) gameSheetOkDown = true;
                } else if (gameSheetOkDown) {
                    gameSheetOkDown = false;
                    if (n > 0) openReview(cr.list.get(Math.min(gameSheetSel, n - 1)));
                }
                return true;
            default:
                return false;
        }
    }

    private void openReview(Games.Review r) {
        if (r.url == null || !r.url.startsWith("http")) {
            gameSheetNote = Ui.tr("This review has no link.");
        } else {
            try {
                getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ex) {   // no browser on the TV
                gameSheetNote = Ui.trf("No web browser on this TV. The review: {url}", "url", r.url);
            }
        }
        heroDirty = true;
        wake();
    }

    /** Words in lines of at most `width`; at most `max` lines, the last one ending in "…" if more was left. */
    private static List<String> wrapLines(String text, Paint p, float width, int max) {
        List<String> out = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        int i = 0;
        for (; i < words.length && out.size() < max; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (line.length() == 0 || p.measureText(next) <= width) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(words[i]);
            }
        }
        boolean more = i < words.length;
        if (out.size() < max && line.length() > 0) out.add(line.toString());
        else if (line.length() > 0) more = true;
        if (more && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            while (last.length() > 1 && p.measureText(last + "…") > width) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last.trim() + "…");
        }
        return out;
    }

    /** One of the three score boxes: its source on top, the number, then what it's made of. */
    private void drawScoreBox(Canvas c, float x, float y, float bw, float bh, String source, String big, String sub) {
        glassTmp.set(x, y, x + bw, y + bh);
        fill.setShader(null);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(glassTmp, 24 * k, 24 * k, fill);
        border.setColor(0x29FFFFFF);
        border.setStrokeWidth(1 * k);
        c.drawRoundRect(glassTmp, 24 * k, 24 * k, border);
        border.setStrokeWidth(2 * k);
        c.drawText(source, x + 24 * k, y + 38 * k, gsLabel);
        c.drawText(feedFit(big, gsBig, bw - 48 * k), x + 24 * k, y + 92 * k, gsBig);
        c.drawText(feedFit(sub, gsSmall, bw - 48 * k), x + 24 * k, y + 124 * k, gsSmall);
    }

    private void drawGameSheet(Canvas c) {
        float a = Ui.clamp(gameSheetIn.value);
        Discover.Title t = gameSheetShown;
        if (t == null || (a <= 0.01f && gameSheet == null)) return;
        if (gsTitle == null) {
            gsTitle = Ui.text(Ui.semibold, 52 * k, 0xFFFFFFFF);
            gsBig = Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF);
            gsLabel = Ui.text(Ui.semibold, 15 * k, 0xB3FFFFFF);
            gsLabel.setLetterSpacing(0.16f);
            gsText = Ui.text(Ui.medium, 19 * k, 0xE0FFFFFF);
            gsSmall = Ui.text(Ui.medium, 16 * k, 0x9EFFFFFF);
            gsOutlet = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
            for (Paint p : new Paint[] {gsTitle, gsBig, gsLabel, gsText, gsSmall, gsOutlet}) p.setColorFilter(IVORY_TEXT);
        }
        boolean layer = a < 0.995f;
        if (layer) c.saveLayerAlpha(0, 200 * k, w, h, Math.round(255 * a));
        fill.setShader(null);
        fill.setColor(0xB8080A08);   // the page behind, dimmed
        c.drawRect(0, 200 * k, w, h, fill);
        float lift = (1 - a) * 24 * k;
        float L = MARGIN * k, T = 226 * k + lift, R = w - MARGIN * k, B = h - 48 * k + lift;
        glassTmp.set(L, T, R, B);
        fill.setColor(0xE8141416);
        c.drawRoundRect(glassTmp, 36 * k, 36 * k, fill);
        border.setColor(0x33FFFFFF);
        border.setStrokeWidth(1.5f * k);
        c.drawRoundRect(glassTmp, 36 * k, 36 * k, border);
        border.setStrokeWidth(2 * k);
        float x = L + 48 * k, y = T + 76 * k, inner = R - L - 96 * k;
        c.drawText(feedFit(t.title, gsTitle, inner), x, y, gsTitle);
        boolean coming = Discover.upcoming(t);
        StringBuilder line = new StringBuilder();
        joinPart(line, t.date.length() >= 10 ? (coming ? Ui.trf("Upcoming · {when}", "when", gameWhen(t.date))
                : Ui.trf("Released {date}", "date", Discover.shortDate(t.date))) : coming ? Ui.tr("Upcoming") : "");
        joinPart(line, t.platforms);
        joinPart(line, t.genres);
        c.drawText(feedFit(line.toString(), gsText, inner), x, y + 42 * k, gsText);
        // The scores, each in its own box with its source.
        Games.Critics cr = gameSheetCritics;
        int critic = cr != null && cr.done && cr.score > 0 ? cr.score : t.score;
        String tier = cr != null && cr.done && cr.score > 0 ? cr.tier : t.tier;
        int reviews = cr != null && cr.done && cr.reviews > 0 ? cr.reviews : t.critics;
        float by = y + 78 * k, bh = 146 * k, gap = 24 * k, bw = (inner - 2 * gap) / 3;
        if (critic > 0) {
            StringBuilder sub = new StringBuilder();
            joinPart(sub, tier);
            if (reviews > 0) joinPart(sub, Ui.trf("{n} reviews", "n", reviews));
            if (cr != null && cr.recommended > 0) joinPart(sub, Ui.trf("{n}% recommend", "n", cr.recommended));
            drawScoreBox(c, x, by, bw, bh, Ui.tr("CRITICS"), critic + " / 100",
                    sub.length() > 0 ? sub.toString() : Ui.tr("Top Critic Average"));
        } else if (t.meta > 0) {
            drawScoreBox(c, x, by, bw, bh, Ui.tr("CRITICS"), t.meta + " / 100", Ui.tr("Metascore"));
        } else {
            drawScoreBox(c, x, by, bw, bh, Ui.tr("CRITICS"), cr != null && !cr.done ? "…" : Ui.tr("Not rated"),
                    coming ? Ui.tr("Not out yet") : cr != null && !cr.done ? Ui.tr("Checking critics…") : Ui.tr("No critic score yet"));
        }
        if (t.rating > 0 && t.ratings >= 5) {
            drawScoreBox(c, x + bw + gap, by, bw, bh, Ui.tr("PLAYERS"),
                    String.format(java.util.Locale.US, "%.1f / 5", t.rating), Ui.trf("from {n} ratings", "n", compact(t.ratings)));
        } else {
            drawScoreBox(c, x + bw + gap, by, bw, bh, Ui.tr("PLAYERS"), Ui.tr("Not rated"),
                    t.ratings > 0 ? Ui.trf("only {n} ratings so far", "n", t.ratings) : Ui.tr("No players' ratings yet"));
        }
        String interest = Games.POPULAR.equals(gameSheetRowKey) && t.rank > 0 ? Ui.trf("#{n} in Popular", "n", t.rank)
                : coming ? Ui.tr("players want it") : Ui.tr("players added it");
        drawScoreBox(c, x + 2 * (bw + gap), by, bw, bh, Ui.tr("INTEREST"), t.added > 0 ? compact(t.added) : "—",
                t.added > 0 ? interest + (Games.POPULAR.equals(gameSheetRowKey) ? " · " + Ui.trf("{n} players added it", "n", compact(t.added)) : "") : Ui.tr("No data yet"));
        // Critics' reviews (OpenCritic): outlet, its own score, an excerpt, the writer; OK opens the full review.
        float ry = by + bh + 64 * k;
        c.drawText(Ui.tr("Critics' reviews"), x, ry, gsOutlet);
        String from = "";
        c.drawText(from, R - 48 * k - gsSmall.measureText(from), ry, gsSmall);
        float listTop = ry + 22 * k, rowH = 116 * k, listBottom = B - 64 * k;
        int fit = Math.max(1, (int) ((listBottom - listTop) / rowH));
        String state = null;
        if (cr == null || !cr.done) state = Ui.tr("Loading critics' reviews…");
        else if (!cr.error.isEmpty()) state = cr.error;
        else if (cr.list.isEmpty()) state = coming ? Ui.tr("No reviews before it's out.") : cr.reviews > 0
                ? Ui.tr("No review excerpts yet.") : Ui.tr("No critics' reviews yet.");
        if (state != null) {
            c.drawText(feedFit(state, gsText, inner), x, listTop + 44 * k, gsText);
        } else {
            int n = cr.list.size(), first = Math.max(0, Math.min(gameSheetSel - fit + 1, n - fit));
            for (int i = first; i < Math.min(n, first + fit); i++) {
                Games.Review r = cr.list.get(i);
                float top = listTop + (i - first) * rowH;
                if (i == gameSheetSel) {
                    glassTmp.set(x - 20 * k, top + 6 * k, R - 28 * k, top + rowH - 6 * k);
                    fill.setColor(0x1FFFFFFF);
                    c.drawRoundRect(glassTmp, 20 * k, 20 * k, fill);
                    border.setColor(accent(0.9f));
                    border.setStrokeWidth(2 * k);
                    c.drawRoundRect(glassTmp, 20 * k, 20 * k, border);
                }
                String head = r.outlet.isEmpty() ? Ui.tr("A critic") : r.outlet;
                c.drawText(feedFit(head, gsOutlet, inner * 0.5f), x, top + 38 * k, gsOutlet);
                float hx = x + Math.min(gsOutlet.measureText(head), inner * 0.5f) + 16 * k;
                if (!r.score.isEmpty()) {
                    gsOutlet.setColor(accent(1));
                    c.drawText(r.score, hx, top + 38 * k, gsOutlet);
                    gsOutlet.setColor(0xFFFFFFFF);
                    hx += gsOutlet.measureText(r.score) + 16 * k;
                }
                if (!r.author.isEmpty()) c.drawText(feedFit(r.author, gsSmall, R - 48 * k - hx), hx, top + 38 * k, gsSmall);
                List<String> lines = wrapLines("“" + r.snippet + "”", gsText, inner - 20 * k, 2);
                for (int l = 0; l < lines.size(); l++) c.drawText(lines.get(l), x, top + (68 + 26 * l) * k, gsText);
            }
        }
        String foot = !gameSheetNote.isEmpty() ? gameSheetNote : state == null
                ? Ui.tr("↑ ↓ choose · OK opens the full review · Back closes") : Ui.tr("Back closes");
        c.drawText(feedFit(foot, gsSmall, inner), x, B - 30 * k, gsSmall);
        if (layer) c.restore();
    }

    /** OK on a For You title: the same rule as Home (launchTitle). */
    private void activateFeed() {
        // The switch: OK does nothing. A game: its details (scores and critics' reviews).
        if (feedRow < 0) return;
        if (gamesOn) {
            openGameSheet();
            return;
        }
        List<Discover.Row> rows = feedRows();
        if (feedRow >= rows.size()) return;
        Discover.Row row = rows.get(feedRow);
        int c = feedColOf(row.key);
        if (c >= row.items.size()) return;
        Discover.Title t = row.items.get(c);
        launchTitle(t.kind, t.id, t.app, feedFocused, null);
    }

    /**
     * Opens a title (Home's cards, every For You row) in an installed streaming app, at that title, through a link the app
     * declares it opens (TitleLaunch). Not launchable: nothing happens (no sound, no app home, no browser, no store);
     * the card stays focusable and navigation is untouched.
     */
    private void launchTitle(String kind, int id, String shown, RectF from, Recs.Pick pick) {
        TitleLaunch launch = TitleLaunch.get(getContext());
        Intent in = launch.intent(kind, id, shown);
        if (in == null) {
            // Why not (the log and titles.txt: this TV's log drops lines within a minute).
            Startup.event("OK on " + kind + " " + id + " (" + shown + "): not opened: " + launch.whyNot(kind, id, shown));
            launch.request(kind, id, shown);   // not known yet: looked up now (the card becomes openable when it is)
            return;
        }
        try {
            activity.startActivity(in, android.app.ActivityOptions.makeScaleUpAnimation(this, Math.round(from.left),
                    Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height()))).toBundle());
            click.open();
            if (pick != null) recs.opened(pick);   // Home's history: what was opened from the cards
            Startup.event("OK on " + kind + " " + id + " (" + shown + "): opened in " + in.getPackage() + ": " + in.getDataString());
        } catch (RuntimeException e) {
            Startup.event("OK on " + kind + " " + id + ": couldn't open: " + e.getMessage());   // refused: still nothing else opened
        }
    }

    /** Can OK open this title? (The focused card's look: a launchable card gets the full focus rim.) */
    private boolean titleLaunchable(String kind, int id, String shown) {
        return TitleLaunch.get(getContext()).launchable(kind, id, shown);
    }

    /** Looks up the links of the titles on screen whose service is installed here (each once; none for the others). */
    private void requestTitleLinks() {
        TitleLaunch launch = TitleLaunch.get(getContext());
        if (!TitleLaunch.configured()) return;
        if (tab == FOR_YOU_TAB) {
            for (Discover.Row row : Discover.get(getContext()).rows()) {
                for (Discover.Title t : row.items) if (t.app != null) launch.request(t.kind, t.id, t.app);
            }
        } else {
            for (Recs.Pick p : cards) if (p.app != null && p.pkg != null) launch.request(p.kind, p.id, p.app);
        }
    }

    @Override public void onTitleLinks() {
        wake();
        heroDirty = true;   // a card may be openable now: its focus look follows
    }

    /** A game's screenshots (its art first when RAWG lists none). */
    private static String[] shotsOf(Discover.Title t) {
        // The featured game's SteamGridDB hero first (current key art), then RAWG's screenshots.
        String list = (t.hero == null || t.hero.isEmpty() ? "" : t.hero + "\n") + (t.shots == null ? "" : t.shots);
        return list.trim().isEmpty() ? new String[] {t.backdrop} : list.trim().split("\n");
    }

    /** The featured game shown (the carousel's place: left/right, or its own turn). */
    private Discover.Title heroGame(Discover.Row hero) {
        return hero.items.get(Math.max(0, Math.min(feedColOf(Games.HERO), hero.items.size() - 1)));
    }

    /** A coming game's chip: "Tomorrow", "In 13 days" within a month, else its date. */
    private static String gameWhen(String date) {
        if (date.length() >= 10 && date.substring(5, 10).equals("12-31")) return date.substring(0, 4);   // a year only
        try {
            java.util.Calendar c = java.util.Calendar.getInstance(), now = java.util.Calendar.getInstance();
            c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                    Integer.parseInt(date.substring(8, 10)), 12, 0, 0);
            now.set(java.util.Calendar.HOUR_OF_DAY, 12);
            long days = Math.round((c.getTimeInMillis() - now.getTimeInMillis()) / 86_400_000.0);
            if (days == 1) return Ui.tr("Tomorrow");
            if (days > 1 && days <= 30) return Ui.trf("In {n} days", "n", days);
        } catch (RuntimeException ignored) {
        }
        return Discover.shortDate(date);
    }

    /** "41K" for 41,250; "950" under a thousand. */
    private static String compact(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f).replace(".0M", "M");
        if (n >= 1_000) return String.format(java.util.Locale.US, "%.1fK", n / 1_000f).replace(".0K", "K");
        return String.valueOf(n);
    }

    /** A game tile's second line: its scores (each named by its source), then how wanted it is, platforms, genre. */
    private static String gameTileMeta(String row, Discover.Title t) {
        StringBuilder sb = new StringBuilder();
        joinPart(sb, scoreLine(t));
        if (Games.POPULAR.equals(row) && t.added >= 50) joinPart(sb, Ui.trf("{n} players added it", "n", compact(t.added)));
        if (Games.UPCOMING.equals(row) && t.added >= 50) joinPart(sb, Ui.trf("{n} want it", "n", compact(t.added)));
        joinPart(sb, t.platforms);
        joinPart(sb, t.genres);
        return sb.toString();
    }

    /**
     * A game's scores, each with its source: OpenCritic's Top Critic Average (else Metacritic as RAWG lists it), and
     * RAWG players' average (from 5 ratings up). A released game with neither: "Not rated". Nothing estimated.
     */
    private static String scoreLine(Discover.Title t) {
        StringBuilder sb = new StringBuilder();
        if (t.score > 0) joinPart(sb, Ui.trf("Critics {n}", "n", t.score));
        else if (t.meta > 0) joinPart(sb, Ui.trf("Critics {n}", "n", t.meta));
        if (t.rating > 0 && t.ratings >= 5) joinPart(sb, Ui.trf("Players {r}★", "r", String.format(java.util.Locale.US, "%.1f", t.rating)));
        if (sb.length() == 0 && !Discover.upcoming(t)) sb.append(Ui.tr("Not rated"));
        return sb.toString();
    }

    private static void joinPart(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }

    /** A game tile's corner: Recently released its date, Upcoming when, Popular its place. */
    private static String gameBadge(String row, Discover.Title t) {
        if (Games.POPULAR.equals(row)) return t.rank > 0 ? "#" + t.rank : "";
        if (Discover.upcoming(t)) return gameWhen(t.date);
        return t.date.length() >= 10 ? Ui.trf("Released {date}", "date", Discover.shortDate(t.date)) : "";
    }

    // MARK: - Games' cinematic hero (v3): full-screen moving art, a featured carousel, the game's own colour

    private Paint heroEyebrow, heroTitle, heroInfo, heroScore;
    private final RectF heroRect = new RectF();
    private final Spring heroArt = new Spring(0.3f, 1f, 0);   // the art on screen (1) or gone (0)
    private boolean heroArtMoving;
    private LinearGradient heroFadeLeft, heroFadeTop, heroFadeDown;
    private float heroFadeH;
    /** Each picture shows this long; a game shows its first HERO_SHOTS, then the carousel turns (not while on it). */
    private static final long HERO_SLIDE_MS = 8000, HERO_FADE_MS = 1200, HERO_TEXT_MS = 500, DRIFT_MS = 20_000;
    private static final int HERO_SHOTS = 3;
    private int heroShot;
    private long heroSlideAt, artFadeAt, heroTextAt, heroSkyAt;
    private String heroArtFrom, heroArtTo;
    private long heroArtChangedAt;
    private float heroFromAlpha = 1;
    private final java.util.HashMap<String, Integer> heroColors = new java.util.HashMap<>();

    /** Left/right on the hero, or the carousel's turn: the new game from its first picture; its text fades in. */
    private void heroChanged() {
        long now = SystemClock.uptimeMillis();
        heroShot = 0;
        heroSlideAt = now;
        heroTextAt = now;
        heroDirty = true;
        sky.invalidate();
        feedBackdrop();
    }

    /**
     * The hero's clock, once a frame while its art is on screen: the next picture every 8 s (a game's first three,
     * then the next game, unless the remote is on the hero), and frames while anything moves. The drift is slow: the
     * background is redrawn at most 30 times a second. Returns true while frames are needed.
     */
    private boolean stepHero() {
        if (heroArt.value <= 0.004f && heroArt.target == 0) return false;
        List<Discover.Row> rows = feedRows();
        if (rows.isEmpty() || !Games.HERO.equals(rows.get(0).key)) return false;
        long now = SystemClock.uptimeMillis();
        if (heroSlideAt == 0) heroSlideAt = now;
        boolean motion = android.animation.ValueAnimator.areAnimatorsEnabled();
        if (now - heroSlideAt >= HERO_SLIDE_MS) {
            heroSlideAt = now;
            Discover.Row hero = rows.get(0);
            int shots = Math.min(HERO_SHOTS, shotsOf(heroGame(hero)).length);
            boolean onHero = zone == Z_CARDS && feedRow == 0;
            if (heroShot + 1 < shots) {
                heroShot++;
            } else if (!onHero && hero.items.size() > 1) {
                feedCol.put(Games.HERO, (feedColOf(Games.HERO) + 1) % hero.items.size());
                heroShot = 0;
                heroTextAt = now;
                heroDirty = true;
            } else {
                heroShot = 0;
            }
            feedBackdrop();
        }
        boolean fading = now - artFadeAt < HERO_FADE_MS || now - heroTextAt < HERO_TEXT_MS;
        if (now - heroTextAt < HERO_TEXT_MS + 50) heroDirty = true;
        if (fading || now - heroSkyAt >= (motion ? 33 : 1000)) {
            heroSkyAt = now;
            sky.invalidate();
        }
        return motion || fading;
    }

    /** The featured game's colour (from its art, made vivid enough to glow), or white before the art is in. */
    private int heroTint() {
        List<Discover.Row> rows = gamesOn ? feedRows() : null;
        if (rows == null || rows.isEmpty() || !Games.HERO.equals(rows.get(0).key)) return 0xFFFFFFFF;
        Integer c = heroColors.get(heroGame(rows.get(0)).backdrop);
        return c == null ? 0xFFFFFFFF : c;
    }

    /** The art's colour: the most vivid of a 16 × 9 sample, lifted to a bright, saturated tone (once per game). */
    private void learnColor(String key, Bitmap b) {
        if (heroColors.containsKey(key) || b == null) return;
        float[] hsv = new float[3], best = null;
        float bestScore = -1;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int px = b.getPixel((int) ((x + 0.5f) * b.getWidth() / 16), (int) ((y + 0.5f) * b.getHeight() / 9));
                android.graphics.Color.colorToHSV(px, hsv);
                float score = hsv[1] * hsv[2];
                if (score > bestScore) {
                    bestScore = score;
                    best = hsv.clone();
                }
            }
        }
        if (best == null) return;
        best[1] = Math.max(0.45f, Math.min(0.75f, best[1]));
        best[2] = Math.max(0.9f, best[2]);
        heroColors.put(key, android.graphics.Color.HSVToColor(best));
    }

    /**
     * Games' hero art, full screen, in the background layer over the frosted image: the picture shown, drifting and
     * zooming very slowly (20 s each way; still with animations off), crossfading into the next one (1.2 s); darkened
     * on the left (the text), at the top (the tabs) and at the bottom (the rows).
     */
    // Gaming draw timing (games-timing.txt): every 3 s, the frames drawn and their average and worst cost.
    private long gfAt, gfMaxArt, gfMaxFeed, gfSumArt, gfSumFeed;
    private int gfFrames;

    private void gameFrame(long ns, boolean art) {
        if (art) { gfSumArt += ns; gfMaxArt = Math.max(gfMaxArt, ns); return; }
        gfSumFeed += ns; gfMaxFeed = Math.max(gfMaxFeed, ns); gfFrames++;
        long now = android.os.SystemClock.uptimeMillis();
        if (gfAt == 0) gfAt = now;
        if (now - gfAt < 3000) return;
        Startup.games(String.format(java.util.Locale.ROOT, "draw %d frames in %d ms: art avg %.1f max %.1f ms, feed avg %.1f max %.1f ms",
                gfFrames, now - gfAt, gfSumArt / 1e6 / gfFrames, gfMaxArt / 1e6, gfSumFeed / 1e6 / gfFrames, gfMaxFeed / 1e6));
        gfAt = now; gfFrames = 0; gfMaxArt = gfMaxFeed = gfSumArt = gfSumFeed = 0;
    }

    private void drawHeroArt(Canvas c) {
        float a = Ui.clamp(heroArt.value);
        if (a <= 0.004f) return;
        List<Discover.Row> rows = feedRows();
        if (rows.isEmpty() || !Games.HERO.equals(rows.get(0).key)) return;
        Discover.Title g = heroGame(rows.get(0));
        String[] shots = shotsOf(g);
        String cur = shots[Math.max(0, heroShot) % shots.length];
        long now = SystemClock.uptimeMillis();
        if (!cur.equals(heroArtTo)) {
            heroArtFrom = heroArtTo;
            heroArtTo = cur;
            artFadeAt = now;
            heroArtChangedAt = now;
        }
        Discover d = Discover.get(getContext());
        Bitmap to = d.card(heroArtTo, true, 1280, 720, 0);
        Bitmap from = heroArtFrom == null ? null : d.card(heroArtFrom, true, 1280, 720, 0);
        if (to == null) {
            // Still loading: the last game's picture fades away (it used to stay, under this game's name: the wrong
            // art), and the fade-in starts when this one's in.
            float gone = Ui.clamp((now - heroArtChangedAt) / 350f);
            if (from != null && gone >= 1) from = null;
            heroFromAlpha = 1 - gone;
            artFadeAt = now;
        } else {
            heroFromAlpha = 1;
            if (heroArtTo.equals(shots[0])) learnColor(g.backdrop, to);
        }
        // The next game's picture, loading in the background so it's there when the carousel turns.
        Discover.Row heroRow = rows.get(0);
        if (heroRow.items.size() > 1) {
            Discover.Title next = heroRow.items.get((heroRow.items.indexOf(g) + 1) % heroRow.items.size());
            d.card(next.backdrop, true, 1280, 720, 0);
        }
        float fade = to == null ? 0 : Ui.clamp((now - artFadeAt) / (float) HERO_FADE_MS);
        fade = fade * fade * (3 - 2 * fade);
        c.save();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            float p = (now % (2 * DRIFT_MS)) / (float) DRIFT_MS;   // 0 → 1 → 0
            if (p > 1) p = 2 - p;
            float e = p * p * (3 - 2 * p), sc = 1.02f + 0.05f * e;
            c.translate(-0.012f * w * e, -0.008f * h * e);
            c.scale(sc, sc, w * 0.6f, h * 0.4f);
        }
        tmp.set(0, 0, w, h);
        if (from != null && fade < 1) drawCovered(c, from, tmp, a * heroFromAlpha);
        if (to != null) drawCovered(c, to, tmp, a * (from == null ? 1 : fade));
        c.restore();
        if (heroFadeLeft == null || heroFadeH != h) {
            heroFadeH = h;
            heroFadeLeft = new LinearGradient(0, 0, w, 0, new int[] {0xE6080A08, 0x8C080A08, 0x00080A08},
                    new float[] {0, 0.34f, 0.62f}, Shader.TileMode.CLAMP);
            heroFadeTop = new LinearGradient(0, 0, 0, h * 0.22f, 0x8C080A08, 0x00080A08, Shader.TileMode.CLAMP);
            heroFadeDown = new LinearGradient(0, h * 0.55f, 0, h, new int[] {0x00080A08, 0xEB080A08, 0xFF080A08},
                    new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP);
        }
        fill.setColor(0xFF000000);
        fill.setAlpha(Math.round(255 * a));
        for (LinearGradient gr : new LinearGradient[] {heroFadeLeft, heroFadeTop, heroFadeDown}) {
            fill.setShader(gr);
            c.drawRect(tmp, fill);
        }
        fill.setShader(null);
        fill.setAlpha(255);
    }

    /**
     * Games' featured game over its art: a live dot in its colour and where it is in the carousel, its name very large,
     * its platforms (the genre in the accent), its Metascore as a ring in its colour (when RAWG has one), when it comes
     * or came out and its players, and the carousel's dots with what's next. The text fades in when the game changes.
     */
    // MARK: - Games' bento (A · Spotlight): one big game, two above-right, three below-right

    private static final float GB_GAP = 24, BENTO_BIG_W = 840;   // Games' bento
    private final Spring gbActive = new Spring(0.24f, 1f, 0);       // the remote in the bento (1) or not (0)
    private final RectF bentoTile = new RectF();
    private Paint gbTitleBig, gbTitle, gbMeta;
    private LinearGradient bentoShadeBig, bentoShadeSmall;

    /** Tile i of the bento whose top is `top` (page units), into bentoTile: fixed, from constants (no per-frame layout). */
    private void bentoRect(int i, float top) {
        float rx = MARGIN + BENTO_BIG_W + GB_GAP, rw = CONTENT_R - rx, th = (HERO_H - GB_GAP) / 2;
        if (i == 0) {
            bentoTile.set(MARGIN, top, MARGIN + BENTO_BIG_W, top + HERO_H);
        } else if (i <= 2) {
            float w2 = (rw - GB_GAP) / 2, x = rx + (i - 1) * (w2 + GB_GAP);
            bentoTile.set(x, top, x + w2, top + th);
        } else {
            float w3 = (rw - 2 * GB_GAP) / 3, x = rx + (i - 3) * (w3 + GB_GAP);
            bentoTile.set(x, top + th + GB_GAP, x + w3, top + HERO_H);
        }
    }

    /**
     * The remote in the bento: left/right turn it (the next or previous game takes the big square, wrapping round);
     * up: the switch (-2); down: the rows (-3).
     */
    private static int gbMove(int at, int dx, int dy, int n) {
        if (dx > 0) return (at + 1) % n;
        if (dx < 0) return (at - 1 + n) % n;
        return dy < 0 ? -2 : -3;
    }

    /**
     * A bento tile's movement: where it's drawn now (page units, top = 0), where it came from and where it goes, and
     * its 0 → 1 spring. Kept per game id: a refresh never moves art onto another game's tile.
     */
    private static final class GbTile {
        final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        final Spring go = new Spring(0.34f, 0.92f, 1);
        boolean placed;
    }

    private final java.util.HashMap<Integer, GbTile> gbTiles = new java.util.HashMap<>();
    private final Paint gbArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix gbMatrix = new android.graphics.Matrix();
    private final java.util.WeakHashMap<Bitmap, android.graphics.BitmapShader> gbShaders = new java.util.WeakHashMap<>();
    private LinearGradient gbShade;   // 0 → 1 tall, stretched to each tile by its matrix
    private int gbFocusLaid = -1, gbCountLaid = -1;
    private final RectF gbLogoRect = new RectF();

    /** A country's flag emoji from its two-letter code ("BR" → 🇧🇷); the TV's emoji font draws it. */
    private static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        String up = code.toUpperCase(java.util.Locale.ROOT);
        return new String(Character.toChars(0x1F1E6 + up.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + up.charAt(1) - 'A'));
    }

    /** Game j's slot with game f focused: f takes the big square (0), the others follow it in order (1 … 5). */
    private static int gbSlot(int j, int f, int n) {
        return (j - f + n) % n;
    }

    /**
     * Focus moved (or first seen): every tile's spring restarts from where the tile is drawn now towards its new slot —
     * interruptible (a quick second press turns them mid-way, no queue), and nothing else is laid out again.
     */
    private void gbRelayout(Discover.Row row, boolean snap) {
        int n = Math.min(6, row.items.size()), f = Math.min(feedColOf(row.key), n - 1);
        for (int j = 0; j < n; j++) {
            GbTile g = gbTiles.get(row.items.get(j).id);
            if (g == null) {
                g = new GbTile();
                gbTiles.put(row.items.get(j).id, g);
            }
            bentoRect(gbSlot(j, f, n), 0);
            g.to.set(bentoTile);
            if (snap || !g.placed) {
                g.cur.set(g.to);
                g.from.set(g.to);
                g.go.snap(1);
                g.placed = true;
            } else {
                g.from.set(g.cur);
                g.go.snap(0);
                g.go.target = 1;
            }
        }
        gbFocusLaid = f;
        gbCountLaid = n;
    }

    private boolean stepBento(float dt) {
        boolean moving = false;
        for (GbTile g : gbTiles.values()) {
            if (!g.go.step(dt)) continue;
            moving = true;
        }
        return moving;
    }

    private void drawBento(Canvas c, Discover.Row row, float top) {
        if (gbTitle == null) {
            gbTitleBig = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);   // the greeting's display face; size per draw
            gbTitleBig.setShadowLayer(18 * k, 0, 3 * k, 0x80000000);
            gbTitleBig.setLetterSpacing(-0.02f);
            gbTitle = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
            gbMeta = Ui.text(Ui.medium, 16 * k, 0xC7FFFFFF);
            for (Paint p : new Paint[] {gbTitleBig, gbTitle, gbMeta}) p.setColorFilter(IVORY_TEXT);
            gbShade = new LinearGradient(0, 0, 0, 1, new int[] {0x00080A08, 0x00080A08, 0xE0080A08},
                    new float[] {0, 0.38f, 1}, Shader.TileMode.CLAMP);
        }
        int n = Math.min(6, row.items.size());
        if (n == 0) return;
        int f = Math.min(feedColOf(row.key), n - 1);
        if (f != gbFocusLaid || n != gbCountLaid) gbRelayout(row, gbFocusLaid < 0);
        for (int j = 0; j < n; j++) if (j != f) drawBentoTile(c, row, j, top, false);
        drawBentoTile(c, row, f, top, true);   // the big one last: its shadow and rim on top while they move
    }

    /**
     * One tile, at its animated rect. One picture per game, made once at the big square's size (no corners), and
     * drawn through a shader into the tile's rounded rect, cut like CSS cover: so a tile can change size every frame
     * without a new bitmap, a clip path or a stretched image (smaller tiles are a sharp reduction of it).
     */
    private void drawBentoTile(Canvas c, Discover.Row row, int j, float top, boolean focused) {
        Discover.Title t = row.items.get(j);
        GbTile g = gbTiles.get(t.id);
        if (g == null) return;
        float v = g.go.value;
        g.cur.set(g.from.left + (g.to.left - g.from.left) * v, g.from.top + (g.to.top - g.from.top) * v,
                g.from.right + (g.to.right - g.from.right) * v, g.from.bottom + (g.to.bottom - g.from.bottom) * v);
        float L = g.cur.left * k, T = (g.cur.top + top) * k, R = g.cur.right * k, B = (g.cur.bottom + top) * k;
        if (B < 200 * k || T > h) return;
        float W = R - L, H = B - T, r0 = 24 * k;
        float fc = Ui.clamp(feedSpring(feedFocus, row.key + j, 0.24f, 0.85f).value), act = Ui.clamp(gbActive.value);
        // Shadow (deeper on the focused one).
        tmp.set(L, T + 10 * k, R, B + 10 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.28f + 0.3f * fc));
        shadows.drawCached(c, tmp, r0, DEEP_BLUR * k, shadowDeep);   // resized every frame: a live blur was redone on the CPU
        tmp.set(L, T, R, B);
        bentoRect(0, 0);
        int bw = Math.round(bentoTile.width() * k), bh = Math.round(bentoTile.height() * k);
        Bitmap art = Discover.get(getContext()).card(t.backdrop, true, bw, bh, 0);
        if (art != null) {
            android.graphics.BitmapShader sh = gbShaders.get(art);
            if (sh == null) {
                sh = new android.graphics.BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                gbShaders.put(art, sh);
            }
            float sc = Math.max(W / art.getWidth(), H / art.getHeight());
            gbMatrix.setScale(sc, sc);
            gbMatrix.postTranslate(L + (W - art.getWidth() * sc) / 2, T + (H - art.getHeight() * sc) / 2);
            sh.setLocalMatrix(gbMatrix);
            gbArt.setShader(sh);
            c.drawRoundRect(tmp, r0, r0, gbArt);
            gbArt.setShader(null);
        } else {   // still loading: never blocks; it comes with the next redraw
            fill.setShader(null);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(tmp, r0, r0, fill);
        }
        gbMatrix.setScale(1, H);
        gbMatrix.postTranslate(0, T);
        gbShade.setLocalMatrix(gbMatrix);
        fill.setShader(gbShade);
        c.drawRoundRect(tmp, r0, r0, fill);
        fill.setShader(null);
        float dim = 0.30f * act * (1 - fc);   // the others, while the remote is in the bento: a little quieter
        if (dim > 0.01f) {
            fill.setColor(Ui.alpha(0xFF000000, dim));
            c.drawRoundRect(tmp, r0, r0, fill);
        }
        // Text, sized with the tile: from a small tile's to the big square's, smoothly as it grows.
        bentoRect(0, 0);
        float bigW = bentoTile.width() * k;
        bentoRect(5, 0);
        float smallW = bentoTile.width() * k, big = Ui.clamp((W - smallW) / (bigW - smallW));
        // Concept A · Premium Cinematic. Two layouts, crossfading with the tile's size as it moves (only one drawn once
        // it settles): small tiles a name and two icon facts; the big square platform pills, the trending chip, the
        // name, and a glass stat rail (date · players · critics), each part on its own with its 3D icon.
        boolean coming = Discover.upcoming(t);
        float aSmall = 1 - Ui.clamp(big / 0.6f), aBig = Ui.clamp((big - 0.4f) / 0.6f);
        if (aSmall > 0.01f) drawGbSmall(c, t, coming, L, B, W, aSmall);
        if (aBig > 0.01f) drawGbBig(c, t, coming, L, T, R, B, W, aBig, focused);
        if (fc > 0.01f) {   // the rim on the focused one, just outside
            float gp = 4 * k;
            glassTmp.set(L - gp, T - gp, R + gp, B + gp);
            border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * fc));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(glassTmp, r0 + gp, r0 + gp, border);
            border.setStrokeWidth(2 * k);
        }
    }

    // MARK: - Games rails (Recently released, Popular this year) and Upcoming's release calendar
    //
    // The mockup's design. A rail loops: the selected game is first and wide at the margin, the previous one peeks in
    // at the left edge, the rest follow; its info (facts, the game's own description, chips) sits under it. Every
    // card's place and size is one function of the rail's spring (GamesLogic.railX/railW), so a move slides every card
    // and none jumps. One spacing scale (8 · 16 · 24 · 32), 1080p units.

    private static final float RAIL_H = 400, RAIL_WIDE = 712, RAIL_NUM = 170, RC_CARD = 228, POP_W = 310, RAIL_GAP = 24,
            RAIL_INFO = 200, POP_NEXT_H = 298, POP_REST_H = 266, CAL_HEAD = 48, CAL_ROW = 76, CAL_GAP = 48;
    private static final int CAL_COLS = 3, CAL_PER = 2, RAIL_SLOTS = 8;
    private static final long ABOUT_DELAY_MS = 350;   // the focus rests this long before a description is asked for

    private final java.util.HashMap<String, Integer> railStep = new java.util.HashMap<>();
    private float[] feedXs = new float[0], feedWs = new float[0];
    private Paint railNum, railMon, railTitle, railLine, railDesc, calNum, calTitle, calSub, calHead, railArt;
    private LinearGradient railShade;
    private final Matrix railMatrix = new Matrix();
    private final java.util.WeakHashMap<Bitmap, BitmapShader> railShaders = new java.util.WeakHashMap<>();

    /** A game's text for the rails and the calendar, made once (not while drawing every frame). */
    private static final class RailText {
        String day = "", mon = "", line = "", players = "", critics = "", trend = "", added = "", rank = "", sub = "",
                count = "", aboutSrc;
        final String[] about = {"", "", ""};   // the description in its three lines
        int sig;
    }

    private final java.util.WeakHashMap<Discover.Title, RailText> railTexts = new java.util.WeakHashMap<>();

    private static boolean railRow(String row) {
        return Games.RECENT.equals(row) || Games.POPULAR.equals(row);
    }

    /** Rows that keep their place when the remote leaves them (the rails and the calendar). */
    private static boolean keepsPlace(String row) {
        return false;   // every row starts again at its first card when you come back to it (sliders reset)
    }

    private int railStepOf(String row) {
        Integer s = railStep.get(row);
        return s == null ? 0 : s;
    }

    /** The focused rail game's description: asked for once the focus has rested (never while moving). */
    private final Runnable askAbout = () -> {
        List<Discover.Row> rows = feedRows();
        if (!gamesOn || feedRow < 0 || feedRow >= rows.size()) return;
        Discover.Row row = rows.get(feedRow);
        if (railRow(row.key) && !row.items.isEmpty())
            Games.get(getContext()).about(row.items.get(GamesLogic.wrap(railStepOf(row.key), row.items.size())));
    };

    private void railPaints() {
        if (railNum != null) return;
        railNum = Ui.text(Ui.displaySemibold, 160 * k, 0xFFFFFFFF);
        float fits = (RAIL_NUM - 18) * k, wide = railNum.measureText("00");
        if (wide > fits) railNum.setTextSize(160 * k * fits / wide);   // two digits always fit their column
        railMon = Ui.text(Ui.semibold, 15 * k, 0xFFFFFFFF);
        railMon.setLetterSpacing(0.18f);
        railTitle = Ui.text(Ui.displayItalic, 38 * k, 0xFFFFFFFF);
        railTitle.setSubpixelText(true);   // smooth as it scales (no snapping to whole pixels)
        railTitle.setLinearText(true);
        railLine = Ui.text(Ui.medium, 19 * k, 0xD9FFFFFF);
        railDesc = Ui.text(Ui.regular, 19 * k, 0xB3FFFFFF);
        calNum = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
        calTitle = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        calSub = Ui.text(Ui.medium, 15 * k, 0x99FFFFFF);
        calHead = Ui.text(Ui.semibold, 15 * k, 0x8CFFFFFF);
        calHead.setLetterSpacing(0.18f);
        for (Paint p : new Paint[] {railNum, railMon, railTitle, railLine, railDesc, calNum, calTitle, calSub, calHead})
            p.setColorFilter(IVORY_TEXT);
        railArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        railShade = new LinearGradient(0, 0, 0, 1, new int[] {0x00080A08, 0x00080A08, 0xE0080A08},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
    }

    private RailText railText(Discover.Title t) {
        RailText x = railTexts.get(t);
        // Stage 2 fills scores, ranks and charts into the same games later: their text is made again then.
        int sig = (((t.score * 31 + t.indiaRank) * 31 + t.rank) * 31 + t.added) * 31 + t.psNo1 + t.platforms.length();
        if (x == null || x.sig != sig) {
            x = new RailText();
            x.sig = sig;
            boolean tba = t.date.length() >= 10 && t.date.startsWith("-12-31", 4);
            x.day = t.date.length() >= 10 ? (tba ? "—" : t.date.substring(8, 10)) : "";
            x.mon = t.date.length() >= 10 && !tba
                    ? Discover.shortDate(t.date).replaceAll("[^\\p{L}]", "").toUpperCase(Ui.locale()) : "";
            StringBuilder sb = new StringBuilder();
            String when = gbDate(t.date);
            if (!when.isEmpty()) sb.append(Discover.upcoming(t) ? when : Ui.trf("Released {d}", "d", when));
            if (!t.platforms.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(t.platforms);
            if (!t.genres.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(t.genres);
            x.line = sb.toString();
            if (t.rating > 0 && t.ratings >= 5)
                x.players = Ui.trf("{r} players", "r", String.format(java.util.Locale.US, "%.1f", t.rating));
            if (t.score > 0) x.critics = Ui.trf("{n} critics", "n", t.score);
            x.trend = t.indiaRank > 0 ? "🇮🇳 " + Ui.tr("Trending in India") : "🌍 " + Ui.tr("Trending worldwide");
            if (t.added > 0) x.added = Ui.trf("{n} added", "n", compact(t.added));
            if (t.rank > 0) x.rank = "#" + t.rank;
            x.sub = t.platforms.isEmpty() ? t.genres : t.genres.isEmpty() ? t.platforms : t.platforms + " · " + t.genres;
            x.count = t.psNo1 > 0 ? Ui.trf("#1 in {n} countries", "n", t.psNo1) : t.added > 0 ? compact(t.added) : "";
            railTexts.put(t, x);
        }
        return x;
    }

    /**
     * The description in up to three lines, wrapped once (again only when it arrives): as many whole sentences as fit
     * three lines; a first sentence longer than that is cut at its last clause (", " "; " ": ") that fits, else at a
     * word, and ends with a full stop. Never "…" (the owner's choice).
     */
    private void railAbout(RailText x, String about, float room) {
        if (about.equals(x.aboutSrc)) return;
        x.aboutSrc = about;
        java.util.Arrays.fill(x.about, "");
        if (about.isEmpty()) return;
        List<String> lines = railWrap(about, room);
        for (int end = about.length(); lines.size() > x.about.length; ) {
            int from = end - 2, stop = Math.max(about.lastIndexOf(". ", from), Math.max(about.lastIndexOf("! ", from), about.lastIndexOf("? ", from)));
            if (stop < 0) break;
            end = stop + 1;
            lines = railWrap(about.substring(0, end), room);
        }
        int n = x.about.length;
        if (lines.size() > n) {   // one long first sentence
            StringBuilder fits = new StringBuilder();
            for (int l = 0; l < n; l++) fits.append(l > 0 ? " " : "").append(lines.get(l));
            String f = fits.toString();
            int stop = Math.max(f.lastIndexOf(", "), Math.max(f.lastIndexOf("; "), f.lastIndexOf(": ")));
            if (stop > f.length() / 2) f = f.substring(0, stop);
            f = f.replaceAll("[\\s,;:—–-]+$", "") + ".";
            lines = railWrap(f, room);
        }
        for (int l = 0; l < n && l < lines.size(); l++) x.about[l] = lines.get(l);
    }

    private List<String> railWrap(String s, float room) {
        List<String> out = new java.util.ArrayList<>();
        String rest = s.trim();
        while (!rest.isEmpty()) {
            int cut = rest.length();
            while (cut > 1 && railDesc.measureText(rest, 0, cut) > room) {
                int sp = rest.lastIndexOf(' ', cut - 1);
                cut = sp > 0 ? sp : cut - 1;
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    private void drawRail(Canvas c, Discover.Row row, float y, boolean active) {
        int n = row.items.size();
        if (n == 0) return;
        railPaints();
        boolean recent = Games.RECENT.equals(row.key);
        float pos = feedSpring(feedScrollX, row.key, 0.24f, 1f).value;
        float normalW = feedNormalW(row.key), wideW = feedProminentW(row.key);
        int first = (int) Math.floor(pos) - 1, slots = Math.min(n, RAIL_SLOTS), front = Math.round(pos);
        // Each game once: the peek, then the rest; the front one drawn last (its rim on top).
        // The loop shows (the previous game peeking in at the left) only while the remote is on this rail.
        for (int s = first; s < first + slots; s++) {
            // ...and not on its first game: nothing before the start until you've moved along it.
            boolean atStart = GamesLogic.wrap(front, n) == 0;
            if (s == front || ((!active || atStart) && s - pos < -0.5f)) continue;
            drawRailSlot(c, row, s, pos, normalW, wideW, y, recent, active);
        }
        drawRailSlot(c, row, front, pos, normalW, wideW, y, recent, active);
        // Its info, under the selected game (fading out and back in as the rail moves).
        int step = railStepOf(row.key);
        float a = Ui.clamp(1 - Math.abs(pos - step) * 2);
        if (a > 0.01f) drawRailInfo(c, row.items.get(GamesLogic.wrap(step, n)), (MARGIN + (recent ? RAIL_NUM : 0)) * k,
                (y + RAIL_H) * k, recent, a);
    }

    private static float popH(float d) {   // Popular's podium: the selected one tallest, the next a step down, then the rest
        if (d <= 0) return RAIL_H + (POP_REST_H - RAIL_H) * Ui.clamp(-d);
        if (d <= 1) return RAIL_H + (POP_NEXT_H - RAIL_H) * d;
        return POP_NEXT_H + (POP_REST_H - POP_NEXT_H) * Ui.clamp(d - 1);
    }

    private void drawRailSlot(Canvas c, Discover.Row row, int s, float pos, float normalW, float wideW, float y,
            boolean recent, boolean active) {
        float d = s - pos, x = MARGIN + GamesLogic.railX(d, normalW, wideW, RAIL_GAP), iw = GamesLogic.railW(d, normalW, wideW);
        if (x * k > w || (x + iw) * k < 0) return;
        int i = GamesLogic.wrap(s, row.items.size());
        Discover.Title t = row.items.get(i);
        RailText tx = railText(t);
        float f = Ui.clamp(1 - Math.abs(d));   // how selected (wide) it is now
        float cardH = recent ? RAIL_H : popH(d);
        float L = (recent ? x + RAIL_NUM : x) * k, R = (x + iw) * k, B = (y + RAIL_H) * k, T = B - cardH * k;
        if (recent) {   // the release day, big (solid on the selected one, faint on the others), its month under it
            float nr = L - 10 * k, nw = railNum.measureText(tx.day);
            railNum.setAlpha(Math.round(255 * (0.16f + 0.84f * f)));
            c.drawText(tx.day, nr - nw, B - 34 * k, railNum);
            railMon.setAlpha(Math.round(255 * (0.4f + 0.45f * f)));
            c.drawText(tx.mon, nr - nw + 4 * k, B - 8 * k, railMon);
        }
        float r0 = 20 * k;
        float fc = active ? Ui.clamp(feedSpring(feedFocus, row.key + i, 0.24f, 0.85f).value) * f : 0;
        tmp.set(L, T + 10 * k, R, B + 10 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.28f + 0.3f * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        tmp.set(L, T, R, B);
        // One picture per game at the wide size (cached by Discover), cover-cropped to the card's size now.
        Bitmap art = Discover.get(getContext()).card(t.backdrop, true, Math.round(RAIL_WIDE * k), Math.round(RAIL_H * k), 0);
        if (art != null) {
            BitmapShader sh = railShaders.get(art);
            if (sh == null) {
                sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                railShaders.put(art, sh);
            }
            float W = R - L, H = B - T, sc = Math.max(W / art.getWidth(), H / art.getHeight());
            railMatrix.setScale(sc, sc);
            railMatrix.postTranslate(L + (W - art.getWidth() * sc) / 2, T + (H - art.getHeight() * sc) / 2);
            sh.setLocalMatrix(railMatrix);
            railArt.setShader(sh);
            c.drawRoundRect(tmp, r0, r0, railArt);
            railArt.setShader(null);
        } else {   // missing or still loading: a quiet tile, never blank, never blocking
            fill.setShader(null);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(tmp, r0, r0, fill);
        }
        railMatrix.setScale(1, B - T);
        railMatrix.postTranslate(0, T);
        railShade.setLocalMatrix(railMatrix);
        fill.setShader(railShade);
        c.drawRoundRect(tmp, r0, r0, fill);
        fill.setShader(null);
        // The name on the picture: larger as the card opens. Set once at its full size and scaled with the canvas: a
        // text size changing every frame snapped between the font's hinted sizes (the name jerked as it grew).
        float ts = (22 + 16 * f) / 38f, nx = L + 24 * k, ny = B - 26 * k;
        c.save();
        c.scale(ts, ts, nx, ny);
        float room = (float) Math.floor((R - L - 48 * k) / ts / (8 * k)) * 8 * k;   // in 8 px steps: the fit is cached
        c.drawText(feedFit(t.title, railTitle, room), nx, ny, railTitle);
        c.restore();
        if (!recent && !tx.rank.isEmpty()) {   // Popular: its place, a trophy on the podium's top three
            float ch = 34 * k, pad = 12 * k, iw0 = t.rank <= 3 ? 22 * k : 0;
            float cw = pad + iw0 + feedChip.measureText(tx.rank) + pad;
            glassTmp.set(L + 20 * k, T + 20 * k, L + 20 * k + cw, T + 20 * k + ch);
            fill.setColor(0xAA141416);
            c.drawRoundRect(glassTmp, ch / 2, ch / 2, fill);
            if (iw0 > 0) gbIcon(c, IC_TROPHY, glassTmp.left + pad + 8 * k, glassTmp.centerY(), 16 * k, 1);
            c.drawText(tx.rank, glassTmp.left + pad + iw0, glassTmp.centerY() + Ui.halfCap(feedChip), feedChip);
        }
        if (fc > 0.01f) {   // the focus rim, just outside (as the bento's)
            float gp = 4 * k;
            glassTmp.set(L - gp, T - gp, R + gp, B + gp);
            border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * fc));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(glassTmp, r0 + gp, r0 + gp, border);
            border.setStrokeWidth(2 * k);
            feedFocused.set(L, T, R, B);
        }
    }

    /** Under the selected game: its facts, its own description (two lines, when it's in), then chips. */
    private void drawRailInfo(Canvas c, Discover.Title t, float x, float top, boolean recent, float a) {
        RailText tx = railText(t);
        float room = 960 * k;
        railAbout(tx, Games.get(getContext()).aboutKnown(t), room);
        railLine.setAlpha(Math.round(0xD9 * a));
        c.drawText(feedFit(tx.line, railLine, room), x, top + 44 * k, railLine);
        railDesc.setAlpha(Math.round(0xB3 * a));
        float last = 44;   // the baseline of the last line drawn: the facts, then each line of the description
        for (int l = 0; l < tx.about.length; l++) {
            if (tx.about[l].isEmpty()) continue;
            last = 76 + 26 * l;
            c.drawText(tx.about[l], x, top + last * k, railDesc);
        }
        // The chips follow the text: 16 under its last line (its descender 8 below the baseline), however many lines.
        float cx = x, cy = top + (last + 24) * k;
        if (!tx.players.isEmpty()) cx += railChip(c, cx, cy, IC_STAR, tx.players, a) + 8 * k;
        if (recent && !tx.critics.isEmpty()) cx += railChip(c, cx, cy, IC_TROPHY, tx.critics, a) + 8 * k;
        if (!recent && !tx.added.isEmpty()) cx += railChip(c, cx, cy, IC_PEOPLE, tx.added, a) + 8 * k;
        railChip(c, cx, cy, -1, tx.trend, a);
    }

    private float railChip(Canvas c, float x, float y, int icon, String text, float a) {
        float h = 34 * k, pad = 14 * k, iw = icon >= 0 ? 22 * k : 0, cw = pad + iw + feedChip.measureText(text) + pad;
        glassTmp.set(x, y, x + cw, y + h);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x1FFFFFFF, a));
        c.drawRoundRect(glassTmp, h / 2, h / 2, fill);
        border.setColor(Ui.alpha(0x33FFFFFF, a));
        border.setStrokeWidth(1 * k);
        c.drawRoundRect(glassTmp, h / 2, h / 2, border);
        border.setStrokeWidth(2 * k);
        if (icon >= 0) gbIcon(c, icon, x + pad + 8 * k, y + h / 2, 16 * k, a);
        feedChip.setAlpha(Math.round(255 * a));
        c.drawText(text, x + pad + iw, y + h / 2 + Ui.halfCap(feedChip), feedChip);
        feedChip.setAlpha(255);
        return cw;
    }

    // Upcoming: the release calendar, three month columns of two games (GamesLogic.calendar), its own grid for the remote.

    private final android.graphics.Rect calInk = new android.graphics.Rect();
    private Discover.Row calFor;
    private int[][] calCols = new int[0][];
    private String[] calHeads = new String[0];

    private int[][] calCols(Discover.Row row) {
        if (row == calFor) return calCols;
        String[] dates = new String[row.items.size()];
        for (int i = 0; i < dates.length; i++) dates[i] = row.items.get(i).date;
        calCols = GamesLogic.calendar(dates, CAL_COLS, CAL_PER);
        calHeads = new String[calCols.length];
        String[] months = new java.text.DateFormatSymbols(Ui.locale()).getMonths();
        for (int j = 0; j < calCols.length; j++) {
            String key = GamesLogic.calendarKey(dates[calCols[j][0]]);
            calHeads[j] = (key.endsWith(" TBA") ? key.substring(0, 4) + " · " + Ui.tr("Date TBA")
                    : months[Integer.parseInt(key.substring(5, 7)) - 1]).toUpperCase(Ui.locale());
        }
        calFor = row;
        return calCols;
    }

    /** The remote in the calendar: left/right between months, up/down within one; false at its edges (the page decides). */
    private boolean calendarMove(Discover.Row row, int dx, int dy) {
        int[][] cols = calCols(row);
        if (cols.length == 0) return false;
        int at = feedColOf(row.key), cj = 0, cp = 0;
        for (int j = 0; j < cols.length; j++) for (int q = 0; q < cols[j].length; q++) if (cols[j][q] == at) { cj = j; cp = q; }
        int j = cj + dx, q = cp + dy;
        if (j < 0 || j >= cols.length || q < 0 || (dy != 0 && q >= cols[j].length)) return false;
        feedCol.put(row.key, cols[j][Math.min(q, cols[j].length - 1)]);
        return true;
    }

    private void drawCalendar(Canvas c, Discover.Row row, float top, boolean active) {
        railPaints();
        int[][] cols = calCols(row);
        String note = Ui.tr("release calendar");
        c.drawText(note, (MARGIN + 16) * k + feedLabel.measureText(row.name), (top + 30) * k, calSub);
        float y = top + FEED_LABEL, colW = (CONTENT_R - MARGIN - (CAL_COLS - 1) * CAL_GAP) / CAL_COLS;
        for (int j = 0; j < cols.length; j++) {
            float cx = MARGIN + j * (colW + CAL_GAP);
            c.drawText(calHeads[j], cx * k, (y + 16) * k, calHead);
            for (int q = 0; q < cols[j].length; q++) {
                int i = cols[j][q];
                Discover.Title t = row.items.get(i);
                RailText tx = railText(t);
                float e = y + CAL_HEAD + q * CAL_ROW;
                float fc = active ? Ui.clamp(feedSpring(feedFocus, row.key + i, 0.24f, 0.85f).value) : 0;
                if (fc > 0.01f) {   // focus: a glass pill behind the entry and the rim (nothing moves)
                    glassTmp.set((cx - 16) * k, (e + 4) * k, (cx + colW) * k, (e + CAL_ROW - 4) * k);
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0x24FFFFFF, fc));
                    c.drawRoundRect(glassTmp, 16 * k, 16 * k, fill);
                    border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * fc));
                    border.setStrokeWidth(3 * k);
                    c.drawRoundRect(glassTmp, 16 * k, 16 * k, border);
                    border.setStrokeWidth(2 * k);
                    feedFocused.set(glassTmp);
                }
                // Right-aligned on the digits' ink, 24 before the name (a "1" has wide side bearings: by the ink, not the advance).
                calNum.getTextBounds(tx.day, 0, tx.day.length(), calInk);
                c.drawText(tx.day, (cx + 64) * k - calInk.right, (e + 52) * k, calNum);
                float nx = (cx + 88) * k, cntW = tx.count.isEmpty() ? 0 : calSub.measureText(tx.count) + 21 * k;   // icon 15 + gap 6 (as the chips)
                float room = (cx + colW - 12) * k - nx - cntW - 16 * k;
                c.drawText(feedFit(t.title, calTitle, room), nx, (e + 34) * k, calTitle);
                c.drawText(feedFit(tx.sub, calSub, room), nx, (e + 58) * k, calSub);
                if (cntW > 0) {
                    float rx = (cx + colW - 12) * k;
                    c.drawText(tx.count, rx - calSub.measureText(tx.count), (e + 42) * k, calSub);
                    gbIcon(c, IC_PEOPLE, rx - cntW + 8 * k, (e + 42) * k - Ui.halfCap(calSub), 15 * k, 0.8f);
                }
                if (q < cols[j].length - 1) {   // between entries: one quiet line
                    fill.setColor(0x14FFFFFF);
                    c.drawRect(cx * k, (e + CAL_ROW) * k - 0.5f * k, (cx + colW) * k, (e + CAL_ROW) * k + 0.5f * k, fill);
                }
            }
        }
    }

    // MARK: - Bento A · Premium Cinematic: text and stats

    private Paint gbVal, gbSub, gbEmoji;
    private Bitmap[] gbIcons;
    private static final String[] GB_ICON = {"calendar", "busts", "star", "trophy"};
    private static final String[] GB_ICON_EMOJI = {"📅", "👥", "⭐", "🏆"};
    private static final int IC_CAL = 0, IC_PEOPLE = 1, IC_STAR = 2, IC_TROPHY = 3;

    /** A 3D icon (Fluent, fetched once into assets by tools/fetch_emojis.sh; read once): else the TV's own emoji. */
    private void gbIcon(Canvas c, int which, float cx, float cy, float size, float alpha) {
        if (gbIcons == null) {
            gbIcons = new Bitmap[GB_ICON.length];
            for (int i = 0; i < GB_ICON.length; i++) {
                try (java.io.InputStream in = getContext().getAssets().open("emoji/" + GB_ICON[i] + ".png")) {
                    gbIcons[i] = android.graphics.BitmapFactory.decodeStream(in);
                    if (i == IC_PEOPLE && gbIcons[i] != null) gbIcons[i] = lighten(gbIcons[i]);
                    if (gbIcons[i] != null) gbIcons[i] = trim(gbIcons[i]);   // centred on what's drawn, not its canvas
                } catch (java.io.IOException ignored) {
                }
            }
        }
        Bitmap b = gbIcons[which];
        if (b != null) {   // fitted in the size × size box, its visible part centred on (cx, cy)
            float sc = size / Math.max(b.getWidth(), b.getHeight()), hw = b.getWidth() * sc / 2, hh = b.getHeight() * sc / 2;
            gbLogoRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            bitmapPaint.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, gbLogoRect, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            if (gbEmoji == null) gbEmoji = Ui.text(android.graphics.Typeface.DEFAULT, 20 * k, 0xFFFFFFFF);
            gbEmoji.setTextSize(size * 0.8f);
            gbEmoji.setAlpha(Math.round(255 * alpha));
            String e = GB_ICON_EMOJI[which];
            c.drawText(e, cx - gbEmoji.measureText(e) / 2, cy + Ui.halfCap(gbEmoji), gbEmoji);
        }
    }

    /** An icon cropped to its visible pixels (Fluent's emoji sit off-centre in their square: the busts low). */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, bt = -1;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) < 16) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > bt) bt = y;
            }
        }
        if (r < l || bt < t) return b;
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1);
    }

    /** Fluent's busts are near-black purple, lost on the dark glass: lifted once to a light blue (as the mockup's). */
    private static Bitmap lighten(Bitmap b) {
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.LightingColorFilter(0xFFFFFFFF, 0x0050_78E0));
        new Canvas(out).drawBitmap(b, 0, 0, p);
        b.recycle();
        return out;
    }

    /** A PlayStation exclusive: every platform it's listed for is a PlayStation (GTA VI, also on Xbox, isn't one). */
    private static boolean gbExclusive(Discover.Title t) {
        if (t.platforms == null || t.platforms.isEmpty()) return false;
        for (String p : t.platforms.split(" · ")) if (!p.trim().startsWith("PS")) return false;
        return true;
    }

    /** "Oct 13", "Dec 11", "2026" (RAWG's year-only dates are Dec 31). */
    private static String gbDate(String date) {
        if (date.length() < 10) return "";
        return date.substring(5, 10).equals("12-31") ? date.substring(0, 4) : Discover.shortDate(date);
    }

    private void gbPaints() {
        if (gbVal != null) return;
        gbVal = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        gbSub = Ui.text(Ui.medium, 14 * k, 0xADFFFFFF);
        gbVal.setColorFilter(IVORY_TEXT);
        gbSub.setColorFilter(IVORY_TEXT);
    }

    /** The name: the game's logo when there is one (fitted, never stretched), else the name in the display face. */
    private void gbName(Canvas c, Discover.Title t, float left, float baseline, float maxW, float maxH, float size, float alpha) {
        Bitmap logo = t.logo.isEmpty() ? null : Discover.get(getContext()).logo(t.logo);
        if (logo != null) {
            float sc = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            gbLogoRect.set(left, baseline - logo.getHeight() * sc, left + logo.getWidth() * sc, baseline);
            bitmapPaint.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(logo, null, gbLogoRect, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            gbTitleBig.setTextSize(size);
            gbTitleBig.setAlpha(Math.round(255 * alpha));
            c.drawText(feedFit(t.title, gbTitleBig, maxW), left - Ui.leftBearing(gbTitleBig, t.title), baseline, gbTitleBig);
            gbTitleBig.setAlpha(255);
        }
    }

    /** A small tile: the name, then two facts with their icons — a score (critics, else players) and when. */
    private void drawGbSmall(Canvas c, Discover.Title t, boolean coming, float L, float B, float W, float a) {
        gbPaints();
        float pad = 20 * k, left = L + pad, base = B - pad, x = left;
        gbMeta.setAlpha(Math.round(230 * a));
        int icon = -1;
        String v = "";
        if (gbExclusive(t) && t.psNo1 > 0) { icon = IC_PEOPLE; v = Ui.trf("#1 in {n}", "n", t.psNo1); }
        else if (t.score > 0) { icon = IC_TROPHY; v = String.valueOf(t.score); }
        else if (t.rating > 0 && t.ratings >= 5) { icon = IC_STAR; v = String.format(java.util.Locale.US, "%.1f", t.rating); }
        else if (t.added >= 50) { icon = IC_PEOPLE; v = compact(t.added) + (coming ? " " + Ui.tr("want it") : ""); }
        if (icon >= 0) {
            gbIcon(c, icon, x + 10 * k, base - Ui.halfCap(gbMeta), 20 * k, a);   // on the text's middle
            x += 26 * k;
            c.drawText(v, x, base, gbMeta);
            x += gbMeta.measureText(v) + 18 * k;
        }
        String when = coming ? gameWhen(t.date) : gbDate(t.date);
        if (!when.isEmpty() && x < L + W - 80 * k) {
            gbIcon(c, IC_CAL, x + 10 * k, base - Ui.halfCap(gbMeta), 20 * k, a);
            c.drawText(feedFit(when, gbMeta, L + W - pad - x - 26 * k), x + 26 * k, base, gbMeta);
        }
        gbMeta.setAlpha(255);
        gbName(c, t, left, base - 34 * k, W - 2 * pad, 50 * k, 26 * k, a);
    }

    /** One part of the stat rail: icon, value, and what it is (with its source). Returns its width. */
    private float gbStat(Canvas c, int icon, String value, String sub, float x, float cy, boolean draw, float a, boolean quiet) {
        float w = 22 * k + 34 * k + 14 * k + Math.max(gbVal.measureText(value), gbSub.measureText(sub)) + 22 * k;
        if (!draw) return w;
        gbIcon(c, icon, x + 22 * k + 17 * k, cy, 34 * k, a);
        float tx = x + 22 * k + 34 * k + 14 * k;
        gbVal.setAlpha(Math.round(255 * a * (quiet ? 0.7f : 1)));
        gbSub.setAlpha(Math.round(173 * a));
        c.drawText(value, tx, cy - 2 * k, gbVal);
        c.drawText(sub, tx, cy + 20 * k, gbSub);
        gbVal.setAlpha(255);
        gbSub.setAlpha(255);
        return w;
    }

    /** The big square: platform pills, trending chip, name, and the stat rail. */
    private void drawGbBig(Canvas c, Discover.Title t, boolean coming, float L, float T, float R, float B, float W, float a, boolean focused) {
        gbPaints();
        float pad = 32 * k, left = L + pad;
        // Platform pills, then the genre in the accent (top left).
        float px = left, py = T + 26 * k, ph = 34 * k;
        List<String> pills = new ArrayList<>();
        if (!t.platforms.isEmpty()) pills.addAll(java.util.Arrays.asList(t.platforms.split(" · ")));
        int nPlat = pills.size();
        if (!t.genres.isEmpty()) pills.add(t.genres);
        for (int i = 0; i < pills.size(); i++) {
            String s = pills.get(i);
            float pw = feedChip.measureText(s) + 28 * k;
            if (px + pw > R - 340 * k) break;   // room left for the trending chip
            glassTmp.set(px, py, px + pw, py + ph);
            boolean genre = i >= nPlat;
            fill.setColor(genre ? Ui.alpha(accent(1), 0.95f * a) : Ui.alpha(0x2EFFFFFF, a));
            c.drawRoundRect(glassTmp, ph / 2, ph / 2, fill);
            feedChip.setColor(genre ? L_INK : 0xFFFFFFFF);
            feedChip.setAlpha(Math.round(255 * a));
            c.drawText(s, px + 14 * k, py + ph / 2 + Ui.halfCap(feedChip), feedChip);
            feedChip.setColor(0xFFFFFFFF);
            feedChip.setAlpha(255);
            px += pw + 10 * k;
        }
        // Trending (top right): India when the game is on India's PS Store chart today (ps-store-db.live), else worldwide.
        if (focused) {
            String where = t.indiaRank > 0 ? "🇮🇳 " + Ui.tr("Trending in India") : "🌍 " + Ui.tr("Trending worldwide");
            float cw = feedChip.measureText(where) + 32 * k, ch = 34 * k, tx = R - 22 * k - cw, ty = T + 26 * k;
            glassTmp.set(tx, ty, tx + cw, ty + ch);
            fill.setColor(Ui.alpha(0xAA141416, a));
            c.drawRoundRect(glassTmp, ch / 2, ch / 2, fill);
            border.setColor(Ui.alpha(0x33FFFFFF, a));
            border.setStrokeWidth(1 * k);
            c.drawRoundRect(glassTmp, ch / 2, ch / 2, border);
            border.setStrokeWidth(2 * k);
            feedChip.setAlpha(Math.round(255 * a));
            c.drawText(where, tx + 16 * k, ty + ch / 2 + Ui.halfCap(feedChip), feedChip);
            feedChip.setAlpha(255);
        }
        // The stat rail (bottom): when · players · critics, each with its source; never squeezed into one line.
        int[] icons = new int[3];
        String[] vals = new String[3], subs = new String[3];
        boolean[] quiet = new boolean[3];
        int n = 0;
        String when = coming ? gbDate(t.date) : gbDate(t.date);
        if (!when.isEmpty()) {
            String cd = coming ? gameWhen(t.date) : Ui.tr("Released");
            icons[n] = IC_CAL; vals[n] = when; subs[n] = cd.equals(when) ? Ui.tr("Coming") : cd; n++;
        }
        if (gbExclusive(t) && (t.psNo1 > 0 || t.psTop10 > 0)) {   // PS5 exclusives: the PS Store's pre-order strength
            icons[n] = IC_PEOPLE;
            vals[n] = t.psNo1 > 0 ? Ui.trf("#1 in {n} countries", "n", t.psNo1) : Ui.trf("Top 10 in {n} countries", "n", t.psTop10);
            subs[n] = !t.psLabel.isEmpty() ? t.psLabel : coming ? Ui.tr("PS Store pre-orders") : Ui.tr("PS Store best-sellers");
            n++;
        } else if (!coming && t.rating > 0 && t.ratings >= 5) {
            icons[n] = IC_STAR; vals[n] = String.format(java.util.Locale.US, "%.1f", t.rating);
            subs[n] = Ui.trf("players · {n} ratings", "n", compact(t.ratings)); n++;
        } else if (t.added >= 50) {
            icons[n] = IC_PEOPLE; vals[n] = compact(t.added);
            subs[n] = coming ? Ui.tr("players want it") : Ui.tr("players added it"); n++;
        }
        icons[n] = IC_TROPHY;
        if (t.score > 0) { vals[n] = String.valueOf(t.score); subs[n] = Ui.tr("critics") + (t.tier.isEmpty() ? "" : " · " + t.tier); }
        else if (t.meta > 0) { vals[n] = String.valueOf(t.meta); subs[n] = Ui.tr("critics"); }
        else { vals[n] = Ui.tr("Not rated"); subs[n] = coming ? Ui.tr("critics after release") : Ui.tr("no critic score yet"); quiet[n] = true; }
        n++;
        float rh = 76 * k, rb = B - 28 * k, rt = rb - rh, total = 0;
        for (int i = 0; i < n; i++) total += gbStat(c, icons[i], vals[i], subs[i], 0, 0, false, a, quiet[i]);
        float rw = Math.min(total, W - 2 * pad);
        glassTmp.set(left, rt, left + rw, rb);
        fill.setColor(Ui.alpha(0x8C16181C, a));   // the glass: smoke over the art, a top highlight, a hairline edge
        c.drawRoundRect(glassTmp, 22 * k, 22 * k, fill);
        border.setColor(Ui.alpha(0x2EFFFFFF, a));
        border.setStrokeWidth(1 * k);
        c.drawRoundRect(glassTmp, 22 * k, 22 * k, border);
        float sx = left, cy = (rt + rb) / 2;
        for (int i = 0; i < n; i++) {
            float sw = gbStat(c, icons[i], vals[i], subs[i], sx, cy, true, a, quiet[i]);
            if (i > 0) c.drawLine(sx, rt + 16 * k, sx, rb - 16 * k, border);
            sx += sw;
        }
        border.setStrokeWidth(2 * k);
        // The name above the rail: logo (up to 620 × 140) or the name in the display face.
        gbName(c, t, left, rt - 24 * k, Math.min(W - 2 * pad, 620 * k), 140 * k, 76 * k, a);
    }

    private void drawGamesHero(Canvas c, Discover.Row row, float top) {
        // Scrolling up under the tabs: the whole hero (text, logo, chips, dots) fades out together; it used to be cut at
        // the tabs' line, leaving the dots alone above the rows.
        float vis = Ui.clamp(1 - (200 - top) / 200f);
        if (vis <= 0.01f || row.items.isEmpty()) return;
        boolean faded = vis < 0.995f;
        if (faded) c.saveLayerAlpha(0, 200 * k, w, (top + HERO_H) * k, Math.round(255 * vis));
        drawGamesHeroBody(c, row, top);
        if (faded) c.restore();
    }

    private void drawGamesHeroBody(Canvas c, Discover.Row row, float top) {
        Discover.Title t = heroGame(row);
        if (heroTitle == null) {
            heroEyebrow = Ui.text(Ui.semibold, 17 * k, 0xD9FFFFFF);
            heroEyebrow.setLetterSpacing(0.18f);
            heroTitle = Ui.text(Ui.semibold, 112 * k, 0xFFFFFFFF);
            heroTitle.setLetterSpacing(-0.025f);
            heroTitle.setShadowLayer(40 * k, 0, 6 * k, 0x73000000);
            heroInfo = Ui.text(Ui.medium, 19 * k, 0xE0FFFFFF);
            heroScore = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            for (Paint p : new Paint[] {heroEyebrow, heroTitle, heroInfo, heroScore}) p.setColorFilter(IVORY_TEXT);
        }
        long now = SystemClock.uptimeMillis();
        float ta = Ui.clamp((now - heroTextAt) / (float) HERO_TEXT_MS);
        boolean layer = ta < 0.995f;
        if (layer) c.saveLayerAlpha(0, top * k, w, (top + HERO_H) * k, Math.round(255 * ta * ta * (3 - 2 * ta)));
        int tint = heroTint();
        float x = MARGIN * k, y = top * k;
        boolean coming = Discover.upcoming(t);
        int n = row.items.size(), at = Math.min(feedColOf(row.key), n - 1);
        // The eyebrow: a glowing dot in the game's colour, then what it is and its place in the carousel.
        fill.setShader(null);
        fill.setColor(Ui.alpha(tint, 0.35f));
        c.drawCircle(x + 6 * k, y + 76 * k, 11 * k, fill);
        fill.setColor(tint);
        c.drawCircle(x + 6 * k, y + 76 * k, 5.5f * k, fill);
        String what = coming ? Ui.tr("UPCOMING") + " · " + gameWhen(t.date).toUpperCase(Ui.locale()) : Ui.tr("RECENTLY RELEASED");
        c.drawText(what + " · " + Ui.trf("{i} OF {n}", "i", at + 1, "n", n), x + 26 * k, y + 82 * k, heroEyebrow);
        // The game's logo (SteamGridDB) in place of its typed name: up to 720 × 112, under the eyebrow, on the name's baseline.
        Bitmap logo = t.logo == null || t.logo.isEmpty() ? null : Discover.get(getContext()).logo(t.logo);
        if (logo != null) {
            float lw = 720 * k, lh = 112 * k, sc = Math.min(lw / logo.getWidth(), lh / logo.getHeight());   // y+98 … y+210
            float dw = logo.getWidth() * sc, dh = logo.getHeight() * sc;
            heroRect.set(x, y + 210 * k - dh, x + dw, y + 210 * k);
            bitmapPaint.setAlpha(255);
            c.drawBitmap(logo, null, heroRect, bitmapPaint);
        } else {
            c.drawText(feedFit(t.title, heroTitle, 1240 * k), x - Ui.leftBearing(heroTitle, t.title), y + 200 * k, heroTitle);
        }
        // Chips: each platform, then the genre in the accent.
        float cx = x, cy = y + 232 * k, ch = 40 * k;
        List<String> chips = new ArrayList<>();
        if (t.platforms != null && !t.platforms.isEmpty()) chips.addAll(java.util.Arrays.asList(t.platforms.split(" · ")));
        for (int i = 0; i <= chips.size(); i++) {
            boolean genre = i == chips.size();
            String label = genre ? t.genres : chips.get(i);
            if (label == null || label.isEmpty()) continue;
            float cw = feedChip.measureText(label) + 34 * k;
            glassTmp.set(cx, cy, cx + cw, cy + ch);
            fill.setColor(genre ? accent(0.95f) : 0x24FFFFFF);
            c.drawRoundRect(glassTmp, ch / 2, ch / 2, fill);
            if (!genre) {
                border.setColor(0x3DFFFFFF);
                border.setStrokeWidth(1 * k);
                c.drawRoundRect(glassTmp, ch / 2, ch / 2, border);
                border.setStrokeWidth(2 * k);
            }
            feedChip.setColor(genre ? L_INK : 0xFFFFFFFF);
            c.drawText(label, cx + 17 * k, cy + ch / 2 + Ui.halfCap(feedChip), feedChip);
            feedChip.setColor(0xFFFFFFFF);
            cx += cw + 12 * k;
        }
        // The Metascore ring in the game's colour (when RAWG has one), then when and how many players.
        String when = t.date.length() >= 10 ? (coming ? Ui.trf("Out {date}", "date", Discover.shortDate(t.date))
                : Ui.trf("Released {date}", "date", Discover.shortDate(t.date))) : "";
        String players = t.added >= 50 ? (coming ? Ui.trf("{n} want it", "n", compact(t.added))
                : Ui.trf("{n} players added it", "n", compact(t.added))) : "";
        String rated = t.rating > 0 && t.ratings >= 5 ? Ui.trf("Players {r}★ from {n} ratings", "r",
                String.format(java.util.Locale.US, "%.1f", t.rating), "n", compact(t.ratings)) : "";
        StringBuilder ib = new StringBuilder();
        joinPart(ib, when);
        joinPart(ib, players);
        joinPart(ib, rated);
        // The ring: OpenCritic's Top Critic Average, else Metacritic as RAWG lists it; each named. Neither: "Not rated".
        int ring = t.score > 0 ? t.score : t.meta;
        if (ring <= 0 && !coming && rated.isEmpty()) ib.insert(0, Ui.tr("Not rated") + (ib.length() > 0 ? " · " : ""));
        String info = ib.toString();
        float ry = y + 344 * k, rr = 42 * k, rx = x + rr;
        if (ring > 0) {
            fill.setColor(Ui.alpha(tint, 0.22f));
            c.drawCircle(rx, ry, rr + 14 * k, fill);
            fill.setColor(0xF00F1210);
            c.drawCircle(rx, ry, rr, fill);
            border.setStrokeWidth(6 * k);
            border.setColor(0x29FFFFFF);
            c.drawCircle(rx, ry, rr - 3 * k, border);
            border.setColor(tint);
            heroRect.set(rx - rr + 3 * k, ry - rr + 3 * k, rx + rr - 3 * k, ry + rr - 3 * k);
            border.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(heroRect, -90, 360f * Math.min(100, ring) / 100f, false, border);
            border.setStrokeCap(Paint.Cap.BUTT);
            border.setStrokeWidth(2 * k);
            String sc = String.valueOf(ring);
            c.drawText(sc, rx - heroScore.measureText(sc) / 2, ry + Ui.halfCap(heroScore), heroScore);
            float tx = x + 2 * rr + 24 * k;
            String label = t.score <= 0 ? Ui.tr("Critics")
                    : Ui.tr("Critics") + (t.tier == null || t.tier.isEmpty() ? "" : " · " + t.tier)
                    + (t.critics > 0 ? " · " + Ui.trf("{n} reviews", "n", t.critics) : "");
            c.drawText(label, tx, ry - 6 * k, heroInfo);
            c.drawText(feedFit(info, heroInfo, 1100 * k), tx, ry + 24 * k, heroInfo);
        } else if (!info.isEmpty()) {
            c.drawText(feedFit(info, heroInfo, 1200 * k), x, ry + Ui.halfCap(heroInfo), heroInfo);
        }
        if (layer) c.restore();
        // The carousel: a dot per game (the one shown long, in the accent), then what's next.
        float dy = y + (HERO_H - 30) * k, dx = x;
        for (int i = 0; i < n; i++) {
            float dw = i == at ? 40 * k : 12 * k;
            glassTmp.set(dx, dy - 6 * k, dx + dw, dy + 6 * k);
            fill.setColor(i == at ? accent(1) : 0x59FFFFE0);
            c.drawRoundRect(glassTmp, 6 * k, 6 * k, fill);
            dx += dw + 10 * k;
        }
        if (n > 1) {
            Discover.Title next = row.items.get((at + 1) % n);
            String nx = Ui.trf("Next: {name}", "name", next.title)
                    + (Discover.upcoming(next) ? " · " + gameWhen(next.date) : "");
            feedMeta.setAlpha(165);
            c.drawText(feedFit(nx, feedMeta, 900 * k), dx + 14 * k, dy + Ui.halfCap(feedMeta), feedMeta);
            feedMeta.setAlpha(255);
        }
    }

    /** A game's line on its wide card: the same as its tile. */
    private static String gameMeta(String row, Discover.Title t) {
        return gameTileMeta(row, t);
    }

    private void feedPaints() {
        if (feedLabel != null) return;
        feedLabel = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        feedRankBig = Ui.text(Ui.displaySemibold, 92 * k, 0xEBFFFFFF);
        feedRank = Ui.text(Ui.displaySemibold, 64 * k, 0xD9FFFFFF);
        feedTitleBig = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
        feedTitleWide = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        feedTitle = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        feedMeta = Ui.text(Ui.medium, 21 * k, 0xBFFFFFFF);
        feedChip = Ui.text(Ui.semibold, 17 * k, 0xFFFFFFFF);
        for (Paint p : new Paint[] {feedLabel, feedRankBig, feedRank, feedTitleBig, feedTitleWide, feedTitle, feedMeta, feedChip}) p.setColorFilter(IVORY_TEXT);
    }

    private String feedFit(String text, Paint p, float room) {
        String key = Math.round(p.getTextSize()) + "|" + Math.round(room) + "|" + text;
        String fit = feedTexts.get(key);
        if (fit == null) {
            if (feedTexts.size() > 600) feedTexts.clear();
            fitPaint.set(p);
            fit = TextUtils.ellipsize(text, fitPaint, room, TextUtils.TruncateAt.END).toString();
            feedTexts.put(key, fit);
        }
        return fit;
    }

    private void drawFeed(Canvas c) {
        feedPaints();
        List<Discover.Row> rows = feedRows();
        boolean loading = gamesOn ? Games.get(getContext()).loading() : Discover.get(getContext()).loading();
        c.save();
        c.clipRect(0, 200 * k, w, h);
        float scrollY = feedScrollY.value;
        drawFeedSwitch(c, scrollY);
        // A switch: the new section's rows fade in (a layer only during those 200 ms).
        float fade = Ui.clamp(feedSection.value);
        boolean faded = fade < 0.995f;
        if (faded) c.saveLayerAlpha(0, 200 * k, w, h, Math.round(255 * fade));
        if (rows.isEmpty()) {
            if (loading) drawFeedSkeleton(c);
            else {
                emptyTitle.setTextAlign(Paint.Align.CENTER);
                boolean noKey = gamesOn && !Games.configured();
                c.drawText(noKey ? Ui.tr("Games need a data key.") : gamesOn ? Ui.tr("Games couldn't load right now.")
                        : Ui.tr("For You couldn't load right now."), w / 2f, 560 * k, emptyTitle);
                c.drawText(noKey ? Ui.tr("Add rawg_key to config.properties.") : Ui.tr("It tries again by itself in a few minutes."),
                        w / 2f, 610 * k, emptyText);
            }
            if (faded) c.restore();
            c.restore();
            return;
        }
        int focusedRow = !sheetOpen && zone == Z_CARDS ? feedRow : -1;
        for (int r = 0; r < rows.size(); r++) {
            Discover.Row row = rows.get(r);
            float top = feedRowTop(rows, r) - scrollY;
            float cardH = feedCardH(row.key);
            float label = feedLabelH(row.key);
            if ((top + label + cardH + 40 + (movieRow(row.key) ? MV_INFO : 0)) * k < 200 * k || top * k > h) continue;
            if (Games.HERO.equals(row.key)) {
                drawBento(c, row, top);
                continue;
            }
            if (movieRow(row.key)) drawMovieHeading(c, row.name, top);
            else c.drawText(row.name, MARGIN * k, (top + 30) * k, feedLabel);
            if (railRow(row.key)) {
                drawRail(c, row, top + FEED_LABEL, r == focusedRow);
                continue;
            }
            if (Games.UPCOMING.equals(row.key)) {
                drawCalendar(c, row, top, r == focusedRow);
                continue;
            }
            float off = feedSpring(feedScrollX, row.key, 0.24f, 1f).value;
            int focusedCol = r == focusedRow ? feedColOf(row.key) : -1;
            int prominent = feedProminent(rows, r);
            // Positions from the widths as they are now (a card widening pushes the next ones along, smoothly).
            if (feedXs.length < row.items.size()) {   // kept between frames: no allocation while drawing
                feedXs = new float[row.items.size()];
                feedWs = new float[row.items.size()];
            }
            float[] xs = feedXs, ws = feedWs;
            float x = MARGIN - off;
            for (int i = 0; i < row.items.size(); i++) {
                float pr = Ui.clamp(feedProm(row.key, i, i == prominent ? 1 : 0).value);
                ws[i] = feedNormalW(row.key) + (feedProminentW(row.key) - feedNormalW(row.key)) * pr;
                xs[i] = x;
                x += ws[i] + FEED_GAP;
            }
            for (int i = 0; i < row.items.size(); i++) {
                if (i == focusedCol) continue;
                drawFeedCard(c, row, i, xs[i], ws[i], top + label);
            }
            if (focusedCol >= 0 && focusedCol < row.items.size()) {
                drawFeedCard(c, row, focusedCol, xs[focusedCol], ws[focusedCol], top + label);
                feedFocused.set(cell);
            }
            // Movies V1: the wide card's description under it, as in Games (it fades in as the card widens).
            if (movieRow(row.key) && prominent >= 0 && prominent < row.items.size()) {
                drawMovieInfo(c, row.items.get(prominent), xs[prominent], top + label + cardH, Ui.clamp(feedProm(row.key, prominent, 1).value));
            }
        }
        if (gamesOn) {   // RAWG's free plan: a credit where its data shows
            Discover.Row last = rows.get(rows.size() - 1);
            float y = feedRowTop(rows, rows.size() - 1) + feedLabelH(last.key) + feedCardH(last.key) + 44 - scrollY;
            feedMeta.setAlpha(150);
            // (no source names on the Games page, by the owner's choice)
            feedMeta.setAlpha(255);
        }
        if (faded) c.restore();
        c.restore();
    }

    // MARK: - Tabs V1: the sub tabs (Pedestal Tiles): icon only, 72 px, the section shown in ivory glass with a warm
    // light under it; the remote on them: lifts 8, grows 5 %, ivory ring, and its icon plays.

    private long feedSwitchedAt, feedPlayFrom;
    private final Spring feedSwitchVis = new Spring(0.24f, 1f, 1);   // the sub tabs: shown above the rows, hidden in them
    private boolean feedWasPlaying;
    private LinearGradient subIvory, subGraphite;
    private android.graphics.RadialGradient subGlow;
    private final Matrix subM = new Matrix();

    private boolean subIconsPlaying() {
        if (tab != FOR_YOU_TAB || sheetOpen || feedScrollY.value > FEED_TOP) return false;
        return TabsLogic.playing(feedSwitchFocus.target > 0.5f, feedSwitchedAt, SystemClock.uptimeMillis());
    }

    private void drawFeedSwitch(Canvas c, float scrollY) {
        float top = (FEED_TOP - scrollY) * k, t = TabsLogic.TILE * k, gap = 16 * k;
        if (top + t + 24 * k < 200 * k) return;   // scrolled away with the page
        if (subIvory == null) {
            subIvory = new LinearGradient(0, 0, 0, 1, 0x38FFF8EC, 0x0FFFF8EC, Shader.TileMode.CLAMP);
            subGraphite = new LinearGradient(0, 0, 0.36f, 1, 0xFF2E2C2A, 0xFF141312, Shader.TileMode.CLAMP);
            subGlow = new android.graphics.RadialGradient(0.5f, 0.5f, 0.5f, 0x8CFFE2AA, 0x00FFE2AA, Shader.TileMode.CLAMP);
        }
        float focus = Ui.clamp(feedSwitchFocus.value), vis = Ui.clamp(feedSwitchVis.value);
        if (vis < 0.01f) return;   // hidden while the remote is in the rows
        int layer = vis < 0.99f ? c.saveLayerAlpha(0, top - 30 * k, (MARGIN + 240) * k, top + t + 40 * k, Math.round(255 * vis)) : -1;
        long now = SystemClock.uptimeMillis();
        boolean play = subIconsPlaying();
        if (play && !feedWasPlaying) feedPlayFrom = now;   // each play starts at the icon's rest
        feedWasPlaying = play;
        float time = play ? (now - feedPlayFrom) / 1000f : Float.NaN;
        for (int i = 0; i < 2; i++) {
            boolean selected = (i == 1) == gamesOn;
            float f = selected ? focus : 0, sc = 1, lift = 0;   // focus: the ring only
            float cx = MARGIN * k + i * (t + gap) + t / 2, cy = top + t / 2 - lift, half = t * sc / 2, rr = 24 * k * sc;
            cell.set(cx - half, cy - half, cx + half, cy + half);
            if (selected) {
                tmp.set(cell);
                tmp.inset(8 * k, 8 * k);
                tmp.offset(0, 24 * k);
                shadowDeep.setColor(0x99000000);
                shadows.drawCached(c, tmp, rr, 32 * k, shadowDeep);
                // The warm light under it, on the "floor".
                glassTmp.set(cell.left + 4 * k, cell.bottom + 2 * k, cell.right - 4 * k, cell.bottom + 14 * k);
                subM.setScale(glassTmp.width(), glassTmp.height());
                subM.postTranslate(glassTmp.left, glassTmp.top);
                subGlow.setLocalMatrix(subM);
                fill.setShader(subGlow);
                c.drawOval(glassTmp, fill);
            }
            subM.setScale(cell.width(), cell.height());
            subM.postTranslate(cell.left, cell.top);
            LinearGradient g = selected ? subIvory : subGraphite;
            g.setLocalMatrix(subM);
            fill.setShader(g);
            fill.setAlpha(selected ? 255 : 191);
            c.drawRoundRect(cell, rr, rr, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            border.setStrokeWidth((selected ? 2 : 1) * k);
            border.setColor(selected ? 0x80FFF8EC : 0x29FFFFFF);
            float in = border.getStrokeWidth() / 2;
            glassTmp.set(cell.left + in, cell.top + in, cell.right - in, cell.bottom - in);
            c.drawRoundRect(glassTmp, rr - in, rr - in, border);
            border.setStrokeWidth(2 * k);
            float icon = 44 * k * sc, it = selected ? time : Float.NaN;
            int alpha = selected ? 255 : 191;
            if (i == 0) TabIcons.popcorn(c, cx, cy, icon, it, alpha);
            else TabIcons.controller(c, cx, cy, icon, it, alpha);
            if (f > 0.01f) {   // the remote on it: the ivory ring, 3 px out
                float o = 5.5f * k;
                glassTmp.set(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o);
                border.setColor(Ui.alpha(ACCENT, f));
                border.setStrokeWidth(3 * k);
                c.drawRoundRect(glassTmp, rr + o, rr + o, border);
                border.setStrokeWidth(2 * k);
            }
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /**
     * A title: its artwork (backdrop for the wide ones, poster for the tall ones), shaded below for the text; trending
     * shows its rank. Top right: the app streaming it (its real icon; another service: a play badge; "+1" for more),
     * else a cinema badge for a confirmed theatrical release, else nothing. Top left on future titles: the date.
     * Focused: it grows a little and rises, its shadow deepens, a white rim follows its corners one gap out.
     */
    private final RectF feedRest = new RectF();

    /** A For You card: resting, from the shared cache (its shadow, art, text and badges in one bitmap); else painted. */
    private void drawFeedCard(Canvas c, Discover.Row row, int i, float x, float bw, float y) {
        float bh = feedCardH(row.key);
        if ((x + bw) * k < 0 || x * k > w) return;
        float f = feedSpring(feedFocus, row.key + i, 0.24f, 0.85f).value;
        boolean game = gameRow(row.key);
        float pr = game ? 0 : Ui.clamp(feedProm(row.key, i, 0).value);
        if (Math.abs(f) < 0.002f && pr < 0.002f) {
            Discover.Title t = row.items.get(i);
            Discover d = Discover.get(getContext());
            float H = bh * k, r0 = 20 * k;
            Bitmap tall = d.card(t.poster.isEmpty() ? t.backdrop : t.poster, t.poster.isEmpty(), Math.round(feedNormalW(row.key) * k), Math.round(H), r0);
            if (Math.abs(i - feedColOf(row.key)) <= 2) {   // the wide art ready ahead for the cards next to the focus
                d.card(t.backdrop.isEmpty() ? t.poster : t.backdrop, !t.backdrop.isEmpty(), Math.round(feedProminentW(row.key) * k), Math.round(H), r0);
            }
            long sig = 17;
            sig = 31 * sig + System.identityHashCode(t);
            sig = 31 * sig + System.identityHashCode(tall);
            sig = 31 * sig + i;
            sig = 31 * sig + Float.floatToIntBits(bw) + 7L * Float.floatToIntBits(bh) + Float.floatToIntBits(k);
            sig = 31 * sig + (game ? gameTileMeta(row.key, t).hashCode() * 3L + gameBadge(row.key, t).hashCode() : Discover.upcoming(t) ? 1 : 0);
            sig = 31 * sig + (t.app == null ? 0 : t.app.hashCode()) + t.moreApps;
            feedRest.set(x * k, y * k, (x + bw) * k, (y + bh) * k);
            long key = ((long) row.key.hashCode() << 24) ^ (i + 1);
            if (heroCards.draw(c, key, sig, feedRest, 72, pc -> paintFeedCard(pc, row, i, x, bw, y, true))) return;
        }
        paintFeedCard(c, row, i, x, bw, y, false);
    }

    /** A For You card at its focus and width (`resting`: as it rests, for the cache). */
    private void paintFeedCard(Canvas c, Discover.Row row, int i, float x, float bw, float y, boolean resting) {
        float bh = feedCardH(row.key);
        Discover.Title t = row.items.get(i);
        float f = resting ? 0 : feedSpring(feedFocus, row.key + i, 0.24f, 0.85f).value, fc = Ui.clamp(f);
        boolean game = gameRow(row.key);
        float pr = game || resting ? 0 : Ui.clamp(feedProm(row.key, i, 0).value);   // game tiles don't widen: they grow
        float s = 1 + (game ? 0.06f : 0.02f) * f;   // small: the card already widens with focus
        float cx = (x + bw / 2) * k, cy = (y + bh / 2) * k - 3 * k * f, W = bw * k, H = bh * k, r0 = 20 * k;
        cell.set(cx - W * s / 2, cy - H * s / 2, cx + W * s / 2, cy + H * s / 2);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-W / 2, -H / 2 + (8 + 14 * f) * k / s, W / 2, H / 2 + (8 + 14 * f) * k / s);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.3f + 0.28f * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        // The art: the poster while tall, the backdrop while wide, crossfading as it widens; each made once at its
        // settled size and cut to the card's proportions now (never stretched), inside the card's rounded shape.
        tmp.set(-W / 2, -H / 2, W / 2, H / 2);
        Discover d = Discover.get(getContext());
        Bitmap tall = d.card(t.poster.isEmpty() ? t.backdrop : t.poster, t.poster.isEmpty(),
                Math.round(feedNormalW(row.key) * k), Math.round(H), r0);
        // The wide art: when widening, and ready ahead for the cards next to the focus (no empty frame as one widens).
        boolean soon = pr > 0.001f || Math.abs(i - feedColOf(row.key)) <= 2;
        Bitmap wide = soon ? d.card(t.backdrop.isEmpty() ? t.poster : t.backdrop, !t.backdrop.isEmpty(),
                Math.round(feedProminentW(row.key) * k), Math.round(H), r0) : null;
        // Each art as the shader of a rounded rectangle (no clip mask: on this GPU a clip path cost a mask on every frame
        // of a card widening).
        if (tall == null && wide == null) {
            fill.setShader(null);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(tmp, r0, r0, fill);
        } else {
            if (tall != null && pr < 0.999f) drawCoveredRound(c, tall, tmp, r0, wide == null ? 1 : 1 - pr);
            if (wide != null && pr > 0.001f) drawCoveredRound(c, wide, tmp, r0, tall == null ? 1 : pr);
        }
        float pad = (W > 400 * k ? 30 : 18) * k, left = -W / 2 + pad, bottom = H / 2 - pad;
        boolean trending = Discover.TRENDING.equals(row.key), movie = movieRow(row.key);
        if (movie) drawMovieCardText(c, t, i, trending, W, H, pr);
        // Text: the wide layout (big title; Trending also its big rank and the kind) and the tall one, crossfading.
        int wideA = Math.round(255 * pr), tallA = Math.round(255 * (1 - pr));
        if (!movie && wideA > 2) {
            float tx = left;
            if (trending) {
                String rank = String.valueOf(i + 1);
                feedRankBig.setAlpha(wideA);
                c.drawText(rank, left, bottom - 6 * k, feedRankBig);
                feedRankBig.setAlpha(255);
                tx = left + feedRankBig.measureText(rank) + 18 * k;
            }
            Paint big = trending ? feedTitleBig : feedTitleWide;
            big.setAlpha(wideA);
            c.drawText(feedFit(t.title, big, W / 2 - tx - 70 * k), tx, bottom - (trending ? 44 : 34) * k, big);
            big.setAlpha(255);
            String meta = "game".equals(t.kind) ? gameMeta(row.key, t)
                    : t.anime ? Ui.tr("Anime") : "tv".equals(t.kind) ? Ui.tr("Series") : Ui.tr("Movie");
            feedMeta.setAlpha(Math.round(wideA * 0.75f));
            c.drawText(feedFit(meta, feedMeta, W - (tx - left) - 2 * pad), tx + 2 * k, bottom, feedMeta);
            feedMeta.setAlpha(255);
        }
        if (game) {
            // A game tile: its name, and under it platforms and genre (Coming soon: how many want it).
            float room = W - 2 * pad;
            c.drawText(feedFit(t.title, feedTitle, room), left, bottom - 26 * k, feedTitle);
            String meta = gameTileMeta(row.key, t);
            feedChip.setAlpha(190);
            c.drawText(feedFit(meta, feedChip, room), left, bottom, feedChip);
            feedChip.setAlpha(255);
        } else if (!movie && tallA > 2) {
            feedTitle.setAlpha(tallA);
            float room = feedNormalW(row.key) * k - 2 * pad;
            if (trending) {
                feedRank.setAlpha(tallA);
                c.drawText(String.valueOf(i + 1), left, bottom - 46 * k, feedRank);
                feedRank.setAlpha(255);
            }
            c.drawText(feedFit(t.title, feedTitle, room), left, bottom, feedTitle);
            feedTitle.setAlpha(255);
        }
        // Top left: games: released when, coming when, or the place in Popular; other titles: the date, if still to come.
        String badge = game ? gameBadge(row.key, t) : Discover.upcoming(t) ? Discover.shortDate(t.date) : "";
        if (!badge.isEmpty()) {
            String date = badge;
            float cw = feedChip.measureText(date) + 54 * k, ch = 34 * k, tx = -W / 2 + 14 * k, ty = -H / 2 + 14 * k;
            fill.setShader(null);
            if (!movie) {
                glassTmp.set(tx, ty, tx + cw, ty + ch);
                fill.setColor(0xAA141416);
                c.drawRoundRect(glassTmp, ch / 2, ch / 2, fill);
                border.setColor(0x29FFFFFF);
                border.setStrokeWidth(1 * k);
                c.drawRoundRect(glassTmp, ch / 2, ch / 2, border);
                border.setStrokeWidth(2 * k);
            }
            if (movie) {   // Movies V1: the date alone in a dark pill
                glassTmp.set(tx, ty, tx + feedChip.measureText(date) + 24 * k, ty + ch);
                fill.setColor(0xBF0E0A0B);
                c.drawRoundRect(glassTmp, ch / 2, ch / 2, fill);
                c.drawText(date, tx + 12 * k, ty + ch / 2 + Ui.halfCap(feedChip), feedChip);
            } else {
                Symbols.draw(getContext(), c, game && Games.POPULAR.equals(row.key) ? "sparkles" : "event", tx + 22 * k, ty + ch / 2, 18 * k, accent(0.95f));
                c.drawText(date, tx + 38 * k, ty + ch / 2 + Ui.halfCap(feedChip), feedChip);
            }
        }
        // Top right: where it is (an indicator only).
        float bs = (W > 250 * k ? 40 : 34) * k, bx = W / 2 - 12 * k - bs, by = -H / 2 + 12 * k;
        Bitmap service = t.app == null || "other".equals(t.app) ? null : HomeApps.serviceArt(getContext(), t.app);
        if (service != null && movie) {   // Movies V1: the app's round 3D icon, as on Home
            drawRoundIcon(c, service, W / 2 - 16 * k - 40 * k, -H / 2 + 16 * k, 40 * k);
        } else if (service != null) {
            glassTmp.set(bx, by, bx + bs, by + bs);
            roundImage(c, service, glassTmp, bs * 0.23f);
        } else if (t.app != null || t.cinema) {
            fill.setShader(null);
            fill.setColor(0xAA141416);
            c.drawCircle(bx + bs / 2, by + bs / 2, bs / 2, fill);
            Symbols.draw(getContext(), c, t.app != null ? "play" : "theaters", bx + bs / 2, by + bs / 2, bs * 0.5f, accent(0.95f));
        }
        if (false) {   // (was Critics' picks' circle: the score is now in the tile's line, with its source)
            fill.setShader(null);
            fill.setColor(0xAA141416);
            c.drawCircle(bx + bs / 2, by + bs / 2, bs / 2, fill);
            border.setColor(0xD9FFFFFF);
            border.setStrokeWidth(1.5f * k);
            c.drawCircle(bx + bs / 2, by + bs / 2, bs / 2 - 0.75f * k, border);
            border.setStrokeWidth(2 * k);
            String score = String.valueOf(t.score);
            c.drawText(score, bx + bs / 2 - feedChip.measureText(score) / 2, by + bs / 2 + Ui.halfCap(feedChip), feedChip);
        }
        if (t.app != null && t.moreApps > 0) {
            String more = "+" + t.moreApps;
            c.drawText(more, bx - 8 * k - feedChip.measureText(more), by + bs / 2 + Ui.halfCap(feedChip), feedChip);
        }
        if (fc > 0.01f) {
            float g = (movie ? 4.5f : 6) * k / s;   // Movies V1: Home's ring, ivory, 3 px out
            glassTmp.set(-W / 2 - g, -H / 2 - g, W / 2 + g, H / 2 + g);
            // Can't be opened here: the same rim, quieter (a subtle hint, not an overlay); OK does nothing on it.
            float strength = "game".equals(t.kind) || titleLaunchable(t.kind, t.id, t.app) ? (movie ? 1 : 0.9f) : 0.4f;
            border.setColor(Ui.alpha(movie ? ACCENT : 0xFFFFFFFF, strength * fc));
            border.setStrokeWidth(3 * k / s);
            c.drawRoundRect(glassTmp, r0 + g, r0 + g, border);
            border.setStrokeWidth(2 * k);
        }
        c.restore();
    }

    // MARK: - Movies V1 (For You's movie and show rows)

    private Paint mvHead, mvHeadIt, mvRankBig, mvRank;
    private TextPaint mvTitle, mvMeta, mvSmall, mvDesc;
    private final java.util.HashMap<String, String[]> mvTexts = new java.util.HashMap<>();   // measured once per text

    private void mvPaints() {
        if (mvHead != null) return;
        mvHead = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        mvHeadIt = Ui.text(Ui.displayItalic, 40 * k, 0xD9FFFFFF);
        mvRankBig = Ui.text(Ui.displaySemibold, 120 * k, 0xFFFFFFFF);
        mvRank = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        mvTitle = new TextPaint(Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF));
        mvMeta = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xD9FFFFFF));
        mvSmall = new TextPaint(Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF));
        mvDesc = new TextPaint(Ui.text(Ui.regular, 19 * k, 0xCCFFFFFF));
        for (Paint p : new Paint[] {mvHead, mvHeadIt, mvRankBig, mvRank, mvTitle, mvMeta, mvSmall, mvDesc}) p.setColorFilter(IVORY_TEXT);
    }

    /** A text cut into at most `lines` lines of `room` px (cached: never measured on every frame). */
    private String[] mvWrap(String text, TextPaint p, float room, int lines) {
        String key = Math.round(p.getTextSize()) + "|" + Math.round(room) + "|" + lines + "|" + text;
        String[] out = mvTexts.get(key);
        if (out == null) {
            if (mvTexts.size() > 400) mvTexts.clear();
            out = TitleWrap.wrap(text, room, p::measureText, lines);
            mvTexts.put(key, out);
        }
        return out;
    }

    /** A row's heading: an ivory accent line, then its first word upright and the rest italic ("Trending *this week*"). */
    private void drawMovieHeading(Canvas c, String name, float top) {
        mvPaints();
        String[] parts = mvTexts.get("head|" + name);
        if (parts == null) {
            String[] h = MoviesLogic.heading(name);
            parts = new String[] {h[0], h[1], String.valueOf(mvHead.measureText(h[0] + " "))};
            mvTexts.put("head|" + name, parts);
        }
        float base = (top + 38) * k, x = MARGIN * k, cy = base - Ui.halfCap(mvHead);
        fill.setShader(null);
        fill.setColor(ACCENT);
        c.drawRoundRect(x, cy - 2 * k, x + 48 * k, cy + 2 * k, 2 * k, 2 * k, fill);
        x += 64 * k;
        c.drawText(parts[0], x, base, mvHead);
        if (!parts[1].isEmpty()) c.drawText(parts[1], x + Float.parseFloat(parts[2]), base, mvHeadIt);
    }

    /**
     * A movie card's text (card-centred coordinates). Wide (focused): Trending's big rank, the title in the serif face
     * (2 lines at most), "Genre · Genre · ★7.6"; tall: Trending's rank and the title on one line. They crossfade with pr.
     */
    private void drawMovieCardText(Canvas c, Discover.Title t, int i, boolean trending, float W, float H, float pr) {
        mvPaints();
        int wideA = Math.round(255 * pr), tallA = Math.round(255 * (1 - pr));
        if (wideA > 2) {
            float left = -W / 2 + 24 * k, right = W / 2 - 24 * k, bottom = H / 2 - 20 * k, tx = left;
            if (trending) {
                String rank = String.valueOf(i + 1);
                mvRankBig.setAlpha(wideA);
                c.drawText(rank, left, bottom, mvRankBig);
                mvRankBig.setAlpha(255);
                tx = left + mvRankBig.measureText(rank) + 20 * k;
            }
            float room = Math.max(0, right - tx);
            String meta = MoviesLogic.rating(t.vote);
            meta = t.genres.isEmpty() ? (meta.isEmpty() ? "" : "\u2605" + meta) : meta.isEmpty() ? t.genres : t.genres + " \u00B7 \u2605" + meta;
            float titleBase = bottom;
            if (!meta.isEmpty()) {
                mvMeta.setAlpha(Math.round(wideA * 0.85f));
                drawMetaLine(c, meta, tx, bottom, mvMeta, room, pr);
                mvMeta.setAlpha(255);
                titleBase = bottom - 17 * k - 10 * k;
            }
            String[] lines = mvWrap(t.title, mvTitle, room, 2);
            float lineH = 36 * 1.05f * k;
            mvTitle.setAlpha(wideA);
            for (int n = 0; n < lines.length; n++) c.drawText(lines[n], tx, titleBase - (lines.length - 1 - n) * lineH, mvTitle);
            mvTitle.setAlpha(255);
        }
        if (tallA > 2) {
            float left = -W / 2 + 16 * k, bottom = H / 2 - 14 * k, room = (MV_W - 32) * k;
            if (trending) {
                mvRank.setAlpha(tallA);
                c.drawText(String.valueOf(i + 1), left, H / 2 - 44 * k, mvRank);
                mvRank.setAlpha(255);
            }
            mvSmall.setAlpha(tallA);
            c.drawText(mvWrap(t.title, mvSmall, room, 1)[0], left, bottom, mvSmall);
            mvSmall.setAlpha(255);
        }
    }

    /** Under the wide card: its description, whole sentences in 3 lines at most, never "…" (nothing when TMDB has none). */
    private void drawMovieInfo(Canvas c, Discover.Title t, float x, float cardsBottom, float a) {
        if (a < 0.02f || t.overview.isEmpty()) return;
        mvPaints();
        String key = "desc|" + t.kind + t.id;
        String[] lines = mvTexts.get(key);
        if (lines == null) {   // measured once per title
            if (mvTexts.size() > 400) mvTexts.clear();
            lines = MoviesLogic.descriptionLines(t.overview, MV_WIDE * k, mvDesc::measureText, 3);
            mvTexts.put(key, lines);
        }
        mvDesc.setAlpha(Math.round(204 * a));
        for (int n = 0; n < lines.length; n++) c.drawText(lines[n], x * k, (cardsBottom + MV_DESC_GAP + 19 + n * MV_DESC_LINE) * k, mvDesc);
        mvDesc.setAlpha(255);
    }

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix coverM = new Matrix();

    /** `b` into the rounded `dst` like CSS object-fit: cover (centred, never stretched), at `alpha`, without a clip. */
    private void drawCoveredRound(Canvas c, Bitmap b, RectF dst, float radius, float alpha) {
        BitmapShader shader = imageShaders.get(b);
        if (shader == null) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(b, shader);
        }
        float bw = b.getWidth(), bh = b.getHeight(), sc = Math.max(dst.width() / bw, dst.height() / bh);
        coverM.setScale(sc, sc);
        coverM.postTranslate(dst.centerX() - bw * sc / 2, dst.centerY() - bh * sc / 2);
        shader.setLocalMatrix(coverM);
        coverPaint.setShader(shader);
        coverPaint.setAlpha(Math.round(255 * Ui.clamp(alpha)));
        c.drawRoundRect(dst, radius, radius, coverPaint);
        coverPaint.setShader(null);
    }

    /** `b` into `dst` like CSS object-fit: cover (centred; cut, never stretched), at `alpha`. */
    private void drawCovered(Canvas c, Bitmap b, RectF dst, float alpha) {
        float bw = b.getWidth(), bh = b.getHeight(), want = dst.width() / dst.height();
        if (bw / bh > want) {
            float sw = bh * want;
            bentoSrc.set(Math.round((bw - sw) / 2), 0, Math.round((bw + sw) / 2), Math.round(bh));
        } else {
            float sh = bw / want;
            bentoSrc.set(0, Math.round((bh - sh) / 2), Math.round(bw), Math.round((bh + sh) / 2));
        }
        bitmapPaint.setAlpha(Math.round(255 * Ui.clamp(alpha)));
        c.drawBitmap(b, bentoSrc, dst, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    /** Before the first feed: the rows' shapes, faintly (no spinner, no layout jump when they arrive). */
    private void drawFeedSkeleton(Canvas c) {
        fill.setShader(null);
        fill.setColor(0x14FFFFFF);
        String[] keys = gamesOn ? new String[] {Games.RECENT, Games.POPULAR} : new String[] {Discover.TRENDING, Discover.SEARCHES};
        float y = FEED_TOP + SWITCH_SPACE;
        for (String key : keys) {
            c.drawRoundRect(MARGIN * k, (y + 8) * k, (MARGIN + 260) * k, (y + 34) * k, 13 * k, 13 * k, fill);
            float x = MARGIN, ch = feedCardH(key);
            for (int i = 0; x < CONTENT_R; i++) {
                float cw = feedCardW(key, i, Discover.TRENDING.equals(key) || railRow(key) ? 0 : -1);
                float lab = feedLabelH(key);
                c.drawRoundRect(x * k, (y + lab) * k, Math.min(x + cw, CONTENT_R) * k, (y + lab + ch) * k, 20 * k, 20 * k, fill);
                x += cw + FEED_GAP;
            }
            y += feedRowSpan(key);
        }
    }

    // Apps geometry (1080p units), for where it's shown: in its tab (between the tabs and the dock, aligned with the
    // Home grid: 6 columns from MARGIN to CONTENT_R) or full screen (no tabs: no picks).
    private float aClipTop, aClipBottom, aSearchTop, aSearchH, aLabel, aChipsTop, aChipH, aGridTop, aW, aH, aGap, aRow;
    private int aCols = COLUMNS;

    private boolean appsInTab() {
        return picksOn();
    }

    /**
     * Apps (design A, an icon library): a centred search bar, the suggested apps as a centred row of icons under it,
     * then every app as an 8-column grid of square icons from MARGIN to CONTENT_R, names under them. The same layout
     * in its tab (under the tabs, the dock hidden) and full screen (no tabs: its own "Apps" title above).
     */
    private void appsGeometry() {
        aClipTop = appsInTab() ? 200 : 0;
        aClipBottom = 1080;
        aSearchTop = 222;
        aSearchH = 72;
        aLabel = 346;
        aChipsTop = 368;
        aChipH = 104;           // the suggested apps' icons (the chips under a search are CHIP_PILL tall)
        aGridTop = 548;
        aCols = 8;
        aW = 156;
        aH = 156;
        aGap = (CONTENT_R - MARGIN - aCols * aW) / (aCols - 1);   // 73
        aRow = 240;
    }

    /** Apps: a pill under a search (the store's search, clearing it), and the search bar's width. */
    private static final float CHIP_PILL = 60, SEARCH_W = 960, APP_RADIUS = 0.5f;   // round icons

    /**
     * Focus into Apps (in its tab): `at` = where (A_SEARCH from the tabs above), or -1: where it was, else the first
     * suggestion, else the first app.
     */
    private void enterApps(int at) {
        appsGeometry();
        sheetOpen = true;
        if (at >= 0) {
            appsZone = at;
        } else if (appsZone == A_GRID && !others.isEmpty()) {
            gridIndex = Math.min(gridIndex, others.size() - 1);
        } else {
            appsZone = !chips.isEmpty() ? A_SUGGEST : !others.isEmpty() ? A_GRID : A_SEARCH;
            chipIndex = 0;
        }
        if (appsZone == A_SUGGEST && chips.isEmpty()) appsZone = A_SEARCH;
        if (appsZone == A_GRID && others.isEmpty()) appsZone = A_SEARCH;
        retargetGrid(false);
        retarget(false);
        click.move();
        gridDirty = true;
    }

    /** Focus out of Apps (in its tab): to the tabs (on Apps) or the dock's first app; Apps stays shown. */
    private void leaveApps(int to) {
        stopTyping();
        sheetOpen = false;
        if (to == Z_SHELF) {
            zone = Z_SHELF;
            dockAwake = true;
            shelfIndex = 0;
            aimDockScroll(false);
        } else {
            zone = Z_TOP;
            topIndex = APPS_TAB;
        }
        retargetGrid(false);
        retarget(false);
        click.move();
        gridDirty = true;
    }

    /** The installed apps (HomeApps.others: every launchable one, A to Z); the grid shows those matching the search. */
    private void setOthers(List<HomeApps.Item> list) {
        appsAll = list;
        appLabels.clear();
        appLabelHalf.clear();
        applyQuery(true);
        warmAppIcons();
    }

    /**
     * The grid becomes the apps matching the search (all of them with no search). keepFocus: the focused app stays
     * focused if it's still there (a list refresh); otherwise focus goes to the first result, the best match.
     */
    private void applyQuery(boolean keepFocus) {
        String focusedKey = keepFocus && gridIndex < others.size() ? others.get(gridIndex).key : null;
        List<HomeApps.Item> list = filterApps(appsAll, appsQuery);
        java.util.HashMap<String, Spring> before = new java.util.HashMap<>();
        for (int i = 0; i < gridFocus.length && i < others.size(); i++) before.put(others.get(i).key, gridFocus[i]);
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) {
            Spring kept = before.get(list.get(i).key);   // the same app: its spring, mid-animation or at rest
            gridFocus[i] = kept != null ? kept : new Spring(0.3f, 0.82f, 0);
        }
        gridLabels = null;
        int at = 0;
        if (focusedKey != null) {
            for (int i = 0; i < list.size(); i++) if (focusedKey.equals(list.get(i).key)) at = i;
        }
        gridIndex = Math.min(at, Math.max(0, list.size() - 1));
        buildChips();
        // Focus never stays on something that's gone: no results → the suggestions (the store), else the search.
        if (appsZone == A_GRID && others.isEmpty()) appsZone = chips.isEmpty() ? A_SEARCH : A_SUGGEST;
        if (appsZone == A_SUGGEST && chips.isEmpty()) appsZone = others.isEmpty() ? A_SEARCH : A_GRID;
        chipIndex = Math.max(0, Math.min(chipIndex, chips.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /**
     * Apps whose name matches `query`, ignoring case and accents: names starting with it first, then a word starting
     * with it ("tv" → "Google TV"), then containing it, then containing it without spaces ("primevideo"); A to Z within
     * each (the list's own order), so results don't jump around while typing.
     */
    private static List<HomeApps.Item> filterApps(List<HomeApps.Item> all, String query) {
        String q = fold(query);
        if (q.isEmpty()) return all;
        String squeezed = q.replace(" ", "");
        List<List<HomeApps.Item>> ranks = new ArrayList<>();
        for (int i = 0; i < 4; i++) ranks.add(new ArrayList<>());
        for (HomeApps.Item it : all) {
            String l = fold(it.label);
            int r;
            if (l.startsWith(q)) r = 0;
            else if (wordStarts(l, q)) r = 1;
            else if (l.contains(q)) r = 2;
            else if (!squeezed.isEmpty() && l.replace(" ", "").contains(squeezed)) r = 3;
            else continue;
            ranks.get(r).add(it);
        }
        List<HomeApps.Item> out = new ArrayList<>();
        for (List<HomeApps.Item> r : ranks) out.addAll(r);
        return out;
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static boolean wordStarts(String l, String q) {
        for (int i = 1; i < l.length(); i++) {
            if (!Character.isLetterOrDigit(l.charAt(i - 1)) && l.startsWith(q, i)) return true;
        }
        return false;
    }

    /**
     * The chips under the search bar. No search: the suggested apps (HomeApps.suggestions: most used, else recently
     * opened, else recently installed; none when the TV gives none). A search: the store's search for it (when this TV
     * has a store or a browser) and clearing it; the matching apps themselves are the grid, not repeated here.
     */
    private void buildChips() {
        chips.clear();
        if (appsQuery.trim().isEmpty()) {
            for (String pkg : suggestPkgs) {
                if (chips.size() >= SUGGEST_MAX) break;
                for (HomeApps.Item it : appsAll) {
                    if (pkg.equals(it.pkg)) {
                        chips.add(new Chip(CHIP_APP, it, it.label));
                        break;
                    }
                }
            }
        } else {
            if (appStore != null) chips.add(new Chip(CHIP_STORE, null, storeLabel()));
            chips.add(new Chip(CHIP_CLEAR, null, Ui.tr("Clear search")));
        }
    }

    private String storeLabel() {
        String q = "\u201C" + appsQuery.trim() + "\u201D";
        if ("web".equals(appStore[0])) return Ui.trf("Search Google Play on the web for {q}", "q", q);
        return Ui.trf("Search {store} for {q}", "store", appStore[2], "q", q);
    }

    /** Suggestions and the store, read again in the background (usage, history and installs change). */
    private void refreshSuggestions() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<String> sug = HomeApps.suggestions(c, SUGGEST_MAX);
            final String[] store = HomeApps.appStore(c);
            boolean voice;
            try {
                voice = !c.getPackageManager().queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
            } catch (RuntimeException e) {
                voice = false;
            }
            final boolean hasVoice = voice;
            handler.post(() -> {
                voiceAvailable = hasVoice;
                setSuggestions(sug, store);
            });
        });
    }

    private void setSuggestions(List<String> sug, String[] store) {
        suggestLabel = sug.isEmpty() ? "" : sug.get(0);
        suggestPkgs = sug.isEmpty() ? new ArrayList<>() : new ArrayList<>(sug.subList(1, sug.size()));
        appStore = store;
        AppLog.w("apps: " + appsAll.size() + " apps; suggestions " + (sug.isEmpty() ? "none" : suggestLabel + " " + suggestPkgs)
                + "; store " + (store == null ? "none" : store[0] + " " + store[2]));
        int before = chips.size();
        buildChips();
        if (sheetOpen && appsZone == A_GRID && gridIndex == 0 && before == 0 && !chips.isEmpty() && appsQuery.isEmpty()) {
            appsZone = A_SUGGEST;   // just opened and they arrived: focus starts on the first suggestion
            chipIndex = 0;
        }
        if (appsZone == A_SUGGEST && chips.isEmpty()) appsZone = others.isEmpty() ? A_SEARCH : A_GRID;
        chipIndex = Math.max(0, Math.min(chipIndex, chips.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** The search changed (typed, or cleared): the grid shows its matches, focus on the best one when it's in the grid. */
    private void setQuery(String q) {
        if (q == null) q = "";
        if (q.equals(appsQuery)) return;
        appsQuery = q;
        if (searchInput != null && !q.equals(searchInput.getText().toString())) searchInput.setText(q);
        gridIndex = 0;
        applyQuery(false);
    }

    private void openSheet() {
        sheetOpen = true;
        appsQuery = "";
        if (searchInput != null) searchInput.setText("");
        appsZone = A_GRID;
        applyQuery(false);
        gridIndex = 0;
        chipIndex = 0;
        // Focus starts on the first suggestion (the app most likely wanted), else the first app, else the search.
        appsZone = !chips.isEmpty() ? A_SUGGEST : !others.isEmpty() ? A_GRID : A_SEARCH;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
        refreshSuggestions();
        updateVoiceMark();
    }

    private void closeSheet() {
        stopTyping();
        sheetOpen = false;
        updateVoiceMark();
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    /** Apps' focus springs (grid, search bar, keyboard key, suggestions): only the focused one at 1. True if any changed. */
    private boolean aimApps(boolean snap) {
        boolean changed = false;
        for (int i = 0; i < gridFocus.length; i++) {
            float t = sheetOpen && appsZone == A_GRID && i == gridIndex ? 1 : 0;
            changed |= gridFocus[i].target != t;
            gridFocus[i].target = t;
            if (snap) gridFocus[i].snap(t);
        }
        if (appsZone != A_SEARCH || !voiceAvailable) searchPart = 0;
        float st = sheetOpen && appsZone == A_SEARCH && searchPart == 0 ? 1 : 0;
        float kt = sheetOpen && appsZone == A_SEARCH && searchPart == 1 ? 1 : 0;
        changed |= searchFocus.target != st || keyboardFocus.target != kt;
        searchFocus.target = st;
        keyboardFocus.target = kt;
        if (snap) {
            searchFocus.snap(st);
            keyboardFocus.snap(kt);
        }
        for (int i = 0; i < chips.size(); i++) {
            Spring sp = chips.get(i).focus;
            float t = sheetOpen && appsZone == A_SUGGEST && i == chipIndex ? 1 : 0;
            changed |= sp.target != t;
            sp.target = t;
            if (snap) sp.snap(t);
        }
        return changed;
    }

    private void retargetGrid(boolean snap) {
        aimApps(snap);
        // …and the rest of the screen's focus (tabs, top buttons, dock): a move made only in Apps' terms (into its
        // search from the tabs) used to leave the tab's or a top button's focused glass on.
        retarget(snap);
        // The page scrolls (px, 1080p) so the focused row is whole in Apps' area: the first row shows the search and
        // the suggestions with it.
        appsGeometry();
        float target = 0;
        if (sheetOpen && appsZone == A_GRID && !others.isEmpty() && gridIndex >= aCols) {
            int r = gridIndex / aCols, rows = (others.size() + aCols - 1) / aCols;
            float top = aGridTop + r * aRow, bottom = top + aH + 52, upper = aClipTop + (appsInTab() ? 22 : 140);
            float lower = 1040;
            target = scroll.target;
            if (top - target < upper) target = top - upper;
            if (bottom - target > lower) target = bottom - lower;
            float max = Math.max(0, aGridTop + (rows - 1) * aRow + aH + 52 - lower);
            target = Math.max(0, Math.min(max, target));
        }
        scroll.target = target;
        if (snap) scroll.snap(target);
        spoken.focusMoved();
    }

    private boolean stepChips(float dt) {
        boolean moving = false;
        for (Chip ch : chips) moving |= ch.focus.step(dt);
        return moving;
    }

    // MARK: - Apps section: remote navigation (search bar ↕ suggestions ↕ grid)

    private void moveApps(int dx, int dy) {
        int zoneBefore = appsZone, chipBefore = chipIndex, gridBefore = gridIndex, partBefore = searchPart;
        appsGeometry();
        if (appsZone == A_SEARCH) {
            if (dy < 0 && appsInTab()) {
                leaveApps(Z_TOP);
                return;
            }
            if (dx != 0 && voiceAvailable) {
                searchPart = dx > 0 ? 1 : 0;   // → the keyboard button at the bar's end, ← back to the bar
            } else if (dy > 0) {
                if (!chips.isEmpty()) {
                    appsZone = A_SUGGEST;
                    chipIndex = 0;
                } else if (!others.isEmpty()) {
                    appsZone = A_GRID;
                    gridIndex = 0;
                }
            }
        } else if (appsZone == A_SUGGEST) {
            if (dx != 0) {
                chipIndex = Math.max(0, Math.min(chips.size() - 1, chipIndex + dx));
            } else if (dy < 0) {
                appsZone = A_SEARCH;
            } else if (!others.isEmpty()) {
                appsZone = A_GRID;
                gridIndex = Math.min(others.size() - 1, nearestColumn(chips.get(chipIndex).rect.centerX()));
            }
        } else {
            if (dy < 0 && gridIndex < aCols) {
                if (!chips.isEmpty()) {
                    appsZone = A_SUGGEST;
                    chipIndex = nearestChip(gridCenterX(gridIndex));
                } else {
                    appsZone = A_SEARCH;
                }
            } else {
                moveGrid(dx, dy);
                return;
            }
        }
        if (appsZone == zoneBefore && chipIndex == chipBefore && gridIndex == gridBefore && searchPart == partBefore) return;
        if (zoneBefore == A_SEARCH && appsZone != A_SEARCH) stopTyping();
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private float gridLeft() {
        appsGeometry();
        return MARGIN * k;
    }

    private float gridCenterX(int i) {
        return gridLeft() + (i % aCols) * (aW + aGap) * k + aW * k / 2;
    }

    private int nearestColumn(float x) {
        int col = Math.round((x - gridLeft() - aW * k / 2) / ((aW + aGap) * k));
        return Math.max(0, Math.min(aCols - 1, col));
    }

    private int nearestChip(float x) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < chips.size(); i++) {
            float d = Math.abs(chips.get(i).rect.centerX() - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Apps section: typing (the TV's own keyboard, through an invisible text field)

    private EditText makeSearchInput(Context a) {
        EditText e = new EditText(a) {
            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                // Back while the keyboard is up closes the keyboard (the keyboard does that); the search stays.
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) post(HomeView.this::keyboardClosed);
                return super.onKeyPreIme(keyCode, event);
            }
        };
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // In place: no full-screen editor over the results (they update as you type).
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setHint(Ui.tr("Search apps"));
        e.setContentDescription(Ui.tr("Search apps"));
        e.setBackground(null);
        e.setAlpha(0f);
        e.setFocusable(false);   // only while typing: it never takes the home screen's focus (or opens the keyboard) by itself
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (sheetOpen) setQuery(s.toString());
            }
        });
        e.setOnEditorActionListener((v, actionId, event) -> {
            finishTyping();
            return true;
        });
        return e;
    }

    private void startTyping() {
        if (searchInput == null) {   // first time: made now (not at start: Home loads first)
            searchInput = makeSearchInput(getContext());
            addView(searchInput);
        }
        typing = true;
        if (!appsQuery.equals(searchInput.getText().toString())) searchInput.setText(appsQuery);
        searchInput.setSelection(searchInput.getText().length());
        searchInput.setFocusable(true);
        searchInput.setFocusableInTouchMode(true);
        searchInput.requestFocus();
        searchInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && typing) imm.showSoftInput(searchInput, 0);
        });
        gridDirty = true;
    }

    /** The keyboard's Search/Done: the keyboard goes, focus to the best match (or the store's search when none). */
    private void finishTyping() {
        stopTyping();
        if (!sheetOpen) return;
        if (!others.isEmpty()) {
            appsZone = A_GRID;
            gridIndex = 0;
        } else if (!chips.isEmpty()) {
            appsZone = A_SUGGEST;
            chipIndex = 0;
        }
        retargetGrid(false);
        gridDirty = true;
    }

    private void keyboardClosed() {
        if (!typing) return;
        stopTyping();
    }

    private void stopTyping() {
        if (searchInput == null || !typing) return;
        typing = false;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchInput.clearFocus();
        searchInput.setFocusable(false);
        requestFocus();
        gridDirty = true;
    }

    /** The store's own search for what was typed (Google Play's app, another store, or Google Play in a browser). */
    private void openStore() {
        if (appStore == null || appsQuery.trim().isEmpty()) return;
        try {
            activity.startActivity(HomeApps.storeSearch(appStore, appsQuery).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppLog.w("apps: " + appStore[0] + " search opened");
            return;
        } catch (RuntimeException e) {
            AppLog.w("apps: " + appStore[0] + " search refused: " + e);
        }
        if (!"web".equals(appStore[0])) {   // the store app wouldn't search: Google Play's page, if a browser can open it
            try {
                activity.startActivity(HomeApps.storeSearch(new String[] {"web", null, "Google Play"}, appsQuery)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        showToast(Ui.trf("Couldn't open {store}.", "store", appStore[2]));
    }

    // MARK: - Apps section: voice (the system's speech recognizer; the remote's microphone while Apps shows)

    /** Apps is on screen: its tab, or (no tabs) its full-screen page. */
    private boolean appsShowing() {
        return appsInTab() ? tab == APPS_TAB : sheetOpen;
    }

    /** Tells the key filter whether the microphone is Apps' now (only while the home screen is running and Apps shows). */
    private void updateVoiceMark() {
        AppsVoice.setActive(getContext(), running && appsShowing());
    }

    /** The microphone pressed on the Apps page: focus into Apps (the search bar), then listen. */
    private void voiceFromRemote() {
        if (!appsShowing()) return;
        if (appsInTab() && !sheetOpen) enterApps(A_SEARCH);
        startVoice();
    }

    private void startVoice() {
        stopTyping();
        Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Say an app's name"))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try {
            voiceReturning = true;
            activity.startActivityForResult(in, AppsVoice.REQUEST);
            AppLog.w("apps: listening");
        } catch (RuntimeException e) {
            voiceReturning = false;
            voiceAvailable = false;
            AppLog.w("apps: no speech recognizer: " + e);
            showToast(Ui.tr("Voice search isn't available on this TV. Type instead."));
            gridDirty = true;
        }
    }

    /**
     * What was heard: the first of the recognizer's guesses that matches an installed app ("open Netflix." counts as
     * "Netflix"), else the first guess as said; it becomes the search, focus on the best match (or the store's search).
     */
    void onVoiceResult(int resultCode, Intent data) {
        java.util.ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode != Activity.RESULT_OK || heard == null || heard.isEmpty()) {
            AppLog.w("apps: voice cancelled");
            return;
        }
        String best = null;
        for (String said : heard) {
            for (String q : voiceQueries(said)) {
                if (!filterApps(appsAll, q).isEmpty()) {
                    best = q;
                    break;
                }
            }
            if (best != null) break;
        }
        if (best == null) best = voiceQueries(heard.get(0)).get(0);
        AppLog.w("apps: heard " + heard + " -> " + best);
        if (appsInTab() && tab != APPS_TAB) {
            selectTab(APPS_TAB);
            topIndex = APPS_TAB;
        }
        sheetOpen = true;
        setQuery(best);
        appsZone = !others.isEmpty() ? A_GRID : !chips.isEmpty() ? A_SUGGEST : A_SEARCH;
        gridIndex = 0;
        chipIndex = 0;
        searchPart = 0;
        retargetGrid(false);
        retarget(false);
        gridDirty = true;
    }

    /** The phrase as heard (trailing punctuation off), then without a leading "open", "launch", "search for"… */
    private static List<String> voiceQueries(String said) {
        String text = said == null ? "" : said.trim().replaceAll("[.,!?\u3002]+$", "").trim();
        List<String> out = new ArrayList<>();
        out.add(text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String verb : new String[] {"search for ", "look for ", "find ", "open ", "launch ", "start ", "play ", "search "}) {
            if (lower.startsWith(verb) && text.length() > verb.length()) {
                out.add(text.substring(verb.length()).trim());
                break;
            }
        }
        return out;
    }

    /** The key filter (another process) saw the microphone on the Apps page. */
    private final BroadcastReceiver voiceKey = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            wake();
            voiceFromRemote();
        }
    };
    private boolean voiceKeyWatched;

    private void watchVoiceKey(boolean on) {
        if (on == voiceKeyWatched) return;
        voiceKeyWatched = on;
        try {
            // 4 = RECEIVER_NOT_EXPORTED (Android 13+; ignored before): only our own key filter sends it.
            if (on) getContext().registerReceiver(voiceKey, new IntentFilter(AppsVoice.ACTION), 4);
            else getContext().unregisterReceiver(voiceKey);
        } catch (RuntimeException ignored) {
        }
    }

    // MARK: - Apps section: drawing

    private void drawSheet(Canvas c) {
        appCards.frame();
        appsGeometry();
        boolean inTab = appsInTab();
        if (!inTab) c.drawColor(0xA8000000);   // full screen: over a dimmed home screen; in its tab: on the background
        float shift = scroll.value * k;
        float left = gridLeft(), right = CONTENT_R * k;
        c.save();
        c.clipRect(0, aClipTop * k, w, aClipBottom * k);
        if (!inTab) c.drawText(Ui.tr("Apps"), left, 150 * k - shift, sheetTitle);   // in the tab, the tab names it
        float sw = SEARCH_W * k, scx = w / 2f;
        drawSearchBar(c, scx - sw / 2, scx + sw / 2, shift);
        drawChips(c, left, right, shift);
        float gridTop = aGridTop * k - shift;
        int n = others.size();
        if (appsAll.isEmpty()) {
            drawAppsLoading(c, left, gridTop);   // the first list read: the grid's shape, quietly
            c.restore();
            return;
        }
        if (n == 0) {
            drawAppsEmpty(c, w / 2f, gridTop);
            c.restore();
            return;
        }
        int focused = sheetOpen && appsZone == A_GRID ? gridIndex : -1;
        for (int i = 0; i < n; i++) {
            if (i == focused) continue;     // drawn last, over its neighbours
            drawGridApp(c, i, left, gridTop);
        }
        if (focused >= 0 && focused < n) drawGridApp(c, focused, left, gridTop);
        c.restore();
    }

    private void drawGridApp(Canvas c, int i, float left, float gridTop) {
        int r = i / aCols, col = i % aCols;
        float y = gridTop + r * aRow * k;
        if (y > aClipBottom * k || y + aRow * k < aClipTop * k) return;
        float x = left + col * (aW + aGap) * k;
        gridCell.set(x, y, x + aW * k, y + aH * k);
        float f = gridFocus[i].value;
        drawAppIcon(c, others.get(i), gridCell, f, true, (aW + aGap - 12) * k);
        if (sheetOpen && appsZone == A_GRID && i == gridIndex) focusedCell.set(cell);
    }

    /**
     * An app's square icon (the dock's art: the bundled official icon, else the app's own; the dark version at night),
     * crisp at rest and focused. Focused (the dock's language): it grows 14% and rises, its shadow deepens, and a
     * white rim follows its corners one gap out (concentric). A faint neutral outline keeps dark icons off the
     * background. No icon: the name's first letter on a dark tile. `name`: the name under it (dim; bright focused).
     */
    private LinearGradient appSheen;
    private final Matrix appSheenM = new Matrix();

    private void drawAppIcon(Canvas c, HomeApps.Item it, RectF base, float f, boolean name, float nameRoom) {
        if (Math.abs(f) < 0.002f) {   // resting: from the shared cache (shadow, icon, light, outline and name in one bitmap)
            Bitmap art = dark() > 0.5f && it.iconDark != null ? it.iconDark : it.icon;
            long sig = 17;
            sig = 31 * sig + System.identityHashCode(art);
            sig = 31 * sig + (art != null && crispIcon(art, Math.round(base.width()), base.width() * APP_RADIUS) != null ? 1 : 0);
            sig = 31 * sig + (it.label == null ? 0 : it.label.hashCode()) + (name ? 1 : 0);
            sig = 31 * sig + Float.floatToIntBits(nameRoom) + 7L * Float.floatToIntBits(base.width()) + Float.floatToIntBits(k);
            sig = 31 * sig + (dark() > 0.5f ? 1 : 0);
            long key = (it.key == null ? (it.pkg == null ? 0 : it.pkg.hashCode()) : it.key.hashCode()) * 4L + (name ? 1 : 0);
            if (appCards.draw(c, key, sig, base, 72, pc -> paintAppIcon(pc, it, base, 0, name, nameRoom))) return;
        }
        paintAppIcon(c, it, base, f, name, nameRoom);
    }

    private void paintAppIcon(Canvas c, HomeApps.Item it, RectF base, float f, boolean name, float nameRoom) {
        float fc = Ui.clamp(f), s = 1 + 0.14f * f, bw = base.width();
        float cx = base.centerX(), cy = base.centerY() - 8 * k * f;
        cell.set(cx - bw * s / 2, cy - bw * s / 2, cx + bw * s / 2, cy + bw * s / 2);
        float r = cell.width() * APP_RADIUS;
        // The shadow at the resting size, enlarged by the canvas: the same shape every frame, so the system's blur of
        // it stays cached (a shadow whose size changes is blurred again on the CPU each frame of the zoom).
        float r0 = bw * APP_RADIUS;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bw / 2 + (6 + 14 * f) * k / s, bw / 2, bw / 2 + (6 + 14 * f) * k / s);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.26f + 0.3f * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Bitmap art = dark() > 0.5f && it.iconDark != null ? it.iconDark : it.icon;
        if (art != null) {
            int size = Math.round(bw * (s < 1.02f ? 1 : 1.14f));   // the focused size made once: sharp when grown
            Bitmap ready = crispIcon(art, size, size * APP_RADIUS);
            // Not ready yet (made in the background): the plain icon, scaled (cheap; a rounded clip each frame isn't).
            c.drawBitmap(ready != null ? ready : art, null, cell, bitmapPaint);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(cell, r, r, fill);
            if (it.symbol != null) {
                Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), cell.width() * 0.42f, 0xF2FFFFFF);
            } else {
                String letter = it.label == null || it.label.isEmpty() ? "?" : it.label.substring(0, 1).toUpperCase(Ui.locale());
                appLetter.setTextSize(cell.width() * 0.44f);
                c.drawText(letter, cell.centerX() - appLetter.measureText(letter) / 2, cell.centerY() + Ui.halfCap(appLetter), appLetter);
            }
        }
        if (art != null) {   // 3D (Concept 3): light from the top, a little shade at the bottom, one cached shader
            if (appSheen == null) appSheen = new LinearGradient(0, 0, 0, 1, new int[] {0x38FFFFFF, 0x00FFFFFF, 0x00000000, 0x33000000},
                    new float[] {0, 0.45f, 0.6f, 1}, Shader.TileMode.CLAMP);
            appSheenM.setScale(1, cell.height());
            appSheenM.postTranslate(0, cell.top);
            appSheen.setLocalMatrix(appSheenM);
            fill.setShader(appSheen);
            c.drawRoundRect(cell, r, r, fill);
            fill.setShader(null);
        }
        border.setColor(0x1AFFFFFF);    // the faint outline (make-interfaces-feel-better: neutral, never tinted)
        border.setStrokeWidth(1 * k);
        c.drawRoundRect(cell, r, r, border);
        if (fc > 0.01f) {
            float g = 6 * k;            // the rim one gap out, its radius the icon's plus the gap (concentric)
            glassTmp.set(cell.left - g, cell.top - g, cell.right + g, cell.bottom + g);
            border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * fc));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(glassTmp, r + g, r + g, border);
        }
        border.setStrokeWidth(2 * k);
        if (!name && fc < 0.01f) return;
        boolean on = fc > 0.5f;
        Paint p = on ? appNameOn : appName;
        String label = appLabel(it, on, nameRoom);
        float ty = cell.bottom + (fc > 0.01f ? 14 * k + g6(fc) : 14 * k) + Ui.halfCap(p) * 2;
        if (!name) p.setAlpha(Math.round(255 * fc));
        c.drawText(label, cx - appLabelWidth(label, p, on), ty, p);
        p.setAlpha(255);
    }

    // Names fitted once per app and style (ellipsizing and measuring on every frame of a scroll cost each frame dearly).
    private final java.util.HashMap<String, String> appLabels = new java.util.HashMap<>();
    private final java.util.HashMap<String, Float> appLabelHalf = new java.util.HashMap<>();

    private String appLabel(HomeApps.Item it, boolean on, float room) {
        String name = it.label == null ? "" : it.label;
        String key = (on ? "1" : "0") + Math.round(room) + "|" + name;
        String fitted = appLabels.get(key);
        if (fitted == null) {
            if (appLabels.size() > 1000) {
                appLabels.clear();
                appLabelHalf.clear();
            }
            fitPaint.set(on ? appNameOn : appName);
            fitted = TextUtils.ellipsize(name, fitPaint, room, TextUtils.TruncateAt.END).toString();
            appLabels.put(key, fitted);
        }
        return fitted;
    }

    private float appLabelWidth(String label, Paint p, boolean on) {
        String key = (on ? "1" : "0") + label;
        Float half = appLabelHalf.get(key);
        if (half == null) {
            half = p.measureText(label) / 2;
            appLabelHalf.put(key, half);
        }
        return half;
    }

    /** Every app's icon made crisp in the background as soon as the list arrives, not when it first scrolls into view. */
    private void warmAppIcons() {
        if (k <= 0) return;
        int size = Math.round(156 * k);
        boolean night = dark() > 0.5f;
        for (HomeApps.Item it : appsAll) {
            Bitmap art = night && it.iconDark != null ? it.iconDark : it.icon;
            if (art != null) crispIcon(art, size, size * APP_RADIUS);
        }
    }

    private float g6(float fc) {
        return 6 * k * fc;
    }

    /** Before the first list read: the grid's shape, as faint rounded squares (no spinner, no shifting layout). */
    /**
     * Apps before its first list: the grid's own shapes (icon squares at the icons' size and corners, a name bar under
     * each) for every row that shows, pulsing softly together. The pulse runs on the clock (not from when Apps was
     * opened), so focus moves and tab switches never restart it; it stops when the list is in.
     */
    private void drawAppsLoading(Canvas c, float left, float gridTop) {
        float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
        float pulse = 0.5f - 0.5f * (float) Math.cos(phase * 2 * Math.PI);
        fill.setShader(null);
        int rows = Math.max(2, (int) Math.ceil((aClipBottom * k - gridTop) / (aRow * k)));
        float r = aW * k * APP_RADIUS;
        for (int i = 0; i < aCols * rows; i++) {
            float x = left + (i % aCols) * (aW + aGap) * k, y = gridTop + (i / aCols) * aRow * k;
            if (y > aClipBottom * k) break;
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.06f + 0.05f * pulse));
            c.drawRoundRect(x, y, x + aW * k, y + aH * k, r, r, fill);
            float bw = aW * k * 0.6f, bx = x + (aW * k - bw) / 2, by = y + aH * k + 22 * k;
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.04f + 0.04f * pulse));
            c.drawRoundRect(bx, by, bx + bw, by + 14 * k, 7 * k, 7 * k, fill);
        }
    }

    /** The search bar: the app's liquid glass (as the weather pill), focused like the Control Center's tiles. */
    private final RectF searchRest = new RectF();

    /**
     * Apps' search bar. Resting (not focused, not typing): from the shared cache, as the cards. Its liquid glass was
     * painted again on every focus move in the grid below it: 22 ms frames there, 12 without it.
     */
    private void drawSearchBar(Canvas c, float left, float right, float shift) {
        float f = Ui.clamp(searchFocus.value), sc = 1 + 0.012f * f;
        float cx = (left + right) / 2, cy = (aSearchTop + aSearchH / 2) * k - shift, hh = aSearchH * k / 2;
        searchRest.set(left, cy - hh, right, cy + hh);
        if (searchRest.bottom * sc < aClipTop * k) return;
        // The glass shows the background behind it: cached per background, never during a crossfade (it would keep a frame).
        long sig = 17L * (dark() > 0.5f ? 2 : 1) + Float.floatToIntBits(right - left) * 31L + Float.floatToIntBits(k)
                + 131L * System.identityHashCode(bgTo);
        if (f > 0.01f) {   // the focused bar's deeper shadow, under the cached one
            tmp.set(cx - (right - left) / 2 * sc, cy - hh * sc + (8 + 8 * f) * k, cx + (right - left) / 2 * sc, cy + hh * sc + (8 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.18f * f));
            shadows.drawCached(c, tmp, hh * sc, SHADOW_BLUR * k, shadow);
        }
        c.save();
        c.scale(sc, sc, cx, cy);
        boolean cached = bgFadeAt == 0 && appCards.draw(c, 0x5EA4C4L, sig, searchRest, 48, pc -> paintSearchBase(pc, searchRest));
        c.restore();
        paintSearchBar(c, left, right, shift, !cached);
    }

    /** The search bar's shadow and liquid glass at rest (cached: the expensive part, the same in every state). */
    private void paintSearchBase(Canvas c, RectF r) {
        float rr = r.height() / 2;
        tmp.set(r);
        tmp.offset(0, 8 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.22f));
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, r, rr, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1);
    }

    /** The search bar; `withBase`: its shadow and glass too (else they came from the cache, drawn just before). */
    private void paintSearchBar(Canvas c, float left, float right, float shift, boolean withBase) {
        float f = Ui.clamp(searchFocus.value), s = 1 + 0.012f * f;
        float cy = (aSearchTop + aSearchH / 2) * k - shift, cx = (left + right) / 2;
        float hw = (right - left) / 2 * s, hh = aSearchH * k / 2 * s;
        searchRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        if (searchRect.bottom < aClipTop * k) return;   // scrolled away under the tabs: nothing to draw
        float r = hh;
        if (withBase) {
            tmp.set(searchRect);
            tmp.offset(0, (8 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.22f + 0.18f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
            scene.liquid(c, searchRect, r, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1);
        }
        glassBody(c, searchRect, r, 0.2f * f);
        focusOutline(c, searchRect, r, f);
        glassEdge(c, searchRect, r, 0.3f + 0.7f * f, (1.2f + 1.3f * f) * k);
        boolean voice = voiceAvailable;
        float x = searchRect.left + 34 * k;
        Symbols.draw(getContext(), c, voice ? "mic" : "search", x + 15 * k, cy, 30 * k, accent(0.9f));
        x += 50 * k;
        float base = cy + Ui.halfCap(searchText);
        // With voice: a keyboard button at the bar's end (→ from the bar), for typing instead.
        float kf = Ui.clamp(keyboardFocus.value), kd = 52 * k * (1 + 0.08f * kf), kbW = voice ? 52 * k + 24 * k : 0;
        if (voice) {
            float kcx = searchRect.right - 14 * k - 26 * k, kcy = cy;
            if (kf > 0.01f) {
                tmp.set(kcx - kd / 2, kcy - kd / 2 + 6 * k * kf, kcx + kd / 2, kcy + kd / 2 + 6 * k * kf);
                shadow.setColor(Ui.alpha(0xFF000000, 0.3f * kf));
                shadows.draw(c, tmp, kd / 2, SHADOW_BLUR * k, shadow);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f + 0.8f * kf));
            c.drawCircle(kcx, kcy, kd / 2, fill);
            Symbols.draw(getContext(), c, "keyboard", kcx, kcy, 26 * k * (1 + 0.08f * kf), blend(accent(0.95f), INK, kf));
        }
        String side = !appsQuery.trim().isEmpty() ? Ui.plural("plural|one:{n} app|other:{n} apps", others.size())
                : f > 0.5f && !typing ? (voice ? Ui.tr("OK to speak") : Ui.tr("OK to type"))
                : kf > 0.5f ? Ui.tr("OK to type") : "";
        float sideW = (side.isEmpty() ? 0 : searchHint.measureText(side) + 34 * k) + kbW;
        float room = searchRect.right - x - sideW - 30 * k;
        if (appsQuery.isEmpty()) {
            if (typing) x = drawCaret(c, x, cy);
            c.drawText(voice ? Ui.tr("Say or type an app name") : Ui.tr("Search apps"), x, base, searchHint);
        } else {
            // A long search keeps its end in view (where the typing is).
            String fit = appsQuery;
            while (fit.length() > 1 && searchText.measureText("\u2026" + fit) > room) fit = fit.substring(1);
            if (!fit.equals(appsQuery)) fit = "\u2026" + fit;
            c.drawText(fit, x, base, searchText);
            x += searchText.measureText(fit);
            if (typing) drawCaret(c, x, cy);
        }
        if (!side.isEmpty()) c.drawText(side, searchRect.right - sideW, base, searchHint);
    }

    private float drawCaret(Canvas c, float x, float cy) {
        fill.setShader(null);
        fill.setColor(0xFF0A84FF);
        c.drawRect(x + 2 * k, cy - 19 * k, x + 5 * k, cy + 19 * k, fill);
        return x + 12 * k;
    }

    /**
     * Under the search bar, centred. No search: the suggested apps as a row of icons (their source as a quiet label:
     * Most used, Recently opened, Recently installed); the focused one shows its name. A search: glass pills to search
     * the store for it and to clear it (the matches themselves are the grid).
     */
    private void drawChips(Canvas c, float left, float right, float shift) {
        if (chips.isEmpty()) return;
        if ((aChipsTop + aChipH + 60) * k - shift < aClipTop * k) {   // scrolled away: keep the rects off screen
            for (Chip ch : chips) ch.rect.set(ch.rect.left, -1000, ch.rect.right, -900);
            return;
        }
        boolean icons = appsQuery.trim().isEmpty();
        if (icons) {
            String label = suggestLabel;
            c.drawText(label, w / 2f - appsSection.measureText(label) / 2, aLabel * k - shift, appsSection);
            float size = aChipH * k, gap = 30 * k, total = chips.size() * size + (chips.size() - 1) * gap;
            float x = w / 2f - total / 2, top = aChipsTop * k - shift;
            for (Chip ch : chips) {
                gridCell.set(x, top, x + size, top + size);
                drawAppIcon(c, ch.app, gridCell, ch.focus.value, false, 260 * k);
                ch.rect.set(cell);
                x += size + gap;
            }
            return;
        }
        float hgt = CHIP_PILL * k, pad = 22 * k, gap = 14 * k, between = 16 * k;
        float[] widths = new float[chips.size()];
        String[] texts = new String[chips.size()];
        float total = 0;
        for (int i = 0; i < chips.size(); i++) {
            fitPaint.set(chipText);
            texts[i] = TextUtils.ellipsize(chips.get(i).label, fitPaint, 760 * k, TextUtils.TruncateAt.END).toString();
            widths[i] = pad + 28 * k + gap + chipText.measureText(texts[i]) + pad;
            total += widths[i] + (i > 0 ? between : 0);
        }
        float x = w / 2f - total / 2, top = aChipsTop * k - shift + (aChipH - CHIP_PILL) / 2 * k;
        for (int i = 0; i < chips.size(); i++) {
            Chip ch = chips.get(i);
            float wch = widths[i], f = Ui.clamp(ch.focus.value), s = 1 + 0.04f * f;
            float ccx = x + wch / 2, ccy = top + hgt / 2;
            ch.rect.set(ccx - wch * s / 2, ccy - hgt * s / 2, ccx + wch * s / 2, ccy + hgt * s / 2);
            float r = ch.rect.height() / 2;
            if (f > 0.01f) {
                tmp.set(ch.rect);
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.3f * f));
                shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
            }
            scene.liquid(c, ch.rect, r, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 10 * k, 6 * k,
                    tone(0.6f, 0.45f), 1);
            glassBody(c, ch.rect, r, 0.22f * f);
            focusOutline(c, ch.rect, r, f);
            glassEdge(c, ch.rect, r, 0.3f + 0.7f * f, (1.2f + 1.3f * f) * k);
            float cx0 = ch.rect.left + pad * s;
            Symbols.draw(getContext(), c, ch.action == CHIP_STORE ? "shop" : "close", cx0 + 14 * k * s, ccy, 26 * k * s, accent(0.95f));
            chipText.setTextSize(24 * k * s);
            c.drawText(texts[i], cx0 + (28 * k + gap) * s, ccy + Ui.halfCap(chipText), chipText);
            chipText.setTextSize(24 * k);
            x += wch + between;
        }
    }

    /** No app matches: say so, and where to look (the store's chip above), never a blank page. */
    private void drawAppsEmpty(Canvas c, float cx, float top) {
        if (appsQuery.trim().isEmpty()) return;
        String q = "\u201C" + appsQuery.trim() + "\u201D";
        c.drawText(Ui.trf("No installed app matches {q}", "q", q), cx, top + 90 * k, emptyTitle);
        String sub = appStore != null
                ? Ui.trf("Not installed? Search {store} from the suggestions above.", "store", appStore[2])
                : Ui.tr("No app store on this TV can search for it.");
        c.drawText(sub, cx, top + 140 * k, emptyText);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        boolean due = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > (Config.SHELF_MOST_USED ? 60_000 : APPS_MAX_AGE_MS);
        if (due && up < appsDeferredUntil) {
            // Just started with the saved dock: its rebuild (seconds of package queries and icon rendering) waits, so
            // the first moments are the user's. Opening Apps, or an app installed or removed, brings it forward.
            handler.removeCallbacks(deferredReload);
            handler.postDelayed(deferredReload, appsDeferredUntil - up);
            due = false;
        }
        final boolean apps = due;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true, HomeApps.DOCK_MAX) : null;
            if (s != null) {
                // The dock first, on its own: it's on screen (and may still be empty: no saved one yet). All Apps,
                // the suggestions and the rest below take longer and come after.
                handler.post(() -> {
                    setShelf(s);
                    Startup.mark("apps loaded (background)");
                });
                HomeApps.saveDock(c, s);   // the next start shows it at once
            }
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<String> sug = apps ? HomeApps.suggestions(c, SUGGEST_MAX) : null;
            final String[] store = apps ? HomeApps.appStore(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons(c) : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && (!sheetOpen || appsAll.isEmpty())) {   // nothing shown yet: in it goes, even with Apps open
                    setOthers(o);
                    setSuggestions(sug, store);
                    retargetGrid(false);
                }
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        appsDeferredUntil = 0;   // a real change: no waiting
        TitleLaunch.get(getContext()).appsChanged();   // an app installed or removed: what can open titles changes
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        lastKeyAt = SystemClock.uptimeMillis();
        if (booting && bootFadeAt == 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) bootFadeAt = lastKeyAt;   // shows the real screen now
            return true;
        }
        if (ccOpen) return ccKey(e);   // nothing reaches the screen under it
        if (gameSheet != null) return gameSheetKey(e);   // a game's details: nothing reaches the rows under it
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (AppsVoice.isMic(e.getKeyCode()) && appsShowing()) {   // the microphone on Apps: voice search of the apps
            if (!down) voiceFromRemote();
            return true;
        }
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            // Back in Apps: first the search goes (the full list again, focus on the search bar), then Apps closes.
            stopTyping();
            if (!appsQuery.isEmpty()) {
                setQuery("");
                appsZone = A_SEARCH;
                retargetGrid(false);
            } else if (appsInTab()) {
                leaveApps(Z_TOP);   // in its tab: back to the tabs (on Apps), like the other tabs
            } else {
                closeSheet();
            }
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (fullPage() && zone == Z_CARDS && tab == FOR_YOU_TAB && feedRow >= 0) {
            List<Discover.Row> rows = feedRows();   // For You: Back from its rows goes to its switch first
            if (feedRow < rows.size()) resetFeedRow(rows.get(feedRow));
            feedRow = -1;
            followFeed(false);
            retarget(false);
        } else if (fullPage() && zone == Z_CARDS) {
            zone = Z_TOP;           // For You: Back from its rows goes to the tabs (on For You)
            topIndex = tab;
            retarget(false);
        } else if (fullPage()) {
            selectTab(0);           // no dock to go back to on For You and Apps: Back on their tabs goes to Home
            zone = Z_TOP;
            topIndex = 0;
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (!sheetOpen && zone == Z_SHELF && !dockAwake) {
            dockAwake = true;
            if (dx != 0) {      // the first ← or → only wakes the dock: the first app pops out
                retarget(false);
                return;
            }
        }
        if (sheetOpen) {
            moveApps(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                    aimDockScroll(false);
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    // No input has a signal: Inputs stays hidden and Home doesn't scroll down to it.
                    if (drawerItems.isEmpty() || !inputsHaveSignal()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = dockNearest(statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        appsGeometry();
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / aCols, col = gridIndex % aCols;
        int rows = (n + aCols - 1) / aCols;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / aCols == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * aCols + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (!sheetOpen && zone == Z_SHELF && !dockAwake) {   // OK on the resting dock: wake it, open nothing yet
            dockAwake = true;
            retarget(false);
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (appsZone == A_SEARCH) {         // OK on the search bar: voice (the keyboard button: typing)
                click.open();
                if (searchPart == 1 || !voiceAvailable) startTyping(); else startVoice();
                return;
            }
            if (appsZone == A_SUGGEST) {
                if (chips.isEmpty()) return;
                Chip ch = chips.get(chipIndex);
                if (ch.action == CHIP_STORE) {
                    click.open();
                    openStore();
                    return;
                }
                if (ch.action == CHIP_CLEAR) {
                    click.open();
                    setQuery("");
                    appsZone = A_SEARCH;
                    retargetGrid(false);
                    return;
                }
                it = ch.app;
                r = ch.rect;
            } else {
                if (others.isEmpty()) return;
                it = others.get(gridIndex);
                r = focusedCell;
            }
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p != null) launchTitle(p.kind, p.id, p.app, heroButton, p);
            return;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < TABS) {
                selectTab(topIndex);
                if (topIndex == APPS_TAB) enterApps(A_SEARCH);   // OK on Apps: into it, on the search bar
                return;
            }
            it = lightButtons.get(topIndex - TABS);
            r = topRects[topIndex];
            if (HomeApps.TOP_SETTINGS.equals(it.key)) {   // Settings: the Control Center (All settings is inside it)
                click.open();
                openCC();
                return;
            }
        } else if (zone == Z_CARDS) {
            if (tab == FOR_YOU_TAB) {
                activateFeed();
                return;
            }
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p != null) launchTitle(p.kind, p.id, p.app, cardRects[cardIndex], p);
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            if (picksOn()) {        // the dock's All Apps: the Apps tab, focus on its first suggestion (or app)
                selectTab(APPS_TAB);
                topIndex = APPS_TAB;
                enterApps(-1);
            } else {
                openSheet();        // no tabs (no picks): Apps full screen, as before
            }
            return;
        }
        if (it.kind == HomeApps.INPUT && (!it.connected || it.inputId == null)) {
            // A console with no device detected on its HDMI port stays disabled: no switching to an empty input.
            showToast(Ui.trf("{app} isn't connected.", "app", it.label));
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || appsZone != A_GRID || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= HomeApps.DOCK_MAX) break;   // shown on the dock: the first 10 installed ones
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= HomeApps.DOCK_MAX) {
                    // Added past the 10th it wouldn't appear: say so instead of "Added".
                    handler.post(() -> showToast(Ui.trf("The dock shows {n} apps. Remove one to add another.", "n", HomeApps.DOCK_MAX)));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        if (HomeApps.autoShelf || !dockAwake) return false;   // a fixed or most-used list: its order isn't moved here
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        aimDockScroll(false);   // the lifted app carries the row along at the edges
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : TABS; i < TABS + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(bento() ? SLOTS : 3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (DRAWER_CARDS && drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (DRAWER_CARDS && drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= TABS) return label(lightButtons, i - TABS);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", TABS);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float x = gridLeft() + (i % aCols) * (aW + aGap) * k;
                float y = (aGridTop + (i / aCols) * aRow - scroll.target) * k;
                a11yRect.set(x, y, x + aW * k, y + aH * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= TABS + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < TABS ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(bento() ? SLOTS : 3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen || !DRAWER_CARDS) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= TABS + lightButtons.size() || (i < TABS && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < TABS) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        layoutTabs();
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[TABS + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        if (bento()) layoutBento();
        heroDirty = statusDirty = true;
    }

    /** Explore, Movies and Shows all show the bento grid (with a TMDB key); the three cards only without one. */
    private boolean bento() {
        return picksOn();   // every tab: Explore, Movies and Shows all use the 10-card grid
    }

    /** Which cards are drawn large (bigger text and icon): the 2×2 one, or any card at least two rows tall. */
    private final boolean[] bentoBig = new boolean[SLOTS];
    private int bentoLaidOut = -1;

    private void layoutBento() {
        layoutBento(cards.size());
    }

    /**
     * The grid for `count` cards. 10 (and while loading: the skeletons): the bento layout. Fewer: the same area in 1 to 3
     * rows, each row's cards side by side at equal widths, the first row holding the most. Every card is a real one:
     * empty slots were drawn as blank placeholder tiles before.
     */
    private void layoutBento(int count) {
        bentoLaidOut = count;
        float g = V1_GAP * k, left = MARGIN * k, top = V1_BENTO_TOP * k;
        float width = (CONTENT_R - MARGIN) * k;
        int cont = Math.min(contShown, Math.max(0, count));
        float bottom = (V1_CONT_TOP + V1_CONT_H) * k;   // no Continue row: the bento has the whole area
        if (cont > 0) {   // Continue Watching below: its heading column, then four cards (fewer keep that width)
            float x0 = (MARGIN + V1_CONT_COL + V1_CONT_COL_GAP) * k;
            float cw = (CONTENT_R * k - x0 - (ContinueLogic.MAX - 1) * g) / ContinueLogic.MAX;
            float ct = V1_CONT_TOP * k, cb = ct + V1_CONT_H * k;
            for (int i = 0; i < cont; i++) {
                float x = x0 + i * (cw + g);
                cardRects[i].set(x, ct, x + cw, cb);
                bentoBig[i] = false;
            }
            contHeadBase = ct;
            bottom = top + V1_BENTO_H * k;
        }
        float height = bottom - top;
        count -= cont;
        float colW = (width - (BENTO_COLS - 1) * g) / BENTO_COLS, rowH = (height - g) / 2;
        if (count <= 0 || count >= BENTO.length) {   // the whole bento (none yet: its skeletons' places)
            for (int j = 0; j < BENTO.length; j++) {
                int[] b = BENTO[j];
                float x = left + b[0] * (colW + g), y = top + b[1] * (rowH + g);
                cardRects[cont + j].set(x, y, x + b[2] * colW + (b[2] - 1) * g, y + b[3] * rowH + (b[3] - 1) * g);
                bentoBig[cont + j] = b[2] == 2 && b[3] == 2;
            }
            return;
        }
        int rows = Math.min(2, count);
        float h = (height - (rows - 1) * g) / rows;
        int at = cont;
        for (int r = 0; r < rows; r++) {
            int inRow = count / rows + (r < count % rows ? 1 : 0);
            float w = (width - (inRow - 1) * g) / inRow, y = top + r * (h + g);
            for (int j = 0; j < inRow; j++, at++) {
                float x = left + j * (w + g);
                cardRects[at].set(x, y, x + w, y + h);
                bentoBig[at] = h >= rowH * 1.5f && w >= colW * 1.5f;
            }
        }
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        // Explore: its own 10. Movies and Shows: 10 of their 15 (Crunchyroll's among them), turning each hour.
        // Home: Movies and Shows together (no Explore lookup); Movies and Shows: 10 of their 15, turning each hour.
        if (tab == APPS_TAB || tab == FOR_YOU_TAB) {   // no picks' cards: these tabs draw themselves
            // Focus stays where it is: For You's rows use Z_CARDS too, and this runs whenever the Home picks refresh in
            // the background (moving focus to the tabs here made the next ← or → switch tabs).
            cards = new ArrayList<>();
            heroDirty = true;
            // Each tab its own background (nothing of Home's left behind): For You the focused title's photo (as Home
            // does with its cards), Apps the plain background (the glass back to neutral when the crossfade ends).
            if (tab == FOR_YOU_TAB) feedBackdrop(); else showBackdrop(null);
            return;
        }
        List<Recs.Pick> list = !bento() ? recs.now(TAB_KIND[tab])
                : tab == 0 ? recs.homeShown(BENTO.length + ContinueLogic.MAX) : recs.tabShown(TAB_KIND[tab]);
        // Home: the Continue row first (from the history in memory), then 7 picks not already in it.
        List<String> contLines = new ArrayList<>();
        List<Recs.Pick> cont = bento() && tab == 0 ? recs.continueShown(contLines) : new ArrayList<>();
        if (bento() && tab == 0) {
            java.util.Set<String> inRow = new java.util.HashSet<>();
            for (Recs.Pick p : cont) inRow.add(Recs.key(p));
            List<Recs.Pick> all = new ArrayList<>(cont);
            for (Recs.Pick p : list) if (all.size() < cont.size() + BENTO.length && !inRow.contains(Recs.key(p))) all.add(p);
            list = all;
        }
        int contBefore = contShown;
        contShown = cont.size();
        // The cards' lines again each time: details (runtime, season) may have arrived for the same titles.
        String[] lines = new String[list.size()], chips = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Recs.Pick p = list.get(i);
            lines[i] = i < contShown ? contLines.get(i)
                    : Recs.cardLine(p, HomeApps.serviceArt(getContext(), p.app) == null);   // logo shown: no name
            chips[i] = i < contShown ? "" : p.followed ? Ui.tr("New season") : p.tier == Recs.TIER_WEEK ? Ui.tr("New this week") : "";
        }
        bentoLines = lines;
        bentoChips = chips;
        bentoLinesGeneration = Ui.generation;
        requestTitleLinks();
        if (!list.isEmpty()) Startup.mark("recommendations shown");
        if (!samePicks(list, cards) || contBefore != contShown) {
            cards = list;
            if (bento()) layoutBento();   // a different number of cards: their own layout
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        // This tab's images (all three tabs' 30 don't fit in memory together; the others load when you switch).
        if (tab == 0) recs.setPinned(list);   // Home's cards stay in memory while you're in For You or an app
        for (Recs.Pick p : list) recs.backdrop(p);
        int shown = bento() && zone == Z_CARDS ? Math.min(cardIndex, cards.size() - 1) : 0;
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(Math.max(0, shown)));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (gameSheet != null) closeGameSheet();   // a game's details never stay open behind another tab
        if (index == tab) return;
        boolean wasApps = tab == APPS_TAB;
        tab = index;
        if (index == APPS_TAB && appsDeferredUntil != 0) {   // Apps before the deferred load: its list now
            appsDeferredUntil = 0;
            reload();
        }
        if (index == FOR_YOU_TAB) {
            discoverReady();   // For You opened before the other tabs were warmed: its data now
            Discover.get(getContext()).refreshIfStale();
            requestTitleLinks();
            feedScrollY.snap(0);
            feedRow = -1;
            resetFeedPlaces();
        }
        if (index == APPS_TAB) {
            // Apps shown under the tabs: the full list, no search left from before, from its top.
            stopTyping();
            appsQuery = "";
            if (searchInput != null) searchInput.setText("");
            appsZone = A_GRID;
            applyQuery(false);
            gridIndex = 0;
            scroll.snap(0);
            sheet.target = 1;
            refreshSuggestions();
            updateVoiceMark();
            warmAppIcons();
        } else if (wasApps) {
            stopTyping();
            sheetOpen = false;
            sheet.target = 0;
            retargetGrid(false);
            updateVoiceMark();
        }
        layoutLight();      // Explore's grid and the three cards are laid out differently
        if (bento()) recs.exploreOpened();
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        // With focus on its inputs and shortcuts, that veil gives way to Explore's own treatment (the dark veil below,
        // exploreDim), crossfading as exploreDim rises.
        android.graphics.ColorFilter v = veil((picksOn() ? 0 : tone(0.32f, 0.42f)) * Ui.clamp(drawer.value) * (1 - Ui.clamp(exploreDim.value)),
                blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        // Explore's dim on a photo at rest: folded into the photo's own drawing (a full-screen dark rect over it was one
        // more full-screen pass for the GPU on every frame of a focus move in the grid).
        float dim = Ui.clamp(exploreDim.value);
        boolean foldDim = dim > 0.002f && v == null && bgTo != null && !fading && tab != FOR_YOU_TAB;
        imagePaint.setColorFilter(foldDim ? veil(0.32f * dim, 0xFF000000) : v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        if (tab == FOR_YOU_TAB) { long ht = System.nanoTime(); drawHeroArt(c); if (gamesOn) gameFrame(System.nanoTime() - ht, true); }
        // In Explore's grid: a soft dark veil over the frosted background, so the artwork stands out.
        if (dim > 0.002f && !foldDim) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.32f * dim));
            c.drawRect(screen, fill);
        }
    }

    // MARK: - Tabs V1 (Morph Dock): graphite glass tiles with ivory line icons; the selected tab stretches into an ivory
    // capsule with its name (its icon on a dark tile); the remote on the tabs lifts it 6 px with the ivory ring.

    private final Spring[] tabSel = new Spring[TABS];
    private final Spring[] tabOpen = new Spring[TABS];   // the capsule with its name: only while the remote is on the tabs
    private final float[] tabCap = new float[TABS];
    private Paint tabName;
    private LinearGradient tabGraphite, tabIvory, tabInner;
    private final Matrix tabM = new Matrix();

    private Spring tabSel(int i) {
        if (tabSel[i] == null) tabSel[i] = new Spring(0.26f, 0.9f, i == tab ? 1 : 0);
        return tabSel[i];
    }

    private Spring tabOpen(int i) {
        if (tabOpen[i] == null) tabOpen[i] = new Spring(0.26f, 0.9f, i == tab && zone == Z_TOP ? 1 : 0);
        return tabOpen[i];
    }

    private boolean stepTabs(float dt) {
        boolean moving = false;
        boolean onTabs = !sheetOpen && zone == Z_TOP && topIndex < TABS;
        for (int i = 0; i < TABS; i++) {
            Spring sp = tabSel(i);
            sp.target = i == tab ? 1 : 0;
            moving |= sp.step(dt);
            Spring op = tabOpen(i);
            op.target = i == tab && onTabs ? 1 : 0;   // in the page: the active tab as its icon alone
            moving |= op.step(dt);
        }
        if (moving) heroDirty = true;
        return moving;
    }

    /** The tabs' places now: each as wide as its selection makes it (72 → its capsule), 16 apart from the margin. */
    private void layoutTabs() {
        if (tabName == null) {
            tabName = Ui.text(Ui.semibold, 28 * k, 0xFF1A1612);
            for (int i = 0; i < TABS; i++) tabCap[i] = TabsLogic.capsuleWidth(tabName.measureText(tabs()[i]) / k);
        }
        float x = MARGIN * k, top = L_TABBAR_TOP * k, hh = TabsLogic.TILE * k;
        for (int i = 0; i < TABS; i++) {
            float wv = TabsLogic.tabWidth(tabOpen(i).value, tabCap[i]) * k;
            topRects[i].set(x, top, x + wv, top + hh);
            x += wv + 16 * k;
        }
    }

    private void shaderIn(LinearGradient g, RectF r) {
        tabM.setScale(r.width(), r.height());
        tabM.postTranslate(r.left, r.top);
        g.setLocalMatrix(tabM);
    }

    private void drawTabsV1(Canvas c) {
        layoutTabs();
        if (tabGraphite == null) {
            tabGraphite = new LinearGradient(0, 0, 0.36f, 1, 0xFF3A3836, 0xFF1A1918, Shader.TileMode.CLAMP);
            tabIvory = new LinearGradient(0, 0, 0, 1, new int[] {0xFFFFF8EC, 0xFFEADFCB, 0xFFD2C2A6}, new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP);
            tabInner = new LinearGradient(0, 0, 0.36f, 1, 0xFF3A3330, 0xFF1A1614, Shader.TileMode.CLAMP);
        }
        float focus = Ui.clamp(topFocus[tab].value), rr = 24 * k;
        for (int i = 0; i < TABS; i++) {
            float sel = Ui.clamp(tabSel(i).value), open = Ui.clamp(tabOpen(i).value), lift = 0;   // focus: the ring only
            cell.set(topRects[i]);
            cell.offset(0, -lift);
            // Its shadow: dark under a tile, warm under the capsule. One blur size for every frame of the stretch (a blur
            // that changed with it was a new mask built on every frame: 6-15 ms of the main thread per frame).
            tmp.set(cell);
            tmp.inset(10 * k, 10 * k);
            tmp.offset(0, (12 + 8 * sel) * k + lift);
            shadowDeep.setColor(blend(0xB3000000, 0x73F3E2BE, sel));
            shadows.drawCached(c, tmp, rr, 32 * k, shadowDeep);
            if (sel < 0.999f) {
                shaderIn(tabGraphite, cell);
                fill.setShader(tabGraphite);
                fill.setAlpha(Math.round(255 * (1 - sel)));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            if (sel > 0.001f) {
                shaderIn(tabIvory, cell);
                fill.setShader(tabIvory);
                fill.setAlpha(Math.round(255 * sel));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            fill.setShader(null);
            fill.setAlpha(255);
            border.setStrokeWidth(1 * k);   // the glass's lit edge
            border.setColor(Ui.alpha(0xFFFFFFFF, 0.22f + 0.5f * sel));
            glassTmp.set(cell.left + 0.5f * k, cell.top + 0.5f * k, cell.right - 0.5f * k, cell.bottom - 0.5f * k);
            c.drawRoundRect(glassTmp, rr, rr, border);
            border.setStrokeWidth(2 * k);
            float icx = cell.left + 36 * k, icy = cell.centerY();   // the tile's middle = the capsule's icon tile's middle
            if (sel > 0.001f) {   // the capsule's dark icon tile
                glassTmp.set(icx - 28 * k, icy - 28 * k, icx + 28 * k, icy + 28 * k);
                shaderIn(tabInner, glassTmp);
                fill.setShader(tabInner);
                fill.setAlpha(Math.round(255 * sel));
                c.drawRoundRect(glassTmp, 18 * k, 18 * k, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            TabIcons.tab(c, i == APPS_TAB ? 2 : i == FOR_YOU_TAB ? 1 : 0, icx, icy, (32 - 2 * sel) * k, blend(0xD9F3EDE2, 0xFFF3EDE2, sel));
            if (open > 0.01f) {   // its name, appearing as the tile stretches (never past the capsule's edge)
                c.save();
                c.clipRect(cell);
                tabName.setAlpha(Math.round(255 * open));
                c.drawText(tabs()[i], cell.left + 80 * k, icy + Ui.halfCap(tabName), tabName);
                tabName.setAlpha(255);
                c.restore();
            }
            if (i == tab && focus > 0.01f) {   // the remote on the tabs: the ivory ring, 4 px out
                float o = 5.5f * k;
                glassTmp.set(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o);
                border.setColor(Ui.alpha(ACCENT, focus));
                border.setStrokeWidth(3 * k);
                c.drawRoundRect(glassTmp, rr + o, rr + o, border);
                border.setStrokeWidth(2 * k);
            }
        }
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        heroCards.frame();
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        drawGreeting(c);
        drawTabsV1(c);
        int top = -1, count = bento() ? Math.min(cards.size(), SLOTS) : 3;
        for (int i = 0; i < count; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        if (tab == APPS_TAB) {
            // Apps: the section draws itself (the sheet layer), under the tabs.
        } else if (tab == FOR_YOU_TAB) {
            { long ft = System.nanoTime(); drawFeed(c); if (gamesOn) gameFrame(System.nanoTime() - ft, false); }
            if (gamesOn) drawGameSheet(c);
        } else if (bento() && cards.isEmpty()) {
            // Nothing cached yet: skeletons in the grid's own shapes while the first load runs; after it, why it's empty.
            if (recs.listLoading()) drawBentoSkeleton(c);
            else drawBentoEmpty(c);
        } else if (bento()) {
            if (bentoLaidOut != count) layoutBento(count);
            drawBentoNote(c, count);
            if (contShown > 0) drawContinueLabel(c);
            if (count <= contShown) {   // only the Continue row so far: the picks' skeletons, or why there are none
                if (recs.listLoading()) for (int j = contShown; j < contShown + BENTO.length; j++) drawSkeletonSlot(c, cardRects[j], bentoBig[j], 1);
                else drawBentoEmpty(c);
            }
            for (int i = 0; i < count; i++) if (i != top) drawBentoCard(c, i);
            if (top >= 0) drawBentoCard(c, top);
        } else {
            for (int i = 0; i < count; i++) if (i != top) drawLightCard(c, i);
            if (top >= 0) drawLightCard(c, top);
        }
        drawWeatherClock(c);   // where the coin prices were
    }

    // MARK: - Weather and time pill (the tab row's right end)

    private Paint wcSoft;
    private String wcTemp = "", wcPlace = "", wcTime = "", wcDate = "", wcSymbol = null;
    private int wcMinute = -1;
    private long wcWeatherAt = -1;
    private int wcPrefs = -1;
    private String wcLanguage = "";

    /**
     * Its text, made again only when the minute, the weather or the settings change (not per frame). The weather is the
     * home screen's own (Open-Meteo, for the city in Home Settings, refreshed every 30 min): shown only while it's
     * fresh; older than 3 h (no internet), the pill says it's unavailable rather than showing an old temperature as
     * now; still loading, a dash.
     */
    private void updateWeatherClock() {
        long now = System.currentTimeMillis();
        int minute = (int) (now / 60_000);
        if (minute == wcMinute && data.weatherAt == wcWeatherAt && Prefs.version == wcPrefs && Ui.language.equals(wcLanguage)) return;
        wcMinute = minute;
        wcWeatherAt = data.weatherAt;
        wcPrefs = Prefs.version;
        wcLanguage = Ui.language;
        wcTime = Ui.clock(now);
        wcDate = Ui.datePattern("format|EEE, MMM d").format(now);
        HomeData.Weather wx = data.weather;
        long age = data.weatherAt == 0 ? Long.MAX_VALUE : now - data.weatherAt;
        if (!Prefs.weather()) {
            wcSymbol = null;
            wcTemp = "";
            wcPlace = "";
        } else if (Double.isNaN(wx.temp) || data.weatherAt == 0) {
            wcSymbol = null;
            wcTemp = "—";
            wcPlace = "";
        } else if (age > 3 * 3600_000L || age < 0) {
            wcSymbol = null;
            wcTemp = "";
            wcPlace = Ui.tr("Weather unavailable");
        } else {
            wcSymbol = Symbols.weather(wx.code, wx.isDay);
            wcTemp = HomeData.degrees(wx.temp);
            String stale = HomeData.staleness(data.weatherAt, 30 * 60_000L);   // 1 to 3 h old: says how old
            // Just the icon and the temperature (no condition or city); an hour-old reading still says how old it is.
            wcPlace = stale;
        }
    }

    /**
     * A small glass pill, right-aligned on the tab row where the coin prices were: weather icon and temperature
     * (no condition or city), then the time and date. The same liquid glass as the round buttons above it; always visible.
     */
    /**
     * Time, date and weather (Concept 3, minimal): the time large in the display face, then "Mon, Oct 26 · 28°" softer,
     * right-aligned on the tabs' line. No pill, glass or icon: text only, nothing animates.
     */
    private Paint wcTimePaint;
    private String wcLine = "", wcLineDate, wcLineTemp;

    private void drawWeatherClock(Canvas c) {
        if (wcTimePaint == null) {
            wcTimePaint = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            wcSoft = Ui.text(Ui.medium, 21 * k, 0xFFFFFFFF);
            wcTimePaint.setColorFilter(IVORY_TEXT);
            wcSoft.setColorFilter(IVORY_TEXT);
        }
        updateWeatherClock();
        if (!wcDate.equals(wcLineDate) || !wcTemp.equals(wcLineTemp)) {   // made when they change, not every frame
            wcLineDate = wcDate;
            wcLineTemp = wcTemp;
            wcLine = wcTemp.isEmpty() ? wcDate : wcDate + "  ·  " + wcTemp;
        }
        float cy = (L_TABBAR_TOP + L_TABBAR_H / 2) * k, right = CONTENT_R * k, base = cy + Ui.halfCap(wcTimePaint);
        wcSoft.setColor(tone(L_DIM, 0xB3FFFFFF));
        float wl = wcSoft.measureText(wcLine);
        c.drawText(wcLine, right - wl, base, wcSoft);
        wcTimePaint.setColor(tone(L_INK, 0xF2FFFFFF));
        c.drawText(wcTime, right - wl - 16 * k - wcTimePaint.measureText(wcTime), base, wcTimePaint);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(-bw / 2, -bh / 2, bw / 2, bh / 2, new int[] {0xF2FFFFFF, 0x47FFFFFF, 0x85FFFFFF},
                        new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    // MARK: - Explore's bento grid

    /** The card the arrow points to: the nearest one past this card's edge in that direction, or -1. */
    private int bentoMove(int from, int dx, int dy) {
        RectF a = cardRects[from];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(cards.size(), SLOTS); i++) {
            if (i == from) continue;
            RectF b = cardRects[i];
            float along, across;
            if (dx > 0) {
                if (b.left < a.right - 1) continue;
                along = b.left - a.right;
                across = gap(a.top, a.bottom, b.top, b.bottom);
            } else if (dx < 0) {
                if (b.right > a.left + 1) continue;
                along = a.left - b.right;
                across = gap(a.top, a.bottom, b.top, b.bottom);
            } else if (dy > 0) {
                if (b.top < a.bottom - 1) continue;
                along = b.top - a.bottom;
                across = gap(a.left, a.right, b.left, b.right);
            } else {
                if (b.bottom > a.top + 1) continue;
                along = a.top - b.bottom;
                across = gap(a.left, a.right, b.left, b.right);
            }
            float centers = dx != 0 ? Math.abs(a.centerY() - b.centerY()) : Math.abs(a.centerX() - b.centerX());
            float score = along + 3 * across + 0.1f * centers;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** How far apart two ranges are (0 when they overlap). */
    private static float gap(float a0, float a1, float b0, float b1) {
        return Math.max(0, Math.max(b0 - a1, a0 - b1));
    }

    /**
     * Coming into Home's cards from the tabs or from the dock: the first card of the section you arrive in (the bento's
     * large card from above; Continue Watching's first from below), as every row and section starts at its first item.
     */
    private int sectionFirst(boolean fromTop) {
        return tab != 0 ? 0 : HomeNav.sectionFirst(Math.min(cards.size(), SLOTS), contShown, fromTop);
    }

    /**
     * The background follows focus once it rests on a card: a crossfade on every key press redrew the background and
     * every glass surface each frame (64-85 ms frames on the TV while moving through the grid; ~31 ms without).
     */
    private static final long FOLLOW_MS = 400;
    /**
     * The crossfade running is a card's (followCard): only the background is redrawn during it, not the glass and the
     * cards over it on every frame (23 ms frames for its 0.48 s); they're redrawn once when it ends.
     */
    private boolean followFade;
    private final Runnable followRested = () -> {
        // Focus left the cards before it rested (down to the dock and Inputs): no crossfade then (one started under the
        // page's scroll and doubled its frame time).
        if (zone != Z_CARDS || drawerOpen) return;
        followCard();
        wake();
    };

    private void followCardSoon() {
        handler.removeCallbacks(followRested);
        handler.postDelayed(followRested, FOLLOW_MS);
    }

    /** In the grid, the frosted background shows the focused card's artwork (crossfading, as between tabs). */
    private void followCard() {
        if (!bento() || cardIndex >= cards.size()) return;
        Backdrop b = recs.backdrop(cards.get(cardIndex));
        if (b != null) {
            showBackdrop(b);
            followFade = bgFadeAt != 0;
        }
    }

    private LinearGradient shimmer;
    private final android.graphics.Matrix shimmerAt = new android.graphics.Matrix();

    /**
     * Loading, with nothing cached: each card's exact shape (sizes, gaps, corners), with placeholders where its service
     * icon, title and line go, and a soft light sweeping across.
     */
    private void drawBentoSkeleton(Canvas c) {
        if (bentoLaidOut != 0) layoutBento(0);
        for (int i = 0; i < BENTO.length; i++) drawSkeletonSlot(c, cardRects[i], bentoBig[i], 1);
    }

    /** One card's skeleton (its shape, icon, title and line placeholders, the sweeping light), at `alpha`. */
    // MARK: - Control Center (the round Settings button)

    /*
     * A glass panel over the home screen, laid out like the reference: the title; Wi-Fi and Bluetooth; AirPlay and Turn
     * off; the volume slider; a "Home screen" group (refresh, Home Settings, clear history); All settings. The screen
     * under it isn't touched (tab, grid, focus stay as they are); while it's open every key is its own; Back or Home
     * closes it and the focus is on the Settings button again, where it was.
     */
    private static final int CC_WIFI = 0, CC_BT = 1, CC_AIRPLAY = 2, CC_OFF = 3, CC_VOLUME = 4, CC_REFRESH = 5,
            CC_HOME_SETTINGS = 6, CC_CLEAR = 7, CC_ALL = 8, CC_COUNT = 9;
    // Top 136: under the round buttons (44..112, ~116 when the focused one grows), never over them; ends at ~884,
    // above the dock's glass (910).
    private static final float CC_W = 640, CC_TOP = 136, CC_PAD = 32, CC_GAP = 14, CC_TILE_H = 92, CC_ROW_H = 76;
    private boolean ccOpen, ccOkDown, ccBackDown;
    private int ccIndex;
    private final Spring ccShow = new Spring(0.3f, 1f, 0);   // the panel's fade and scale (Explore's springs, faster)
    private final Spring[] ccFocus = new Spring[CC_COUNT];
    private final RectF[] ccRects = new RectF[CC_COUNT];
    private final RectF ccPanel = new RectF();
    private float ccSectionY;
    private int ccWifi = Radios.UNKNOWN, ccBt = Radios.UNKNOWN, ccVolume, ccVolumeMax = 1;
    private String ccAirplayPkg, ccAirplayName;
    private Intent ccAirplayIntent;          // the TV's own AirPlay screen (or receiver app), found once
    private boolean ccAirplayInput;   // the TV lists an AirPlay input (switched to like HDMI)
    private static volatile Object[] airplayFound;   // {Intent, name} or {null, null}: looked up once per process
    private Paint ccTitle, ccName, ccSub, ccSection, ccPct;

    private void openCC() {
        if (ccOpen) return;
        if (ccFocus[0] == null) for (int i = 0; i < CC_COUNT; i++) {
            ccFocus[i] = new Spring(0.24f, 0.9f, 0);
            ccRects[i] = new RectF();
        }
        ccOpen = true;
        ccIndex = CC_WIFI;
        for (int i = 0; i < CC_COUNT; i++) ccFocus[i].snap(i == ccIndex ? 1 : 0);
        ccOkDown = ccBackDown = false;
        readCCState();
        findAirplay();
        ccShow.target = 1;
        ccLayer.setVisibility(VISIBLE);
        ccLayer.invalidate();
        wake();
    }

    /** Closes it; `snap`: at once (an app is opening over it, or Home). The focus is still on the Settings button. */
    private void closeCC(boolean snap) {
        if (!ccOpen && ccShow.value <= 0.001f) return;
        ccOpen = false;
        ccShow.target = 0;
        if (snap) {
            ccShow.snap(0);
            ccLayer.setVisibility(GONE);
        }
        retarget(snap);   // the focus under it, as it is now (its cached layers redrawn: no glass left from before)
        statusDirty = true;
        tilesDirty = true;
        heroDirty = true;
        gridDirty = true;
        wake();
    }

    /** Wi-Fi, Bluetooth (Radios: the TV's real state) and the volume (the media stream, as the remote's keys set it). */
    private void readCCState() {
        ccAirplayInput = airplayInputId() != null;
        Context c = getContext();
        ccWifi = Radios.wifi(c);
        ccBt = Radios.bluetooth(c);
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            ccVolumeMax = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            ccVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        }
    }

    /**
     * AirPlay: TVs that have it built in (TCL's Google TVs with AirPlay 2, among others) ship it as a system app or a
     * screen inside their settings, often with no launcher icon. So every installed package is looked at once (in the
     * background, cached for the app's life): an app named for AirPlay that can be launched, else an exported screen
     * whose name says AirPlay (a settings one first). Everything found is logged: adb logcat -s ibtzyTV | grep airplay
     */
    private void findAirplay() {
        Object[] known = airplayFound;
        if (known != null) {
            ccAirplayIntent = (Intent) known[0];
            ccAirplayName = (String) known[1];
            ccAirplayPkg = ccAirplayIntent == null ? null : ccAirplayIntent.getPackage();
            return;
        }
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            Object[] found = lookUpAirplay(c);
            airplayFound = found;
            handler.post(() -> {
                ccAirplayIntent = (Intent) found[0];
                ccAirplayName = (String) found[1];
                ccAirplayPkg = ccAirplayIntent == null ? null : ccAirplayIntent.getPackage();
                ccLayer.invalidate();
            });
        });
    }

    private static boolean namesAirplay(String s) {
        String l = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
        return l.contains("airplay") || l.contains("air_play") || l.contains("air play") || l.contains("airscreen")
                || l.contains("airreceiver") || l.contains("homekit");
    }

    private static Object[] lookUpAirplay(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent best = null;
        String bestName = null;
        int bestRank = -1;
        List<String> seen = new ArrayList<>();
        List<android.content.pm.ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (RuntimeException e) {
            AppLog.w("airplay: can't list apps: " + e);
            return new Object[] {null, null};
        }
        for (android.content.pm.ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            boolean appIsAirplay = namesAirplay(pkg) || namesAirplay(label);
            // 1. The app itself, when it can be launched (its TV or phone launcher screen).
            if (appIsAirplay) {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                // MediaTek's launcher entry is the black playback screen too: not an AirPlay screen to open.
                if (launch != null && launch.getComponent() != null
                        && launch.getComponent().getClassName().endsWith(".TVActivity")) launch = null;
                seen.add(pkg + " \"" + label + "\"" + (launch != null ? " (launchable)" : ""));
                if (launch != null && bestRank < 3) {
                    best = launch;
                    bestName = label;
                    bestRank = 3;
                }
            }
            // 2. Its screens: an exported one named for AirPlay (in a TV settings app, or in the AirPlay app).
            android.content.pm.ActivityInfo[] acts;
            try {
                acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities;
            } catch (Exception e) {
                continue;
            }
            if (acts == null) continue;
            for (android.content.pm.ActivityInfo act : acts) {
                if (!act.exported || !act.enabled || (!appIsAirplay && !namesAirplay(act.name))) continue;
                // MediaTek's TVActivity is the screen AirPlay video plays on (black until a phone sends something):
                // never the one to open from a tile.
                if (act.name.endsWith(".TVActivity")) continue;
                String n = act.name.toLowerCase(java.util.Locale.ROOT);
                int rank = n.contains("setting") ? 2 : n.contains("main") || n.contains("home") || n.contains("launch") ? 1 : 0;
                seen.add(pkg + "/" + act.name);
                if (rank > bestRank) {
                    best = new Intent().setClassName(pkg, act.name);
                    bestName = label;
                    bestRank = rank;
                }
            }
        }
        AppLog.w("airplay: " + (seen.isEmpty() ? "nothing named AirPlay on this TV" : String.join(", ", seen))
                + (best == null ? "" : " -> opens " + best.getComponent() + (best.getComponent() == null ? best.getPackage() : "")));
        if (best != null && best.getPackage() == null && best.getComponent() != null) best.setPackage(best.getComponent().getPackageName());
        return new Object[] {best, bestName};
    }

    /**
     * The TV's AirPlay input (TCL/MediaTek: "com.mediatek.AirplayAPK/.AirPlayTvInputService"), or null. On these TVs
     * AirPlay is a source like HDMI: the TV app (com.tcl.tv) shows its waiting screen once the source is switched to it.
     */
    private String airplayInputId() {
        TvInputManager tim = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        try {
            for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                String id = info.getId();
                if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("airplay")) return id;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Switches the TV to its AirPlay input, as the console cards do for HDMI. False when there's none or it's refused. */
    private boolean openAirplayInput() {
        String id = airplayInputId();
        if (id == null) return false;
        Intent in = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(in);
            AppLog.w("airplay: switched to input " + id);
            return true;
        } catch (RuntimeException e) {
            AppLog.w("airplay: input " + id + " refused: " + e);
            return false;
        }
    }

    private void layoutCC() {
        float pad = CC_PAD * k, gap = CC_GAP * k, w = CC_W * k;
        float right = CONTENT_R * k, left = right - w, x0 = left + pad, x1 = right - pad, half = (x1 - x0 - gap) / 2;
        float y = CC_TOP * k + pad + 62 * k;   // under the title
        ccRects[CC_WIFI].set(x0, y, x0 + half, y + CC_TILE_H * k);
        ccRects[CC_BT].set(x1 - half, y, x1, y + CC_TILE_H * k);
        y += CC_TILE_H * k + gap;
        ccRects[CC_AIRPLAY].set(x0, y, x0 + half, y + CC_TILE_H * k);
        ccRects[CC_OFF].set(x1 - half, y, x1, y + CC_TILE_H * k);
        y += CC_TILE_H * k + gap;
        ccRects[CC_VOLUME].set(x0, y, x1, y + 72 * k);
        y += 72 * k + 30 * k;
        ccSectionY = y + 16 * k;   // "HOME SCREEN"
        y += 34 * k;
        ccRects[CC_REFRESH].set(x0, y, x0 + half, y + CC_ROW_H * k);
        ccRects[CC_HOME_SETTINGS].set(x1 - half, y, x1, y + CC_ROW_H * k);
        y += CC_ROW_H * k + gap;
        ccRects[CC_CLEAR].set(x0, y, x1, y + CC_ROW_H * k);
        y += CC_ROW_H * k + gap;
        ccRects[CC_ALL].set(x0, y, x1, y + 76 * k);
        y += 76 * k + pad;
        ccPanel.set(left, CC_TOP * k, right, y);
    }

    /** One frame: the panel's fade and scale (a layer property, no redraw) and the focus (redrawn while it moves). */
    private boolean stepCC(float dt) {
        if (ccFocus[0] == null) return false;
        boolean moving = ccShow.step(dt);
        float v = Ui.clamp(ccShow.value);
        if (moving || ccOpen) {
            ccLayer.setAlpha(v);
            float sc = 0.97f + 0.03f * v;
            ccLayer.setPivotX(ccPanel.centerX());
            ccLayer.setPivotY(ccPanel.top);
            ccLayer.setScaleX(sc);
            ccLayer.setScaleY(sc);
        }
        if (!ccOpen && v <= 0.002f && ccLayer.getVisibility() != GONE) ccLayer.setVisibility(GONE);
        boolean focus = false;
        for (int i = 0; i < CC_COUNT; i++) {
            ccFocus[i].target = ccOpen && i == ccIndex ? 1 : 0;
            focus |= ccFocus[i].step(dt);
        }
        if (focus) ccLayer.invalidate();
        return moving || focus;
    }

    private boolean ccKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (!down) return true;
                int dx = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (ccIndex == CC_VOLUME) {   // on the slider: the volume itself
                    android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                dx > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
                        readCCState();
                        ccLayer.invalidate();
                    }
                    return true;
                }
                ccMove(dx, 0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) ccMove(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) ccMove(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // On release, and only if pressed here (no double activation, nothing from a press made elsewhere).
                if (down) {
                    if (e.getRepeatCount() == 0) ccOkDown = true;
                } else if (ccOkDown && !e.isCanceled()) {
                    ccOkDown = false;
                    click.open();
                    ccActivate(ccIndex);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) ccBackDown = true;
                } else if (ccBackDown && !e.isCanceled()) {
                    ccBackDown = false;
                    closeCC(false);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                handler.postDelayed(() -> {   // the TV sets it; the slider follows
                    if (ccOpen) {
                        readCCState();
                        ccLayer.invalidate();
                    }
                }, 120);
                return false;
            default:
                return true;   // nothing else reaches the screen under it
        }
    }

    /** The nearest tile past the focused one's edge in that direction (as the grid moves); stays at the edges. */
    private void ccMove(int dx, int dy) {
        RectF a = ccRects[ccIndex];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < CC_COUNT; i++) {
            if (i == ccIndex) continue;
            RectF b = ccRects[i];
            float along, across;
            if (dx > 0) {
                if (b.left < a.right - 1) continue;
                along = b.left - a.right;
                across = gap(a.top, a.bottom, b.top, b.bottom);
            } else if (dx < 0) {
                if (b.right > a.left + 1) continue;
                along = a.left - b.right;
                across = gap(a.top, a.bottom, b.top, b.bottom);
            } else if (dy > 0) {
                if (b.top < a.bottom - 1) continue;
                along = b.top - a.bottom;
                across = gap(a.left, a.right, b.left, b.right);
            } else {
                if (b.bottom > a.top + 1) continue;
                along = a.top - b.bottom;
                across = gap(a.left, a.right, b.left, b.right);
            }
            float centers = dx != 0 ? Math.abs(a.centerY() - b.centerY()) : Math.abs(a.centerX() - b.centerX());
            float score = along + 3 * across + 0.1f * centers;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return;
        ccIndex = best;
        click.move();
        wake();
    }

    private void ccActivate(int i) {
        switch (i) {
            case CC_WIFI:
                ccOpenItem(HomeApps.action(HomeApps.WIFI, HomeApps.TOP_WIFI, Ui.tr("Wi-Fi"), "wifi"));
                break;
            case CC_BT:
                ccOpenItem(HomeApps.action(HomeApps.BLUETOOTH, HomeApps.TOP_BLUETOOTH, Ui.tr("Bluetooth"), "bluetooth"));
                break;
            case CC_AIRPLAY:
                if (openAirplayInput()) {
                    closeCC(true);
                } else if (ccAirplayIntent != null) {
                    try {
                        activity.startActivity(new Intent(ccAirplayIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        closeCC(true);
                    } catch (RuntimeException ex) {
                        // MediaTek's AirPlay screen asks for its own permission (declared in the manifest: granted only
                        // if it's a normal one). Refused: the same screen through the TV's ADB, as the shell (the
                        // debugging ibtzy TV Launcher mode already uses), which may hold it.
                        AppLog.w("airplay: couldn't open " + ccAirplayIntent + ": " + ex);
                        final android.content.ComponentName cn = ccAirplayIntent.getComponent();
                        final Context app = getContext().getApplicationContext();
                        if (cn == null) {
                            showToast(Ui.tr("Couldn't open AirPlay."));
                        } else {
                            closeCC(false);
                            LOADER.execute(() -> {
                                String out;
                                try (Adb adb = Adb.connect(app, 10_000, null)) {
                                    out = adb.shell("am start -n " + cn.flattenToShortString());
                                } catch (Exception adbError) {
                                    out = "Error: " + adbError.getMessage();
                                }
                                AppLog.w("airplay via adb: " + out.trim());
                                if (out.contains("Error") || out.contains("Exception")) {
                                    handler.post(() -> showToast(Ui.tr("The TV doesn't let apps open its AirPlay screen.")));
                                }
                            });
                        }
                    }
                } else if (airplayFound == null) {
                    showToast(Ui.tr("Looking for AirPlay…"));   // the first lookup is still running
                } else {
                    // TCL's AirPlay page lives inside the TV's Settings (no screen of its own): Settings, where it's
                    // listed, with a word on where to find it.
                    ccOpenItem(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Settings"), "settings"));
                    showToast(Ui.tr("AirPlay is in the TV's Settings."));
                }
                break;
            case CC_OFF: {
                // The TV's own sleep key, sent through its ADB (the debugging that ibtzy TV Launcher mode already uses).
                closeCC(false);
                final Context c = getContext().getApplicationContext();
                LOADER.execute(() -> {
                    try (Adb adb = Adb.connect(c, 10_000, null)) {
                        adb.shell("input keyevent KEYCODE_SLEEP");
                    } catch (Exception ex) {
                        AppLog.w("turn off: " + ex.getMessage());
                        handler.post(() -> showToast(Ui.tr("Couldn't turn off the screen: it needs ibtzy TV Launcher mode (USB debugging on).")));
                    }
                });
                break;
            }
            case CC_VOLUME: {   // OK on the slider: mute or unmute
                android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    readCCState();
                    ccLayer.invalidate();
                }
                break;
            }
            case CC_REFRESH:
                recs.refreshNow();
                showToast(Ui.tr("Getting new picks…"));
                closeCC(false);
                break;
            case CC_HOME_SETTINGS:
                ccOpenItem(HomeApps.settings());
                break;
            case CC_CLEAR:
                recs.clearHistory();
                showToast(Ui.tr("Watch history cleared."));
                break;
            default:
                ccOpenItem(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Settings"), "settings"));
        }
    }

    /** Opens a settings page or an app from a tile: the panel goes at once (the page opens over Home). */
    private void ccOpenItem(HomeApps.Item it) {
        RectF r = ccRects[ccIndex];
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (opened) closeCC(true);
        else showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    private void drawCC(Canvas c) {
        if (ccFocus[0] == null) return;
        if (ccTitle == null) {
            ccTitle = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
            ccName = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
            ccSub = Ui.text(Ui.medium, 19 * k, 0xB3FFFFFF);
            ccSection = Ui.text(Ui.semibold, 19 * k, 0x99FFFFFF);
            ccSection.setLetterSpacing(0.08f);
            ccPct = Ui.numbers(Ui.semibold, 24 * k, 0xFFFFFFFF);
            for (Paint p : new Paint[] {ccTitle, ccName, ccSub, ccSection, ccPct}) p.setColorFilter(IVORY_TEXT);
        }
        layoutCC();
        // The screen under it, dimmed (a little past the edges, so the opening scale never shows a border).
        fill.setShader(null);
        fill.setColor(0x59000000);
        c.drawRect(-60 * k, -60 * k, w + 60 * k, h + 60 * k, fill);
        // The panel: the app's liquid glass (as the round buttons and the weather pill), larger, with a soft shadow.
        float radius = 44 * k;
        tmp.set(ccPanel);
        tmp.offset(0, 14 * k);
        shadow.setColor(0x59000000);
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        scene.liquid(c, ccPanel, radius, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 14 * k, 8 * k,
                tone(0.6f, 0.45f), 1);   // the same material as the round buttons (light by day, smoked at night)
        // No white wash or outline on top: the glass's own light-catching edge is its outline (glass over glass
        // looked like a transparent panel with a white border).
        c.drawText(Ui.tr("Settings"), ccPanel.left + CC_PAD * k, ccPanel.top + CC_PAD * k + Ui.halfCap(ccTitle) * 2, ccTitle);
        // Wi-Fi and Bluetooth: their real state; the icon's circle lights up while it's on.
        String wifiSub = ccWifi == Radios.CONNECTED ? Ui.tr("Connected") : ccWifi == Radios.ON ? Ui.tr("Not connected")
                : ccWifi == Radios.OFF ? Ui.tr("Off") : Ui.tr("Settings");
        drawCCTile(c, CC_WIFI, ccWifi == Radios.OFF ? "wifi_off" : "wifi", Ui.tr("Wi-Fi"), wifiSub, true, ccWifi >= Radios.ON);
        String btSub = ccBt == Radios.ON ? Ui.tr("On") : ccBt == Radios.OFF ? Ui.tr("Off") : Ui.tr("Settings");
        drawCCTile(c, CC_BT, ccBt == Radios.OFF ? "bluetooth_disabled" : "bluetooth", Ui.tr("Bluetooth"), btSub, true, ccBt == Radios.ON);
        drawCCTile(c, CC_AIRPLAY, "airplay", Ui.tr("AirPlay"), ccAirplayInput || ccAirplayIntent != null
                ? Ui.tr("Open AirPlay") : airplayFound == null ? Ui.tr("Looking…") : Ui.tr("In TV Settings"), true,
                false);   // an action like Turn off, not a switch: never drawn as "on"
        drawCCTile(c, CC_OFF, "power_settings_new", Ui.tr("Turn off"), Ui.tr("Screen off"), true, false);
        drawCCVolume(c);
        c.drawText(Ui.tr("HOME SCREEN"), ccPanel.left + CC_PAD * k + 4 * k, ccSectionY, ccSection);
        drawCCTile(c, CC_REFRESH, "refresh", Ui.tr("Refresh"), Ui.tr("New picks now"), false, false);
        drawCCTile(c, CC_HOME_SETTINGS, "home", Ui.tr("Home Settings"), "ibtzy TV Launcher", false, false);
        drawCCTile(c, CC_CLEAR, "delete", Ui.tr("Clear watch history"), Ui.tr("Titles opened from the cards"), false, false);
        drawCCTile(c, CC_ALL, "settings", Ui.tr("All settings"), null, false, false);
    }

    /**
     * A tile: glass a little lighter than the panel, an icon (in a round chip on the top two rows: white when the thing
     * is on, with a blue icon), the name and a line under it. Focused: it grows 3%, lighter, with a white rim.
     */
    private void drawCCTile(Canvas c, int i, String symbol, String name, String sub, boolean chip, boolean on) {
        float f = Ui.clamp(ccFocus[i].value), s = 1 + 0.03f * f;
        RectF base = ccRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = cell.height() * (chip ? 0.32f : 0.3f);
        // A lighter layer of the panel's glass, lit from the top; its edge catches the light. Focused: brighter body
        // and a full, wider edge (with the 3% growth), clearly visible from the sofa.
        glassBody(c, cell, r, 0.13f + 0.25f * f);
        focusOutline(c, cell, r, f);
        glassEdge(c, cell, r, 0.45f + 0.55f * f, (1.2f + 1.3f * f) * k);
        fill.setShader(null);
        float x = cell.left + 26 * k * s;
        if (chip) {
            float d = 54 * k * s, ccx = x + d / 2;
            fill.setColor(on ? 0xF2FFFFFF : 0x33FFFFFF);
            c.drawCircle(ccx, cy, d / 2, fill);
            Symbols.draw(getContext(), c, symbol, ccx, cy, 26 * k * s, on ? 0xFF0A84FF : 0xF2FFFFFF);
            x += d + 18 * k * s;
        } else {
            Symbols.draw(getContext(), c, symbol, x + 15 * k * s, cy, 30 * k * s, 0xF2FFFFFF);
            x += 30 * k * s + 22 * k * s;
        }
        float room = cell.right - x - (i == CC_ALL ? 56 : 18) * k;
        ccName.setTextSize(25 * k * s);
        ccSub.setTextSize(19 * k * s);
        fitPaint.set(ccName);
        String n = TextUtils.ellipsize(name, fitPaint, room, TextUtils.TruncateAt.END).toString();
        if (sub == null || sub.isEmpty()) {
            c.drawText(n, x, cy + Ui.halfCap(ccName), ccName);
        } else {
            c.drawText(n, x, cy - 3 * k * s, ccName);
            fitPaint.set(ccSub);
            c.drawText(TextUtils.ellipsize(sub, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, cy + 24 * k * s, ccSub);
        }
        if (i == CC_ALL) Symbols.draw(getContext(), c, "chevron_right", cell.right - 34 * k * s, cy, 26 * k * s, 0xF2FFFFFF);
    }

    /** The volume: a glass track, filled in white up to the level (the speaker icon on the white), the percentage. */
    private void drawCCVolume(Canvas c) {
        float f = Ui.clamp(ccFocus[CC_VOLUME].value), s = 1 + 0.02f * f;
        RectF base = ccRects[CC_VOLUME];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = cell.height() / 2;
        glassBody(c, cell, r, 0.13f + 0.2f * f);
        fill.setShader(null);
        float level = Ui.clamp(ccVolume / (float) ccVolumeMax);
        float thumb = 116 * k * s, fillW = Math.max(thumb, cell.width() * level);
        tmp.set(cell.left, cell.top, cell.left + fillW, cell.bottom);
        fill.setColor(0xF2FFFFFF);
        c.drawRoundRect(tmp, r, r, fill);
        focusOutline(c, cell, r, f);
        glassEdge(c, cell, r, 0.45f + 0.55f * f, (1.2f + 1.3f * f) * k);
        Symbols.draw(getContext(), c, ccVolume == 0 ? "volume_off" : "volume_up", cell.left + 46 * k * s, cy, 28 * k * s, INK);
        String pct = Math.round(level * 100) + "%";
        c.drawText(pct, cell.right - 30 * k * s - ccPct.measureText(pct), cy + Ui.halfCap(ccPct), ccPct);
    }

    /** The first-start skeleton: still showing (true) and its light moving; ends with a quick fade. */
    private boolean stepBoot(long up) {
        if (!booting) return false;
        if (bootFadeAt == 0) {
            boolean ready = !cards.isEmpty() || !recs.listLoading() || !picksOn();
            if (ready || up - bootStartedAt > BOOT_MAX_MS) bootFadeAt = up;
        }
        float t = bootFadeAt == 0 ? 0 : Ui.clamp((up - bootFadeAt) / (float) BOOT_FADE_MS);
        if (t >= 1) {
            booting = false;
            bootLayer.setVisibility(GONE);
            Startup.mark("boot skeleton gone");
            return false;
        }
        bootLayer.setAlpha(1 - t * t * (3 - 2 * t));
        bootLayer.invalidate();   // the light sweeping across
        return true;
    }

    /**
     * The whole screen while the app first loads: the background it will have, and in the real places and sizes the
     * title, the tabs, the round buttons, the weather pill, the 12 cards (their own skeletons) and the dock's glass with
     * its icons. One light sweeps across all of it (the cards' shimmer).
     */
    private void drawBoot(Canvas c) {
        if (c != null) {   // null: recording its shapes (bootLayout)
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setShader(nightGradient);
            fill.setAlpha(255);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
        if (lTitle == null) return;
        bootShape(c, MARGIN * k, (L_TITLE_BASE - 46) * k, MARGIN * k + lTitle.measureText(Ui.tr("For You")), L_TITLE_BASE * k, 14 * k);
        layoutTabs();
        for (int i = 0; i < TABS; i++) {   // Tabs V1: the tiles and the selected capsule
            RectF r = topRects[i];
            bootShape(c, r.left, r.top, r.right, r.bottom, 24 * k);
        }
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[TABS + i];
            bootShape(c, r.left, r.top, r.right, r.bottom, r.width() / 2);
        }
        float cy = (L_TABBAR_TOP + L_TABBAR_H / 2) * k;
        bootShape(c, (CONTENT_R - 330) * k, cy - 22 * k, CONTENT_R * k, cy + 22 * k, 22 * k);   // the weather and time pill
        if (bentoLaidOut != 0) layoutBento(0);
        for (int i = 0; i < BENTO.length; i++) drawSkeletonSlot(c, cardRects[i], bentoBig[i], 1);
        if (platterRect.width() > 0) {
            bootShape(c, platterRect.left, platterRect.top, platterRect.right, platterRect.bottom, platterRect.height() / 2);
            for (int i = 0; i < shelf.size() && i < tileRects.length; i++) {
                RectF r = tileRects[i];
                if (r.right < platterRect.left || r.left > platterRect.right) continue;
                bootShape(c, r.left, r.top, r.right, r.bottom, TILE_RADIUS * k);
            }
        }
    }

    /** One skeleton shape (the cards' placeholder color and the moving light). */
    private void bootShape(Canvas c, float l, float t, float r, float b, float radius) {
        if (bootRecord != null) {
            bootRecord.add(new float[] {BOOT_SHAPE, l, t, r, b, radius, 1});
            return;
        }
        tmp.set(l, t, r, b);
        fill.setShader(null);
        fill.setColor(0x1AFFFFFF);
        c.drawRoundRect(tmp, radius, radius, fill);
        if (shimmer != null) {
            fill.setShader(shimmer);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, radius, radius, fill);
            fill.setShader(null);
        }
    }

    private void drawSkeletonSlot(Canvas c, RectF slot, boolean big, float alpha) {
        float r = 20 * k, band = 520 * k;
        if (bootRecord != null) {   // the same shapes as below, as data
            float pad = 16 * k, icon = (big ? 48 : 36) * k;   // Home Design V1: the cards' own padding and round icon
            bootRecord.add(new float[] {BOOT_SLOT, slot.left, slot.top, slot.right, slot.bottom, r, 1});
            bootRecord.add(new float[] {BOOT_SHAPE, slot.left + pad, slot.top + pad, slot.left + pad + icon, slot.top + pad + icon, icon / 2, 0});
            float metaH = 14 * k, titleH = (big ? 30 : 20) * k, base = slot.bottom - pad;
            bootRecord.add(new float[] {BOOT_SHAPE, slot.left + pad, base - metaH, slot.left + pad + slot.width() * 0.32f, base, metaH / 2, 0});
            float titleBottom = base - 15 * k - (big ? 12 : 8) * k;
            bootRecord.add(new float[] {BOOT_SHAPE, slot.left + pad, titleBottom - titleH,
                    slot.left + pad + slot.width() * (big ? 0.55f : 0.6f), titleBottom, titleH / 2, 0});
            return;
        }
        if (shimmer == null) shimmer = new LinearGradient(0, 0, band, 0, new int[] {0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
        shimmerAt.setTranslate(-band + phase * (w + band), 0);
        shimmer.setLocalMatrix(shimmerAt);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF1C1C1E, alpha));
        c.drawRoundRect(slot, r, r, fill);
        float pad = 16 * k, icon = (big ? 48 : 36) * k;   // Home Design V1: the cards' own padding and round icon
        fill.setColor(Ui.alpha(0x1AFFFFFF, alpha));
        tmp.set(slot.left + pad, slot.top + pad, slot.left + pad + icon, slot.top + pad + icon);
        c.drawRoundRect(tmp, icon / 2, icon / 2, fill);
        float metaH = 14 * k, titleH = (big ? 30 : 20) * k, base = slot.bottom - pad;
        tmp.set(slot.left + pad, base - metaH, slot.left + pad + slot.width() * 0.32f, base);
        c.drawRoundRect(tmp, metaH / 2, metaH / 2, fill);
        float titleBottom = base - 15 * k - (big ? 12 : 8) * k;
        tmp.set(slot.left + pad, titleBottom - titleH, slot.left + pad + slot.width() * (big ? 0.55f : 0.6f), titleBottom);
        c.drawRoundRect(tmp, titleH / 2, titleH / 2, fill);
        fill.setShader(shimmer);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(slot, r, r, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    /** When each card's image was first shown (its fade-in); kept, so coming back to Explore shows them at once. */
    private final Map<String, Long> revealedAt = new HashMap<>();

    /** A card still waiting for its image, or fading in (the skeletons' light keeps moving meanwhile). */
    private boolean cardsWaiting() {
        long up = SystemClock.uptimeMillis();
        for (int i = 0; i < Math.min(cards.size(), SLOTS); i++) {
            Recs.Pick p = cards.get(i);
            Backdrop b = recs.backdrop(p);
            if (b == null && !recs.imageFailed(p)) return true;
            Long at = b == null ? null : revealedAt.get(p.backdrop);
            if (b != null && (at == null || up - at < CARD_REVEAL_MS + 10)) return true;
        }
        return false;
    }

    /** Fewer than 10 cards: why, in small text on the tab row (no empty tiles in the grid). */
    private void drawBentoNote(Canvas c, int count) {
        if (count >= BENTO.length || tab != 0 || !Recs.EXPLORE_ON) return;   // Explore's 7-day note: off with it
        String note = recs.exploreNote();
        if (note.isEmpty()) return;
        float x = topRects[2].right + 40 * k, max = CONTENT_R * k - 520 * k - x;
        if (max < 120 * k) return;
        c.drawText(TextUtils.ellipsize(note, bNote, max, TextUtils.TruncateAt.END).toString(), x,
                topRects[2].centerY() + Ui.halfCap(bNote), bNote);
    }

    /** No cards at all: the reason (no app, nothing on your apps, or the load failed), centered in the grid's area. */
    private void drawBentoEmpty(Canvas c) {
        String error = recs.exploreError();
        String note = !error.isEmpty() ? Ui.trf("Couldn't load recommendations ({error}). Trying again soon.", "error", error)
                : tab != 0 || !Recs.EXPLORE_ON || recs.exploreNote().isEmpty() ? Ui.tr("No recommendations on your apps right now.") : recs.exploreNote();
        int width = Math.round(900 * k);
        if (!note.equals(bentoNoteText) || bentoNoteLayout == null || bentoNoteLayout.getWidth() != width) {
            bentoNoteText = note;
            bentoNoteLayout = android.text.StaticLayout.Builder.obtain(note, 0, note.length(), bNote, width)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build();
        }
        c.save();
        float areaTop = V1_BENTO_TOP * k, areaBottom = (contShown > 0 && cards.size() > 0 ? V1_BENTO_TOP + V1_BENTO_H : V1_CONT_TOP + V1_CONT_H) * k;
        c.translate(w / 2f - width / 2f, (areaTop + areaBottom) / 2f - bentoNoteLayout.getHeight() / 2f);
        bentoNoteLayout.draw(c);
        c.restore();
    }

    /** Empty slots when TMDB had fewer than 10 titles: faint outlines, and in the first one, why. */
    private void drawBentoGaps(Canvas c, int count) {
        if (count >= BENTO.length) return;
        float r = 20 * k;
        for (int i = count; i < BENTO.length; i++) {
            fill.setShader(null);
            fill.setColor(0x0FFFFFFF);
            c.drawRoundRect(cardRects[i], r, r, fill);
            border.setColor(0x1FFFFFFF);
            c.drawRoundRect(cardRects[i], r, r, border);
        }
        String error = count == 0 ? recs.exploreError() : "";
        String note = !error.isEmpty() ? Ui.trf("Couldn't load new releases ({error}). Trying again soon.", "error", error)
                : recs.exploreNote();
        if (note.isEmpty()) return;
        RectF slot = cardRects[count];
        int width = Math.max(1, Math.round(slot.width() - 40 * k));
        if (!note.equals(bentoNoteText) || bentoNoteLayout == null || bentoNoteLayout.getWidth() != width) {
            bentoNoteText = note;
            bentoNoteLayout = android.text.StaticLayout.Builder.obtain(note, 0, note.length(), bNote, width)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build();
        }
        c.save();
        c.translate(slot.left + 20 * k, slot.centerY() - bentoNoteLayout.getHeight() / 2f);
        bentoNoteLayout.draw(c);
        c.restore();
    }

    /**
     * A Home card (Home Design V1): the artwork filling a rounded tile, a shade at the bottom under the title and its
     * line, the service's round 3D icon at the top left. Resting tiles sit flat (no shadow). Focused: it grows 2%, gets
     * a 3 px accent ring 3 px outside its edge and a soft shadow, all inside half of the 24 px gap, so it never covers a
     * neighbour; a Continue Watching card also shows the 3D play button. The resting card is one cached bitmap
     * (drawBentoPlate) and the focused one is that bitmap scaled: a focus move redraws only what moves.
     */
    private void drawBentoCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = cards.get(i);
        boolean big = bentoBig[i];
        // Its image not downloaded and decoded yet: the card stays a skeleton (metadata alone isn't "ready"). Ready:
        // the card fades in over its skeleton. Failed: the fallback (dark card, icon and text), never an endless
        // skeleton. Each card on its own: none waits for the slowest.
        Backdrop b = recs.backdrop(p);
        if (b == null && !recs.imageFailed(p)) {
            drawSkeletonSlot(c, base, big, 1);
            return;
        }
        float reveal = 1;
        if (b != null) {
            long up = SystemClock.uptimeMillis();
            Long at = revealedAt.get(p.backdrop);
            if (at == null) {
                at = up;
                revealedAt.put(p.backdrop, at);
            }
            reveal = Ui.clamp((up - at) / CARD_REVEAL_MS);
        }
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + CARD_GROW * Math.max(0, v);
        float cx = base.centerX(), cy = base.centerY();
        if (f > 0.01f) {   // the shadow, under the grown card (a cached blur: the same shape on every frame)
            tmp.set(base);
            tmp.offset(0, 16 * k * f);
            shadowDeep.setColor(Ui.alpha(0xFF000000, 0.55f * f * reveal));
            c.save();
            c.scale(s, s, cx, cy);
            shadows.drawCached(c, tmp, cardRadius(i), DEEP_BLUR * k, shadowDeep);
            c.restore();
        }
        boolean overflow = fitTitle(i, p);
        c.save();
        c.scale(s, s, cx, cy);
        if (reveal < 1) {
            drawSkeletonSlot(c, base, big, 1 - reveal);
            c.saveLayerAlpha(base.left - 4 * k, base.top - 4 * k, base.right + 4 * k, base.bottom + 4 * k, Math.round(255 * reveal));
        }
        if (reveal < 1 || !drawBentoPlate(c, i, p, b, true)) paintBentoCard(c, i, p, b, false);
        drawCardTitle(c, i, p, f);   // over the cached card: it rests at the bottom and rises with focus
        if (f > 0.01f) drawCardDetails(c, i, f * reveal);
        if (reveal < 1) c.restore();   // the fade-in layer
        c.restore();
        if (f > 0.01f) {
            float g = 4.5f * k;   // the ring's middle: 3 px out, then half its 3 px width
            tmp.set(cx - base.width() * s / 2 - g, cy - base.height() * s / 2 - g, cx + base.width() * s / 2 + g, cy + base.height() * s / 2 + g);
            float rr = cardRadius(i) * s + g;
            // Can't be opened here: the same ring, quieter (as in For You); OK does nothing on it.
            border.setColor(Ui.alpha(ACCENT, f * reveal * (titleLaunchable(p.kind, p.id, p.app) ? 1 : 0.45f)));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(tmp, rr, rr, border);
            border.setStrokeWidth(2 * k);
            if (i < contShown) drawPlayDisc(c, cx, cy - base.height() * s * 0.08f, f * reveal);
        }
    }

    private static final float CARD_GROW = 0.02f;   // Home Design V1: scale(1.02)

    private float cardRadius(int i) {
        return (i < contShown ? 24 : 20) * k;
    }

    /** A Home card at rest, in screen coordinates: on the screen, or recorded into its cached bitmap. */
    private void paintBentoCard(Canvas c, int i, Recs.Pick p, Backdrop b, boolean withTitle) {
        RectF base = cardRects[i];
        boolean big = bentoBig[i], resume = i < contShown;
        float bw = base.width(), bh = base.height(), r = cardRadius(i);
        clip.reset();
        clip.addRoundRect(base, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        // Artwork, cut once from the whole 16:9 photo, like CSS object-fit: cover; object-position: 50% 40% (filled, never
        // stretched, centered on the faces Backdrop found; else 50% across, 40% down, where TMDB backdrops put faces).
        Bitmap img = b == null ? null : b.frame != null ? b.frame : b.wide;
        if (img != null) {
            float iw = img.getWidth(), ih = img.getHeight(), want = bw / bh;
            float fx = b.frame != null ? b.focusX : 0.5f, fy = b.frame != null ? b.focusY : 0.4f;
            if (iw / ih > want) {          // photo wider than the card: full height, cut across around the subject
                float sw = ih * want, sx = Math.max(0, Math.min(iw - sw, fx * iw - sw / 2));
                bentoSrc.set(Math.round(sx), 0, Math.round(sx + sw), Math.round(ih));
            } else {                        // card wider than the photo: full width, cut down around the subject
                float sh = iw / want, sy = Math.max(0, Math.min(ih - sh, fy * ih - sh / 2));
                bentoSrc.set(0, Math.round(sy), Math.round(iw), Math.round(sy + sh));
            }
            c.drawBitmap(img, bentoSrc, base, bitmapPaint);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF1C1C1E);
            c.drawRect(base, fill);
        }
        // The shade under the text: Continue Watching from the bottom up to 60% (the design's 0.9 → 0), a bento tile
        // over its lower part.
        if (bentoShades[i] == null || bentoShadeH[i] != bh) {
            float from = resume ? 0.4f : big ? 0.4f : 0.2f;
            int dark = resume ? 0xE60E0A0B : 0xE6000000;
            bentoShades[i] = new LinearGradient(0, bh * from, 0, bh, dark & 0x00FFFFFF, dark, Shader.TileMode.CLAMP);
            bentoShadeH[i] = bh;
        }
        shadeMatrix.setTranslate(0, base.top);
        bentoShades[i].setLocalMatrix(shadeMatrix);
        fill.setColor(0xFF000000);
        fill.setShader(bentoShades[i]);
        c.drawRect(base, fill);
        fill.setShader(null);
        float pad = (resume ? 20 : 16) * k, iconD = (big ? 48 : resume ? 40 : 36) * k;
        Bitmap art = HomeApps.serviceArt(getContext(), p.app);
        if (art != null) drawRoundIcon(c, art, base.left + pad, base.top + pad, iconD);
        if (withTitle) drawCardTitle(c, i, p, 0);
        c.restore();
    }

    /**
     * The focused card's details (fading in with focus f): its chip ("New this week", "New season") beside the icon, and
     * its line (S2 · ★7.6, or Continue Watching's S1 · Opened yesterday). At rest a card shows its art, icon and name only.
     */
    private void drawCardDetails(Canvas c, int i, float f) {
        RectF base = cardRects[i];
        boolean big = bentoBig[i], resume = i < contShown;
        float pad = (resume ? 20 : 16) * k, iconD = (big ? 48 : resume ? 40 : 36) * k;
        int a = Math.round(255 * f);
        String chip = i < bentoChips.length ? bentoChips[i] : "";
        if (!resume && base.width() >= 400 * k && chip != null && !chip.isEmpty()) {
            feedPaints();
            int c0 = c.saveLayerAlpha(base.left, base.top, base.right, base.top + 2 * pad + iconD, a);
            bentoChip(c, base.left + pad + iconD + 12 * k, base.top + pad + iconD / 2, chip);
            c.restoreToCount(c0);
        }
        String line = i < bentoLines.length ? bentoLines[i] : "";
        if (!line.isEmpty()) {
            TextPaint meta = metaPaint(i);
            int old = meta.getAlpha();
            meta.setAlpha(Math.round(old * f));
            drawMetaLine(c, line, base.left + pad, base.bottom - pad, meta, base.width() - 2 * pad, f);
            meta.setAlpha(old);
        }
    }

    private final Path starPath = new Path();
    private float starPathSize;
    private LinearGradient starGloss;

    /** A line with "★7.6": the ★ drawn as a gold 3D star (lit from the top), the rest as text, cut with "…" to fit. */
    private void drawMetaLine(Canvas c, String line, float x, float base, TextPaint p, float maxW, float f) {
        int si = line.indexOf('\u2605');
        if (si < 0) {
            c.drawText(TextUtils.ellipsize(line, p, maxW, TextUtils.TruncateAt.END).toString(), x, base, p);
            return;
        }
        String before = line.substring(0, si), after = line.substring(si + 1);
        float bw = p.measureText(before), size = p.getTextSize() * 0.95f, cap = Ui.halfCap(p) * 2;
        c.drawText(before, x, base, p);
        float sx = x + bw, cy = base - cap / 2;
        if (starPathSize != size) {   // a 5-point star, made once per size, centred on 0,0
            starPath.reset();
            for (int j = 0; j < 10; j++) {
                double ang = Math.PI / 5 * j - Math.PI / 2;
                float rr = (j % 2 == 0 ? 0.5f : 0.22f) * size;
                float px = (float) Math.cos(ang) * rr, py = (float) Math.sin(ang) * rr;
                if (j == 0) starPath.moveTo(px, py); else starPath.lineTo(px, py);
            }
            starPath.close();
            starGloss = new LinearGradient(0, -size / 2, 0, size / 2, new int[] {0xFFFFF1A8, 0xFFF5B82E, 0xFFB7790F},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            starPathSize = size;
        }
        c.save();
        c.translate(sx + size / 2, cy + size * 0.04f);
        fill.setColor(0xFF000000);
        fill.setShader(starGloss);
        fill.setAlpha(Math.round(255 * f));
        c.drawPath(starPath, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        c.restore();
        float tx = sx + size + 3 * k;
        c.drawText(TextUtils.ellipsize(after, p, Math.max(0, x + maxW - tx), TextUtils.TruncateAt.END).toString(), tx, base, p);
    }
    private final Matrix shadeMatrix = new Matrix();
    /** Resting cards as GPU bitmaps (CardCache): Home's and For You's (this layer), and Apps' icons (the sheet's). */
    private final CardCache heroCards = new CardCache(160, () -> {
        heroDirty = true;
        wake();
    });
    private final CardCache appCards = new CardCache(320, () -> {
        gridDirty = true;
        wake();
    });

    // The design's type, ~15% larger to read from the sofa: the large tile's name in the display face (41, down to 32 to
    // fit), the others 21; Continue Watching's 23 over a 16 line; the bento's lines 17.
    private static final float TITLE_BIG = 41, TITLE_SMALL = 21, TITLE_CONT = 23, TITLE_MIN = 32;
    private TextPaint v1TitleBig, v1Title, v1Meta, v1ContTitle, v1ContMeta;

    private void v1Paints() {
        if (v1Title != null) return;
        v1TitleBig = new TextPaint(Ui.text(Ui.displaySemibold, TITLE_BIG * k, 0xFFFFFFFF));
        v1Title = new TextPaint(Ui.text(Ui.semibold, TITLE_SMALL * k, 0xFFFFFFFF));
        v1Meta = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xBFFFFFFF));
        v1ContTitle = new TextPaint(Ui.text(Ui.semibold, TITLE_CONT * k, 0xFFFFFFFF));
        v1ContMeta = new TextPaint(Ui.text(Ui.medium, 16 * k, 0xC7FFFFFF));
    }

    private TextPaint metaPaint(int i) {
        v1Paints();
        return i < contShown ? v1ContMeta : v1Meta;
    }

    // Each card's name, measured once per name and width (not on every frame): its size, whether it fits, its "…" form.
    private final long[] titleKey = new long[SLOTS];
    private final float[] titleSize = new float[SLOTS];
    private final boolean[] titleOver = new boolean[SLOTS];
    private final String[] titleShort = new String[SLOTS];
    private final String[][] titleWrapped = new String[SLOTS][];   // a name too long for one line: up to 2 (focused)

    /** Sizes card i's name; true when it doesn't fit even so (drawn over the cached card: "…", or sliding focused). */
    private boolean fitTitle(int i, Recs.Pick p) {
        v1Paints();
        RectF base = cardRects[i];
        boolean big = bentoBig[i], resume = i < contShown;
        String t = p.title == null ? "" : p.title;
        float textW = base.width() - 2 * (resume ? 20 : 16) * k;
        long key = 31L * t.hashCode() + Float.floatToIntBits(textW) * 7L + (big ? 1 : 0) + (resume ? 2 : 0);
        if (titleKey[i] == key && titleShort[i] != null) return titleOver[i];
        TextPaint paint = big ? v1TitleBig : resume ? v1ContTitle : v1Title;
        float size = (big ? TITLE_BIG : resume ? TITLE_CONT : TITLE_SMALL) * k;
        paint.setTextSize(size);
        float full = paint.measureText(t);
        if (big) while (full > textW && size > TITLE_MIN * k) {   // the large card's name: smaller to fit
            paint.setTextSize(size -= k);
            full = paint.measureText(t);
        }
        titleKey[i] = key;
        titleSize[i] = size;
        titleOver[i] = full > textW;
        titleShort[i] = TextUtils.ellipsize(t, paint, textW, TextUtils.TruncateAt.END).toString();
        final TextPaint measure = paint;
        titleWrapped[i] = titleOver[i] ? TitleWrap.wrap(t, textW, measure::measureText, 2) : null;
        return titleOver[i];
    }

    /** Card i's name at its place: "…" when it doesn't fit, or sliding along when it's the focused card (f). */
    private void drawCardTitle(Canvas c, int i, Recs.Pick p, float f) {
        fitTitle(i, p);
        RectF base = cardRects[i];
        boolean big = bentoBig[i], resume = i < contShown;
        TextPaint title = big ? v1TitleBig : resume ? v1ContTitle : v1Title;
        title.setTextSize(titleSize[i]);
        float pad = (resume ? 20 : 16) * k, textW = base.width() - 2 * pad;
        String line = i < bentoLines.length ? bentoLines[i] : "";
        float metaBase = base.bottom - pad;
        // At rest the name sits at the bottom; focused, it slides up (with the focus spring) to make room for its line.
        float rise = line.isEmpty() ? 0 : (metaPaint(i).getTextSize() + (big ? 12 : 9) * k) * Ui.clamp(f);
        float base0 = metaBase - rise;
        String[] lines = titleWrapped[i];
        float g = Ui.clamp(f);
        if (lines == null || lines.length < 2 || g <= 0.01f) {
            c.drawText(titleShort[i], base.left + pad, base0, title);
            return;
        }
        // Too long for one line: focused, the whole name in 2 lines (the 2nd ends in "…" if it must), never sliding
        // and never past the card's edges; it fades in over the resting "…" line as the focus grows.
        int alpha = title.getAlpha();
        title.setAlpha(Math.round(alpha * (1 - g)));
        c.drawText(titleShort[i], base.left + pad, base0, title);
        title.setAlpha(Math.round(alpha * g));
        float lineH = title.getTextSize() * 1.15f;
        c.drawText(lines[0], base.left + pad, base0 - lineH, title);
        c.drawText(lines[1], base.left + pad, base0, title);
        title.setAlpha(alpha);
    }

    /** A service's round 3D icon (diameter d at x, y): its art in a circle, lit from the top, a soft shadow under it. */
    private void drawRoundIcon(Canvas c, Bitmap art, float x, float y, float d) {
        tmp.set(x, y + 6 * k, x + d, y + d + 6 * k);
        shadowSoft.setColor(0x73000000);
        shadows.drawCached(c, tmp, d / 2, 12 * k, shadowSoft);
        int size = Math.round(d);
        Bitmap icon = crispIcon(art, size, size / 2f);
        if (icon != null) {
            c.drawBitmap(icon, x, y, bitmapPaint);
        } else {
            tmp.set(x, y, x + d, y + d);
            roundImage(c, art, tmp, d / 2);
        }
        if (appSheen == null) appSheen = new LinearGradient(0, 0, 0, 1, new int[] {0x38FFFFFF, 0x00FFFFFF, 0x00000000, 0x33000000},
                new float[] {0, 0.45f, 0.6f, 1}, Shader.TileMode.CLAMP);
        appSheenM.setScale(1, d);
        appSheenM.postTranslate(0, y);
        appSheen.setLocalMatrix(appSheenM);
        fill.setShader(appSheen);
        c.drawCircle(x + d / 2, y + d / 2, d / 2, fill);
        fill.setShader(null);
        border.setColor(0x29FFFFFF);   // a hairline edge (the design's inset highlight)
        border.setStrokeWidth(1 * k);
        c.drawCircle(x + d / 2, y + d / 2, d / 2 - 0.5f * k, border);
        border.setStrokeWidth(2 * k);
    }

    private android.graphics.RadialGradient playGloss;
    private final Matrix playGlossM = new Matrix();

    /** The focused Continue Watching card's play button: a 72 px ivory 3D disc (lit from the top left) and its shadow. */
    private void drawPlayDisc(Canvas c, float x, float y, float f) {
        float d = 72 * k * (0.9f + 0.1f * f);   // grows in from 90% (never from nothing)
        tmp.set(x - d / 2, y - d / 2 + 16 * k * f, x + d / 2, y + d / 2 + 16 * k * f);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.55f * f));
        shadows.drawCached(c, tmp, d / 2, DEEP_BLUR * k, shadowDeep);
        if (playGloss == null) playGloss = new android.graphics.RadialGradient(-0.15f, -0.22f, 0.75f, new int[] {0xFFFFFFFF, 0xFFF3EDE2, 0xFFCBC1B0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        playGlossM.setScale(d, d);
        playGlossM.postTranslate(x, y);
        playGloss.setLocalMatrix(playGlossM);
        fill.setColor(0xFF000000);
        fill.setShader(playGloss);
        fill.setAlpha(Math.round(255 * f));
        c.drawCircle(x, y, d / 2, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        Symbols.draw(getContext(), c, "play", x + 3 * k, y, d * 0.42f, Ui.alpha(0xFF141413, f));
    }

    /** A resting Home card from the shared cache (a name that doesn't fit is left out: it's drawn over it). */
    private boolean drawBentoPlate(Canvas c, int i, Recs.Pick p, Backdrop b, boolean overflow) {
        long sig = plateSig(i, p, b) * 31 + (overflow ? 1 : 0);
        return heroCards.draw(c, 0x7F000000L + i, sig, cardRects[i], 2, pc -> paintBentoCard(pc, i, p, b, !overflow));
    }

    /** Everything a resting card's look depends on (no allocation: it runs for every card on every frame). */
    private long plateSig(int i, Recs.Pick p, Backdrop b) {
        RectF base = cardRects[i];
        boolean big = bentoBig[i], resume = i < contShown;
        long h = 17;
        h = 31 * h + System.identityHashCode(b);
        h = 31 * h + (b == null ? 0 : System.identityHashCode(b.frame != null ? b.frame : b.wide));
        h = 31 * h + (p.title == null ? 0 : p.title.hashCode());
        h = 31 * h + (i < bentoLines.length && bentoLines[i] != null ? bentoLines[i].hashCode() : 0);
        h = 31 * h + (i < bentoChips.length && bentoChips[i] != null ? bentoChips[i].hashCode() : 0);
        h = 31 * h + (p.app == null ? 0 : p.app.hashCode());
        h = 31 * h + Float.floatToIntBits(base.width());
        h = 31 * h + Float.floatToIntBits(base.height());
        h = 31 * h + Float.floatToIntBits(k);
        h = 31 * h + (big ? 1 : 0) + (resume ? 2 : 0) + (dark() > 0.5f ? 4 : 0);
        Bitmap art = HomeApps.serviceArt(getContext(), p.app);   // the icon: a plain one first, the crisp one when ready
        if (art != null) {
            int size = Math.round((big ? 48 : resume ? 40 : 36) * k);
            h = 31 * h + (crispIcon(art, size, size / 2f) != null ? 1 : 0);
        }
        return h;
    }

    /** Memory is short: the resting cards' bitmaps go (they're painted again when next shown). */
    void trimCaches() {
        heroCards.clear();
        appCards.clear();
        consoleCache.clear();
    }

    /** A card's chip: dark glass so it reads on bright artwork too (Games' lighter chip sits on the plain background). */
    private void bentoChip(Canvas c, float x, float cy, String text) {
        float h = 34 * k, pad = 14 * k, cw = pad + feedChip.measureText(text) + pad;
        glassTmp.set(x, cy - h / 2, x + cw, cy + h / 2);
        fill.setShader(null);
        fill.setColor(0x990E0A0B);
        c.drawRoundRect(glassTmp, h / 2, h / 2, fill);
        border.setColor(0x33F3EDE2);
        border.setStrokeWidth(1 * k);
        c.drawRoundRect(glassTmp, h / 2, h / 2, border);
        border.setStrokeWidth(2 * k);
        c.drawText(text, x + pad, cy + Ui.halfCap(feedChip), feedChip);
    }

    private TextPaint contHead, contHeadItalic, contSub;

    /**
     * Continue Watching's heading column (Home Design V1, "Editorial"): an accent line, "Continue" over an italic
     * "watching" in the display face, and how many titles there are and what OK does; centred on the cards' height.
     */
    private void drawContinueLabelPaints() {
        contHead = new TextPaint(Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF));
        contHeadItalic = new TextPaint(Ui.text(Ui.displayItalic, 56 * k, 0xD9FFFFFF));
        contSub = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xBFFFFFFF));
        for (Paint p : new Paint[] {contHead, contHeadItalic, contSub}) p.setColorFilter(IVORY_TEXT);
    }

    private void drawContinueLabel(Canvas c) {
        if (contHead == null) drawContinueLabelPaints();
        float x = MARGIN * k, lineH = 56 * k * 0.95f, subH = 17 * k * 1.4f;
        float block = 4 * k + 16 * k + 2 * lineH + 16 * k + subH;
        float y = contHeadBase + (V1_CONT_H * k - block) / 2;
        fill.setShader(null);
        fill.setColor(ACCENT);
        tmp.set(x, y, x + 48 * k, y + 4 * k);
        c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
        y += 4 * k + 16 * k;
        c.drawText(Ui.tr("Continue"), x, y + lineBase(contHead, lineH), contHead);
        y += lineH;
        c.drawText(Ui.tr("watching"), x, y + lineBase(contHeadItalic, lineH), contHeadItalic);
        y += lineH + 16 * k;
        c.drawText(Ui.tr("You said \u201Cone more episode\u201D"), x, y + lineBase(contSub, subH), contSub);
    }

    /** The baseline within a line box of height h (as CSS centres the glyphs' ascent and descent in it). */
    private static float lineBase(Paint p, float h) {
        Paint.FontMetrics m = p.getFontMetrics();
        return (h - (m.descent - m.ascent)) / 2 - m.ascent;
    }


    /**
     * The round buttons (Wi-Fi, Bluetooth and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus). No text below them.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[TABS + i];
            float f = Ui.clamp(topFocus[TABS + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            fill.setShader(null);   // Concept 1: a translucent dark disc and a hairline, no glass or shadow
            fill.setColor(tone(0x8CFFFFFF, 0x730E0A0B));
            c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            border.setColor(tone(0x1F000000, 0x33F3EDE2));
            border.setStrokeWidth(1 * k);
            c.drawCircle(cell.centerX(), cell.centerY(), rad, border);
            border.setStrokeWidth(2 * k);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(accent(f));   // focused: the accent disc (was white), the icon dark on it
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, accent(0.92f)), L_INK, f));
            // No name or status under them: it covered the weather and clock. Wi-Fi/Bluetooth state shows in the icon.
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (picksOn() && tab == APPS_TAB) {
                    enterApps(-1);   // up from the dock: back into Apps, where focus was
                    return true;
                }
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : TABS;
                } else {
                    zone = Z_CARDS;
                    float x = shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX();
                    cardIndex = bento() ? sectionFirst(false) : nearest(cardRects, cards.size(), x);
                    followCardSoon();
                }
                break;
            case Z_CARDS:
                if (tab == FOR_YOU_TAB) {
                    if (!moveFeed(dx, dy)) return true;
                    break;
                }
                if (bento()) {
                    int next = bentoMove(cardIndex, dx, dy);
                    if (tab == 0) next = HomeNav.across(cardIndex, next, contShown);   // another section: its first card
                    if (next >= 0) {
                        cardIndex = next;
                        followCardSoon();
                    } else if (dy < 0) {
                        zone = Z_TOP;
                        topIndex = tab;
                    } else if (dy > 0) {
                        zone = Z_SHELF;
                        dockAwake = true;   // arrived by moving: the dock's first app pops out now
                        shelfIndex = 0;     // always the first app (it was the one under the card, often the 2nd)
                        aimDockScroll(false);   // scrolled along? back to the start, smoothly
                    } else {
                        return true;
                    }
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    dockAwake = true;
                    shelfIndex = 0;   // the dock's first app
                    aimDockScroll(false);   // scrolled along? back to the start, smoothly
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : TABS, Math.min(TABS - 1 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < TABS) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (tab == FOR_YOU_TAB) {
                        zone = Z_CARDS;        // down from the tabs: For You's switch (Movies & Shows | Games)
                        feedRow = -1;
                        followFeed(false);
                        break;
                    }
                    if (tab == APPS_TAB) {
                        enterApps(A_SEARCH);   // down from the tabs (or round buttons): the search bar under them
                        return true;
                    }
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                        dockAwake = true;
                        shelfIndex = 0;
                        aimDockScroll(false);   // scrolled along? back to the start, smoothly
                    } else {
                        zone = Z_CARDS;
                        cardIndex = bento() ? sectionFirst(true)
                                : nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                        followCardSoon();
                    }
                } else if (topIndex < TABS && !lightButtons.isEmpty()) {
                    topIndex = TABS;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
