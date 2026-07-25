package de.mhus.vance.brain.sheet;

import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetColumn;
import de.mhus.vance.shared.document.kind.SheetDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * XLSX and CSV import/export for {@code kind: sheet} documents, backed by
 * Apache POI. Bridges the sparse Vance sheet model to a real spreadsheet
 * file so users can round-trip with Excel / Numbers / LibreOffice.
 *
 * <p>Mapping limits (documented, best-effort): one worksheet only (the
 * first on import); cell data, {@code numberFormat}, bold/italic/align,
 * text + background colour (RGB), per-cell borders, column widths and row
 * heights map both ways. Column/row-level borders, merges, charts and
 * multiple sheets are NOT exported/imported.
 */
@Service
public class SheetXlsxService {

    private static final double PX_PER_CHAR = 7.0;   // ~ default Calibri 11
    private static final double PT_PER_PX = 0.75;    // 96dpi → 72pt

    // ── Export ─────────────────────────────────────────────────────

    public byte[] exportXlsx(SheetDocument doc) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Sheet1");
            DataFormat fmt = wb.createDataFormat();
            Map<String, XSSFCellStyle> styleCache = new HashMap<>();

            for (SheetCell c : doc.cells()) {
                SheetCodec.Address a = SheetCodec.parseAddress(c.field());
                if (a == null) continue;
                int rowIdx = a.row() - 1;
                int colIdx = SheetCodec.columnIndexFromLetter(a.column()) - 1;
                if (rowIdx < 0 || colIdx < 0) continue;
                Row row = sheet.getRow(rowIdx);
                if (row == null) row = sheet.createRow(rowIdx);
                Cell cell = row.createCell(colIdx);
                setCellValue(cell, c.data());
                XSSFCellStyle style = styleFor(wb, fmt, styleCache, c);
                if (style != null) cell.setCellStyle(style);
            }

