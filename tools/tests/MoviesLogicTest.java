import io.ibtzy.tvlauncher.MoviesLogic;
import java.util.Arrays;

/** Tests for For You's Movies V1 texts (no Android): run by tools/tests/run.sh. */
public class MoviesLogicTest {
    private static int failed;

    private static void check(String name, Object got, Object want) {
        boolean ok = got instanceof String[] ? Arrays.equals((String[]) got, (String[]) want) : String.valueOf(got).equals(String.valueOf(want));
        if (!ok) { failed++; System.out.println("FAIL " + name + ": " + (got instanceof String[] ? Arrays.toString((String[]) got) : got)); }
    }

    public static void main(String[] args) {
        // Genres: TMDB's names, short, at most 2, joined.
        check("two genres", MoviesLogic.genres(new String[] {"Science Fiction", "Thriller", "Drama"}), "Sci-fi · Thriller");
        check("series genres", MoviesLogic.genres(new String[] {"Action & Adventure", "Sci-Fi & Fantasy"}), "Action · Sci-fi");
        check("no genres", MoviesLogic.genres(new String[0]), "");
        check("null genres", MoviesLogic.genres(null), "");
        check("blank and null names skipped", MoviesLogic.genres(new String[] {" ", null, "Crime"}), "Crime");
        check("the same short name once", MoviesLogic.genres(new String[] {"Science Fiction", "Sci-Fi & Fantasy", "Drama"}), "Sci-fi · Drama");

        // Rating: TMDB's 0-10 average, one decimal; none when missing or malformed.
        check("rating", MoviesLogic.rating(7.63), "7.6");
        check("rating rounds", MoviesLogic.rating(8.05), "8.1");
        check("whole rating", MoviesLogic.rating(9), "9.0");
        check("no votes", MoviesLogic.rating(0), "");
        check("NaN", MoviesLogic.rating(Double.NaN), "");
        check("over 10", MoviesLogic.rating(11), "");
        check("negative", MoviesLogic.rating(-1), "");

        // Description: one paragraph, spaces collapsed; nothing when missing.
        check("description", MoviesLogic.description("  A thief.\n\nIn  Paris. "), "A thief. In Paris.");
        check("no description", MoviesLogic.description(null), "");
        check("blank description", MoviesLogic.description("   "), "");

        // Headings: the first word upright, the rest italic ("Trending *this week*").
        check("heading", MoviesLogic.heading("Trending this week"), new String[] {"Trending", "this week"});
        check("one word", MoviesLogic.heading("Upcoming"), new String[] {"Upcoming", ""});
        check("heading spaces", MoviesLogic.heading("  Most   anticipated "), new String[] {"Most", "anticipated"});
        check("empty heading", MoviesLogic.heading(null), new String[] {"", ""});

        // Description lines: whole sentences in at most 3 lines, never "…"; a first sentence too long is cut at a word.
        java.util.function.ToDoubleFunction<String> w = String::length;   // 1 unit per character
        check("all fits", MoviesLogic.descriptionLines("A thief. In Paris.", 20, w, 3), new String[] {"A thief. In Paris."});
        check("drops the sentence that doesn't fit",
                MoviesLogic.descriptionLines("One two three. Four five six. Seven eight nine ten eleven.", 15, w, 3),
                new String[] {"One two three.", "Four five six."});
        check("long first sentence: cut at a word, no …",
                MoviesLogic.descriptionLines("aaaa bbbb cccc dddd eeee ffff gggg", 9, w, 3), new String[] {"aaaa bbbb", "cccc dddd", "eeee ffff"});
        check("ends with ! and ?", MoviesLogic.descriptionLines("Run! Why? Because.", 10, w, 3), new String[] {"Run! Why?", "Because."});
        check("empty", MoviesLogic.descriptionLines("", 10, w, 3), new String[0]);
        check("null", MoviesLogic.descriptionLines(null, 10, w, 3), new String[0]);
        check("spaces collapsed", MoviesLogic.descriptionLines("  Hi.\n\n Bye. ", 10, w, 3), new String[] {"Hi. Bye."});
        check("no room", MoviesLogic.descriptionLines("Hi.", 0, w, 3), new String[0]);
        check("never an ellipsis", String.join("|", MoviesLogic.descriptionLines("Word word word word word word word word word word.", 10, w, 3)).contains("\u2026"), false);

        if (failed > 0) { System.out.println("MoviesLogicTest: " + failed + " failed"); System.exit(1); }
        System.out.println("MoviesLogicTest: all passed");
    }
}
