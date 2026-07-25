package de.mhus.vance.brain.sheet;

import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetComputed;
import de.mhus.vance.shared.document.kind.SheetDocument;
import de.mhus.vance.shared.document.kind.SheetCell;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Server-side formula evaluation for {@code kind: sheet} documents,
 * backed by Apache POI (already a {@code vance-brain} dependency). Builds
 * an in-memory workbook from the sparse cells, evaluates every formula,
 * and returns the {@link SheetComputed} overlay — the finance-style
 * separation of source ({@code cell.data}) from computed value.
 *
 * <p>Only formula cells ({@code data} starting with {@code =}) appear in
 * the overlay; plain literals are read straight from {@code data} by the
 * client. A bad formula degrades to an {@code error} value — never a
 * throw that would kill the whole recalc.
 *
 * <p>Pure: takes a {@link SheetDocument}, returns a {@link SheetComputed}.
 * Spec: {@code specification/doc-kind-sheet.md} §6; plan: {@code planning/sheet-v2.md}.
 */
@Service
public class SheetEvalService {

    /** Evaluate all formulas and return the computed overlay (stamped now). */
    public SheetComputed evaluate(SheetDocument doc) {
        List<SheetComputed.Value> values = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("s");
            Map<String, Cell> formulaCells = new LinkedHashMap<>();

            // Pass 1: literals; queue formulas.
            for (SheetCell c : doc.cells()) {
                SheetCodec.Address addr = SheetCodec.parseAddress(c.field());
                if (addr == null) continue;
                Cell poi = cellAt(sheet, addr);
                String data = c.data();
                if (data.startsWith("=")) {
                    String formula = data.substring(1).trim();
                    if (formula.isEmpty()) {
                        values.add(error(c.field(), "#ERROR!", "empty formula"));
                        continue;
                    }
                    try {
                        poi.setCellFormula(formula);
                        formulaCells.put(c.field(), poi);
                    } catch (RuntimeException e) {
                        values.add(error(c.field(), "#ERROR!", e.getMessage()));
                    }
                } else {
                    setLiteral(poi, data);
                }
            }

            // Pass 2: evaluate the formula cells.
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (Map.Entry<String, Cell> e : formulaCells.entrySet()) {
                values.add(evaluateCell(ev, e.getKey(), e.getValue()));
            }
        } catch (Exception e) {
            // Workbook lifecycle failure — return whatever we gathered.
        }
        return new SheetComputed(Instant.now().toString(), values);
    }

    private static SheetComputed.Value evaluateCell(FormulaEvaluator ev, String field, Cell cell) {
        try {
            org.apache.poi.ss.usermodel.CellValue cv = ev.evaluate(cell);
            return switch (cv.getCellType()) {
                case NUMERIC -> new SheetComputed.Value(
                        field, formatNumber(cv.getNumberValue()), "number", null);
                case STRING -> new SheetComputed.Value(field, cv.getStringValue(), "text", null);
                case BOOLEAN -> new SheetComputed.Value(
                        field, String.valueOf(cv.getBooleanValue()), "boolean", null);
                case ERROR -> {
                    String err = errorString(cv.getErrorValue());
                    yield new SheetComputed.Value(field, err, "error", err);
                }
                default -> new SheetComputed.Value(field, "", "empty", null);
            };
        } catch (RuntimeException e) {
            return error(field, "#ERROR!", e.getMessage());
        }
    }

    private static Cell cellAt(Sheet sheet, SheetCodec.Address addr) {
        int rowIdx = addr.row() - 1;                                   // A1 → 0-based
        int colIdx = SheetCodec.columnIndexFromLetter(addr.column()) - 1;
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        return cell;
    }

    private static void setLiteral(Cell cell, String data) {
        String t = data.trim();
        if (t.isEmpty()) {
            cell.setBlank();
            return;
        }
        if ("TRUE".equalsIgnoreCase(t)) { cell.setCellValue(true); return; }
        if ("FALSE".equalsIgnoreCase(t)) { cell.setCellValue(false); return; }
        try {
            cell.setCellValue(Double.parseDouble(t));
            return;
        } catch (NumberFormatException ignore) {
            // not numeric — plain text
        }
        cell.setCellValue(data);
    }

    private static SheetComputed.Value error(String field, String value, @Nullable String msg) {
        return new SheetComputed.Value(field, value, "error", msg);
    }

    private static String errorString(byte code) {
        try {
            return FormulaError.forInt(code).getString();
        } catch (RuntimeException e) {
            return "#ERROR!";
        }
    }

    private static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
