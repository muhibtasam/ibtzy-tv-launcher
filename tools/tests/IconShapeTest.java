import io.freemytv.IconShape;

/** Tests for the round app icons' analysis (no Android): run by tools/tests/run.sh. */
public class IconShapeTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static int[] canvas(int n, int color) {
        int[] px = new int[n * n];
        java.util.Arrays.fill(px, color);
        return px;
    }

    private static void rect(int[] px, int n, int l, int t, int r, int b, int color) {
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) px[y * n + x] = color;
    }

    private static void disc(int[] px, int n, float cx, float cy, float rad, int color) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
            if (dx * dx + dy * dy <= rad * rad) px[y * n + x] = color;
        }
    }

    public static void main(String[] args) {
        int n = 100, black = 0xFF000000, red = 0xFFE50914, green = 0xFF1DB954, white = 0xFFFFFFFF;

        // A letter mark (Netflix's N) on its own background: centred on that colour, not filling the circle.
        int[] netflix = canvas(n, black);
        rect(netflix, n, 40, 25, 60, 75, red);
        check("letter: background is the edge colour", IconShape.background(netflix, n, n) == black);
        int[] b = IconShape.contentBounds(netflix, n, n, black);
        check("letter: bounds", b != null && b[0] == 40 && b[1] == 25 && b[2] == 60 && b[3] == 75);

        // A round logo (Spotify, YouTube Music) on a square: fills the circle.
        int[] spotify = canvas(n, black);
        disc(spotify, n, 50, 50, 42, green);
        int[] sb = IconShape.contentBounds(spotify, n, n, black);

        // The same round logo on transparency.
        int[] clear = canvas(n, 0x00000000);
        disc(clear, n, 50, 50, 45, green);
        check("transparent edge: background is transparent", (IconShape.background(clear, n, n) >>> 24) == 0);

        // A full-colour square with a small mark (YouTube's play on red): the mark centred on the red.
        int[] yt = canvas(n, red);
        rect(yt, n, 42, 40, 60, 60, white);
        check("small mark on colour: background red", IconShape.background(yt, n, n) == red);

        // A wide wordmark (Prime Video): doesn't fill (it's fitted inside the circle instead).
        int[] prime = canvas(n, black);
        rect(prime, n, 10, 38, 90, 62, 0xFF1A98FF);

        // A gear (Settings): round overall, but its teeth leave gaps at the rim: fitted inside, not filling.
        int[] gear = canvas(n, black);
        disc(gear, n, 50, 50, 44, 0xFF8899AA);
        for (int i = 0; i < 12; i += 2) {   // six notches cut into the rim
            double a = Math.PI * 2 * i / 12;
            disc(gear, n, 50 + (float) (40 * Math.cos(a)), 50 + (float) (40 * Math.sin(a)), 7, black);
        }

        // A rounded-square icon with a light rim (the bundled dock art): the rim isn't the logo.
        int[] rimmed = canvas(n, black);
        rect(rimmed, n, 0, 0, n, 2, 0xFF9A9A9A);
        rect(rimmed, n, 0, n - 2, n, n, 0xFF9A9A9A);
        rect(rimmed, n, 0, 0, 2, n, 0xFF9A9A9A);
        rect(rimmed, n, n - 2, 0, n, n, 0xFF9A9A9A);
        disc(rimmed, n, 50, 50, 36, green);

        // Nothing but the background.
        check("blank: no bounds", IconShape.contentBounds(canvas(n, black), n, n, black) == null);

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("IconShapeTest: all passed");
    }
}
