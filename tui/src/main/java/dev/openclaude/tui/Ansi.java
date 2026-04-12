package dev.openclaude.tui;

/**
 * ANSI escape code constants and helpers for terminal rendering.
 */
public final class Ansi {

    private Ansi() {}

    public static final String ESC = "\u001b[";
    public static final String RESET = ESC + "0m";
    public static final String BOLD = ESC + "1m";
    public static final String DIM = ESC + "2m";
    public static final String ITALIC = ESC + "3m";
    public static final String UNDERLINE = ESC + "4m";
    public static final String STRIKETHROUGH = ESC + "9m";

    // Colors
    public static final String BLACK = ESC + "30m";
    public static final String RED = ESC + "31m";
    public static final String GREEN = ESC + "32m";
    public static final String YELLOW = ESC + "33m";
    public static final String BLUE = ESC + "34m";
    public static final String MAGENTA = ESC + "35m";
    public static final String CYAN = ESC + "36m";
    public static final String WHITE = ESC + "37m";
    public static final String GRAY = ESC + "90m";

    // Bright colors
    public static final String BRIGHT_RED = ESC + "91m";
    public static final String BRIGHT_GREEN = ESC + "92m";
    public static final String BRIGHT_YELLOW = ESC + "93m";
    public static final String BRIGHT_BLUE = ESC + "94m";
    public static final String BRIGHT_MAGENTA = ESC + "95m";
    public static final String BRIGHT_CYAN = ESC + "96m";

    // Background
    public static final String BG_BLACK = ESC + "40m";
    public static final String BG_RED = ESC + "41m";
    public static final String BG_GREEN = ESC + "42m";
    public static final String BG_BLUE = ESC + "44m";
    public static final String BG_GRAY = ESC + "100m";

    // Cursor
    public static final String HIDE_CURSOR = ESC + "?25l";
    public static final String SHOW_CURSOR = ESC + "?25h";
    public static final String SAVE_CURSOR = ESC + "s";
    public static final String RESTORE_CURSOR = ESC + "u";

    // Screen
    public static final String CLEAR_LINE = ESC + "2K";
    public static final String CLEAR_TO_END = ESC + "0J";
    public static final String CLEAR_SCREEN = ESC + "2J";
    public static final String HOME = ESC + "H";

    public static String moveTo(int row, int col) {
        return ESC + row + ";" + col + "H";
    }

    public static String moveUp(int n) {
        return n > 0 ? ESC + n + "A" : "";
    }

    public static String moveDown(int n) {
        return n > 0 ? ESC + n + "B" : "";
    }

    public static String moveRight(int n) {
        return n > 0 ? ESC + n + "C" : "";
    }

    public static String moveLeft(int n) {
        return n > 0 ? ESC + n + "D" : "";
    }

    public static String color(String text, String color) {
        return color + text + RESET;
    }

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    public static String dim(String text) {
        return DIM + text + RESET;
    }

    public static String italic(String text) {
        return ITALIC + text + RESET;
    }

    /**
     * Strip all ANSI escape sequences from a string.
     */
    public static String strip(String text) {
        return text.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
    }

    /**
     * Calculate visible length (without ANSI codes).
     */
    public static int visibleLength(String text) {
        return strip(text).length();
    }
}
