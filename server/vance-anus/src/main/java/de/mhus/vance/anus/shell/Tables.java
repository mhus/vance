package de.mhus.vance.anus.shell;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Tiny ASCII-table helper. Spring Shell ships its own {@code TableBuilder}
 * but for our handful of fixed-shape lists a plain pad-and-print is enough
 * and keeps the wire to that API thin (in case we swap shells later).
 */
final class Tables {

    private Tables() {}

    static <T> String render(List<String> headers, List<Function<T, @Nullable Object>> cells, List<T> rows) {
        if (headers.size() != cells.size()) {
            throw new IllegalArgumentException("headers/cells size mismatch");
        }
        int cols = headers.size();
        String[][] grid = new String[rows.size() + 1][cols];
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            grid[0][c] = headers.get(c);
            widths[c] = headers.get(c).length();
        }
        for (int r = 0; r < rows.size(); r++) {
            T row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                Object value = cells.get(c).apply(row);
                String text = value == null ? "" : value.toString();
                grid[r + 1][c] = text;
                if (text.length() > widths[c]) {
                    widths[c] = text.length();
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < cols; c++) {
                sb.append(pad(grid[r][c], widths[c]));
                if (c < cols - 1) sb.append("  ");
            }
            sb.append('\n');
            if (r == 0) {
                for (int c = 0; c < cols; c++) {
                    sb.append("-".repeat(widths[c]));
                    if (c < cols - 1) sb.append("  ");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
