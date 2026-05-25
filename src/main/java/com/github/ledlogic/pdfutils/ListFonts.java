package com.github.ledlogic.pdfutils;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ListFonts - CLI tool that prints all system fonts in CSS font-family syntax.
 *
 * Usage:
 *   javac ListFonts.java
 *   java ListFonts [options]
 *
 * Options:
 *   --format flat       One font per line as a CSS font-family value (default)
 *   --format css        Full CSS rule:  font-family: "Font Name";
 *   --format stack      CSS font stacks with a generic fallback appended
 *   --filter <text>     Case-insensitive substring filter on font name
 *   --generic <family>  Generic family appended in stack mode (default: sans-serif)
 *                       Options: serif | sans-serif | monospace | cursive | fantasy
 *   --help              Show this help message
 *
 * Examples:
 *   java ListFonts
 *   java ListFonts --format css
 *   java ListFonts --format stack --generic monospace
 *   java ListFonts --filter mono --format css
 */
public class ListFonts {

    public static void main(String[] args) {
        // Defaults
        String format  = "flat";
        String filter  = null;
        String generic = "sans-serif";

        // ── Parse arguments ──────────────────────────────────────────────────
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                    printHelp();
                    return;
                case "--format":
                    if (i + 1 < args.length) format = args[++i];
                    else die("--format requires a value (flat | css | stack)");
                    break;
                case "--filter":
                    if (i + 1 < args.length) filter = args[++i].toLowerCase();
                    else die("--filter requires a text value");
                    break;
                case "--generic":
                    if (i + 1 < args.length) generic = args[++i];
                    else die("--generic requires a value");
                    break;
                default:
                    die("Unknown option: " + args[i]);
            }
        }

        // ── Finalize for lambda capture ──────────────────────────────────────
        final String genericFamily = generic;

        // ── Gather fonts ─────────────────────────────────────────────────────
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] allFonts = ge.getAvailableFontFamilyNames();

        final String filterFinal = filter;
        List<String> fonts = Arrays.stream(allFonts)
                .filter(f -> filterFinal == null || f.toLowerCase().contains(filterFinal))
                .sorted()
                .collect(Collectors.toList());

        if (fonts.isEmpty()) {
            System.err.println("No fonts matched the filter \"" + filter + "\".");
            System.exit(1);
        }

        // ── Output ───────────────────────────────────────────────────────────
        switch (format) {
            case "flat":
                fonts.forEach(f -> System.out.println(cssQuote(f)));
                break;

            case "css":
                fonts.forEach(f ->
                        System.out.println("font-family: " + cssQuote(f) + ";"));
                break;

            case "stack":
                fonts.forEach(f ->
                        System.out.println("font-family: " + cssQuote(f) + ", " + genericFamily + ";"));
                break;

            default:
                die("Unknown format: " + format + ". Use flat, css, or stack.");
        }

        System.err.println("\n// " + fonts.size() + " font(s) listed.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the font name wrapped in double-quotes if it contains a space or
     * special character; otherwise returns it unquoted — matching CSS rules.
     * CSS requires quotes around multi-word family names.
     */
    private static String cssQuote(String fontName) {
        // Quote if name contains space, digit start, or special characters
        boolean needsQuotes = fontName.contains(" ")
                || Character.isDigit(fontName.charAt(0))
                || fontName.chars().anyMatch(c -> !Character.isLetterOrDigit(c)
                        && c != '-' && c != '_');
        return needsQuotes ? "\"" + fontName + "\"" : fontName;
    }

    private static void die(String msg) {
        System.err.println("Error: " + msg);
        System.err.println("Run with --help for usage.");
        System.exit(1);
    }

    private static void printHelp() {
        System.out.println(
            "ListFonts — print system fonts in CSS font-family syntax\n"
          + "\n"
          + "Usage:\n"
          + "  javac ListFonts.java\n"
          + "  java ListFonts [options]\n"
          + "\n"
          + "Options:\n"
          + "  --format flat       One value per line  e.g.  \"Arial\"          (default)\n"
          + "  --format css        Full CSS declaration e.g.  font-family: \"Arial\";\n"
          + "  --format stack      CSS font stack       e.g.  font-family: \"Arial\", sans-serif;\n"
          + "  --filter <text>     Case-insensitive substring filter on font name\n"
          + "  --generic <family>  Generic fallback used in stack mode (default: sans-serif)\n"
          + "                      serif | sans-serif | monospace | cursive | fantasy\n"
          + "  --help              Show this message\n"
          + "\n"
          + "Examples:\n"
          + "  java ListFonts\n"
          + "  java ListFonts --format css\n"
          + "  java ListFonts --format stack --generic monospace\n"
          + "  java ListFonts --filter arial --format css\n"
          + "  java ListFonts --filter mono --format stack --generic monospace"
        );
    }
}
