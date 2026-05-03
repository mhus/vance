package de.mhus.vance.foot.ui;

import java.util.Locale;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;

/**
 * Parses a JLine-style expression string into an {@link AttributedStyle}.
 *
 * <p>Grammar — comma-separated tokens, whitespace tolerated:
 * <pre>
 *   token := "fg:" color
 *          | "bg:" color
 *          | modifier
 *   color := "default" | base | "bright-" base
 *   base := "black" | "red" | "green" | "yellow"
 *         | "blue" | "magenta" | "cyan" | "white"
 *   modifier := "bold" | "faint" | "italic" | "underline"
 *             | "blink" | "inverse" | "conceal" | "crossed-out"
 * </pre>
 *
 * <p>Examples: {@code fg:red,bold}, {@code fg:bright-black},
 * {@code fg:green,bg:default,italic}. Empty / blank input returns
 * {@code null} so the caller can fall through to "no styling" (terminal
 * default — looks white on most palettes).
 *
 * <p>Unknown tokens are ignored silently rather than failing the boot —
 * a misconfigured colour is annoying, but not worth blocking the CLI
 * from coming up. (Logging a warn would require a logger here; the
 * caller is expected to do that if it needs visibility.)
 */
public final class StyleParser {

    private StyleParser() {}

    /**
     * Parse {@code spec} into an {@link AttributedStyle}, or {@code null}
     * when the spec is blank / contains no recognised tokens (= "render
     * unstyled, default terminal colour").
     */
    public static @Nullable AttributedStyle parse(@Nullable String spec) {
        if (spec == null || spec.isBlank()) return null;
        AttributedStyle style = AttributedStyle.DEFAULT;
        boolean any = false;
        for (String raw : spec.split(",")) {
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            AttributedStyle next = applyToken(style, token);
            if (next != null) {
                style = next;
                any = true;
            }
        }
        return any ? style : null;
    }

    private static @Nullable AttributedStyle applyToken(AttributedStyle style, String token) {
        if (token.startsWith("fg:")) {
            Integer color = parseColor(token.substring(3));
            if (color == null) return null;
            return color < 0 ? style.foregroundDefault() : style.foreground(color);
        }
        if (token.startsWith("bg:")) {
            Integer color = parseColor(token.substring(3));
            if (color == null) return null;
            return color < 0 ? style.backgroundDefault() : style.background(color);
        }
        return switch (token) {
            case "bold" -> style.bold();
            case "faint" -> style.faint();
            case "italic" -> style.italic();
            case "underline" -> style.underline();
            case "blink" -> style.blink();
            case "inverse" -> style.inverse();
            case "conceal" -> style.conceal();
            case "crossed-out", "strikethrough" -> style.crossedOut();
            default -> null;
        };
    }

    /**
     * @return ANSI colour index 0..15, {@code -1} for the special
     *         "default" sentinel, or {@code null} when {@code name}
     *         doesn't name a known colour.
     */
    private static @Nullable Integer parseColor(String name) {
        if ("default".equals(name) || "*".equals(name)) return -1;
        boolean bright = false;
        String base = name;
        if (base.startsWith("bright-")) {
            bright = true;
            base = base.substring("bright-".length());
        }
        int idx = switch (base) {
            case "black" -> AttributedStyle.BLACK;
            case "red" -> AttributedStyle.RED;
            case "green" -> AttributedStyle.GREEN;
            case "yellow" -> AttributedStyle.YELLOW;
            case "blue" -> AttributedStyle.BLUE;
            case "magenta" -> AttributedStyle.MAGENTA;
            case "cyan" -> AttributedStyle.CYAN;
            case "white", "gray", "grey" -> AttributedStyle.WHITE;
            default -> -2;
        };
        if (idx == -2) return null;
        // "gray"/"grey" alias — map to bright-black, the conventional
        // grey rendering on every modern terminal.
        if ("gray".equals(base) || "grey".equals(base)) {
            return AttributedStyle.BRIGHT + AttributedStyle.BLACK;
        }
        return bright ? AttributedStyle.BRIGHT + idx : idx;
    }
}