            for (Map.Entry<String, SheetColumn> e : doc.columns().entrySet()) {
                Integer width = e.getValue().width();
                if (width == null) continue;
                int colIdx = SheetCodec.columnIndexFromLetter(e.getKey()) - 1;
                if (colIdx < 0) continue;
                int units = (int) Math.min(255 * 256, Math.round(width / PX_PER_CHAR * 256));
                sheet.setColumnWidth(colIdx, Math.max(256, units));
            }
            for (Map.Entry<String, Integer> e : doc.rowHeights().entrySet()) {
                try {
                    int rowIdx = Integer.parseInt(e.getKey()) - 1;
                    if (rowIdx < 0) continue;
                    Row row = sheet.getRow(rowIdx);
                    if (row == null) row = sheet.createRow(rowIdx);
                    row.setHeightInPoints((float) (e.getValue() * PT_PER_PX));
                } catch (NumberFormatException ignore) {
                    // skip malformed row key
                }
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ToolException("Could not build XLSX: " + e.getMessage());
        }
    }

    public String exportCsv(SheetDocument doc) {
        Map<String, String> byField = new HashMap<>();
        int maxRow = 0;
        int maxCol = 0;
        for (SheetCell c : doc.cells()) {
            SheetCodec.Address a = SheetCodec.parseAddress(c.field());
            if (a == null) continue;
            byField.put(c.field(), c.data());
            maxRow = Math.max(maxRow, a.row());
            maxCol = Math.max(maxCol, SheetCodec.columnIndexFromLetter(a.column()));
        }
        int cols = Math.max(maxCol, doc.schema().size());
        StringBuilder sb = new StringBuilder();
        for (int r = 1; r <= maxRow; r++) {
            for (int ci = 1; ci <= cols; ci++) {
                if (ci > 1) sb.append(',');
                String key = SheetCodec.columnLetterFromIndex(ci) + r;
                sb.append(csvEscape(byField.getOrDefault(key, "")));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Import ─────────────────────────────────────────────────────

    public SheetDocument importXlsx(byte[] bytes) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) return SheetDocument.empty();
            XSSFSheet sheet = wb.getSheetAt(0);
            List<SheetCell> cells = new ArrayList<>();
            int maxRow = 0;
            int maxCol = 0;
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String data = readCellData(cell);
                    Fmt f = readCellFormat(cell);
                    if (data.isEmpty() && f.isEmpty()) continue;
                    String addr = SheetCodec.columnLetterFromIndex(cell.getColumnIndex() + 1)
                            + (cell.getRowIndex() + 1);
                    cells.add(new SheetCell(addr, data, f.color, f.background,
                            f.bold ? Boolean.TRUE : null, f.italic ? Boolean.TRUE : null,
                            f.align, f.numberFormat, f.borders, new LinkedHashMap<>()));
                    maxRow = Math.max(maxRow, cell.getRowIndex() + 1);
                    maxCol = Math.max(maxCol, cell.getColumnIndex() + 1);
                }
            }
            List<String> schema = new ArrayList<>();
            for (int ci = 1; ci <= maxCol; ci++) schema.add(SheetCodec.columnLetterFromIndex(ci));

            Map<String, SheetColumn> columns = new LinkedHashMap<>();
            for (int ci = 1; ci <= maxCol; ci++) {
                int poiWidth = sheet.getColumnWidth(ci - 1);
                if (sheet.isColumnHidden(ci - 1)) continue;
                int px = (int) Math.round(poiWidth / 256.0 * PX_PER_CHAR);
                // only record non-default widths
                if (px > 0 && Math.abs(px - defaultPxWidth(sheet)) > 4) {
                    columns.put(SheetCodec.columnLetterFromIndex(ci), new SheetColumn(px, null));
                }
            }
            Map<String, Integer> rowHeights = new LinkedHashMap<>();
            for (int r = 0; r < maxRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                if (row.getHeight() != sheet.getDefaultRowHeight()) {
                    int px = (int) Math.round(row.getHeightInPoints() / PT_PER_PX);
                    if (px > 0) rowHeights.put(Integer.toString(r + 1), px);
                }
            }

            return new SheetDocument("sheet", schema, maxRow > 0 ? maxRow : null, cells,
                    columns, rowHeights, new LinkedHashMap<>(), new LinkedHashMap<>());
        } catch (IOException | RuntimeException e) {
            throw new ToolException("Could not read XLSX: " + e.getMessage());
        }
    }

    public SheetDocument importCsv(String csv) {
        List<List<String>> grid = parseCsv(csv);
        List<SheetCell> cells = new ArrayList<>();
        int maxCol = 0;
        for (int r = 0; r < grid.size(); r++) {
            List<String> rowVals = grid.get(r);
            maxCol = Math.max(maxCol, rowVals.size());
            for (int ci = 0; ci < rowVals.size(); ci++) {
                String v = rowVals.get(ci);
                if (v == null || v.isEmpty()) continue;
                String addr = SheetCodec.columnLetterFromIndex(ci + 1) + (r + 1);
                cells.add(new SheetCell(addr, v, null, null, new LinkedHashMap<>()));
            }
        }
        List<String> schema = new ArrayList<>();
        for (int ci = 1; ci <= maxCol; ci++) schema.add(SheetCodec.columnLetterFromIndex(ci));
        return new SheetDocument("sheet", schema, grid.isEmpty() ? null : grid.size(), cells,
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>());
    }

    // ── Cell value <-> POI ─────────────────────────────────────────

    private static void setCellValue(Cell cell, String data) {
        if (data == null || data.isEmpty()) {
            cell.setBlank();
            return;
        }
        if (data.startsWith("=")) {
            try {
                cell.setCellFormula(data.substring(1));
                return;
            } catch (RuntimeException e) {
                cell.setCellValue(data); // keep as literal text if POI rejects it
                return;
            }
        }
        if ("TRUE".equalsIgnoreCase(data)) { cell.setCellValue(true); return; }
        if ("FALSE".equalsIgnoreCase(data)) { cell.setCellValue(false); return; }
        try {
            cell.setCellValue(Double.parseDouble(data));
            return;
        } catch (NumberFormatException ignore) {
            // not numeric
        }
        cell.setCellValue(data);
    }

    private static String readCellData(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case FORMULA -> "=" + cell.getCellFormula();
                case NUMERIC -> formatNumber(cell.getNumericCellValue());
                case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
                case STRING -> cell.getStringCellValue();
                default -> "";
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ── Format <-> POI ─────────────────────────────────────────────

    private @Nullable XSSFCellStyle styleFor(XSSFWorkbook wb, DataFormat fmt,
                                             Map<String, XSSFCellStyle> cache, SheetCell c) {
        boolean hasStyle = c.color() != null || c.background() != null
                || Boolean.TRUE.equals(c.bold()) || Boolean.TRUE.equals(c.italic())
                || c.align() != null || c.numberFormat() != null || c.borders() != null;
        if (!hasStyle) return null;
        String key = c.color() + "|" + c.background() + "|" + c.bold() + "|" + c.italic()
                + "|" + c.align() + "|" + c.numberFormat() + "|" + c.borders();
        XSSFCellStyle cached = cache.get(key);
        if (cached != null) return cached;

        XSSFCellStyle style = wb.createCellStyle();
        if (c.numberFormat() != null) style.setDataFormat(fmt.getFormat(c.numberFormat()));
        if (c.align() != null) {
            style.setAlignment(switch (c.align()) {
                case "center" -> HorizontalAlignment.CENTER;
                case "right" -> HorizontalAlignment.RIGHT;
                default -> HorizontalAlignment.LEFT;
            });
        }
        if (Boolean.TRUE.equals(c.bold()) || Boolean.TRUE.equals(c.italic()) || c.color() != null) {
            XSSFFont font = wb.createFont();
            if (Boolean.TRUE.equals(c.bold())) font.setBold(true);
            if (Boolean.TRUE.equals(c.italic())) font.setItalic(true);
            XSSFColor fc = hexColor(c.color());
            if (fc != null) font.setColor(fc);
            style.setFont(font);
        }
        XSSFColor bg = hexColor(c.background());
        if (bg != null) {
            style.setFillForegroundColor(bg);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        String b = c.borders();
        if (b != null) {
            if (b.indexOf('t') >= 0) style.setBorderTop(BorderStyle.THIN);
            if (b.indexOf('r') >= 0) style.setBorderRight(BorderStyle.THIN);
            if (b.indexOf('b') >= 0) style.setBorderBottom(BorderStyle.THIN);
            if (b.indexOf('l') >= 0) style.setBorderLeft(BorderStyle.THIN);
        }
        cache.put(key, style);
        return style;
    }

    /** Parsed per-cell format for import. */
    private record Fmt(@Nullable String color, @Nullable String background,
                       boolean bold, boolean italic, @Nullable String align,
                       @Nullable String numberFormat, @Nullable String borders) {
        boolean isEmpty() {
            return color == null && background == null && !bold && !italic
                    && align == null && numberFormat == null && borders == null;
        }
    }

    private static Fmt readCellFormat(Cell cell) {
        CellStyle style = cell.getCellStyle();
        if (!(style instanceof XSSFCellStyle xs)) {
            return new Fmt(null, null, false, false, null, null, null);
        }
        String numberFormat = null;
        String df = xs.getDataFormatString();
        if (df != null && !df.isBlank() && !"General".equalsIgnoreCase(df)) numberFormat = df;
        String align = switch (xs.getAlignment()) {
            case CENTER -> "center";
            case RIGHT -> "right";
            case LEFT -> "left";
            default -> null;
        };
        boolean bold = false;
        boolean italic = false;
        String color = null;
        XSSFFont font = xs.getFont();
        if (font != null) {
            bold = font.getBold();
            italic = font.getItalic();
            color = colorHex(font.getXSSFColor());
        }
        String background = colorHex(xs.getFillForegroundColorColor());
        StringBuilder b = new StringBuilder(4);
        if (xs.getBorderTop() != BorderStyle.NONE) b.append('t');
        if (xs.getBorderRight() != BorderStyle.NONE) b.append('r');
        if (xs.getBorderBottom() != BorderStyle.NONE) b.append('b');
        if (xs.getBorderLeft() != BorderStyle.NONE) b.append('l');
        String borders = b.length() == 0 ? null : b.toString();
        return new Fmt(color, background, bold, italic, align, numberFormat, borders);
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static @Nullable XSSFColor hexColor(@Nullable String hex) {
        if (hex == null || hex.isBlank()) return null;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        if (h.length() != 6) return null;
        try {
            byte[] rgb = new byte[]{
                    (byte) Integer.parseInt(h.substring(0, 2), 16),
                    (byte) Integer.parseInt(h.substring(2, 4), 16),
                    (byte) Integer.parseInt(h.substring(4, 6), 16)};
            return new XSSFColor(rgb, null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable String colorHex(@Nullable XSSFColor color) {
        if (color == null) return null;
        byte[] rgb = color.getRGB();
        if (rgb == null || rgb.length < 3) return null;
        return String.format("#%02x%02x%02x", rgb[0] & 0xff, rgb[1] & 0xff, rgb[2] & 0xff);
    }

    private static int defaultPxWidth(XSSFSheet sheet) {
        return (int) Math.round(sheet.getDefaultColumnWidth() * PX_PER_CHAR);
    }

    private static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String csvEscape(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** Minimal RFC-4180-ish CSV parser (quotes + escaped quotes + newlines). */
    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        String s = csv.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                cur.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                cur.add(field.toString());
                field.setLength(0);
                rows.add(cur);
                cur = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }
}
