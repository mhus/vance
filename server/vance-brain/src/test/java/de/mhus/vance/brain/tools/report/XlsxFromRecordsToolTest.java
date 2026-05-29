package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Pure-helper + roundtrip smoke tests for {@link XlsxFromRecordsTool}.
 * The actual Spring-context-wired tool invocation lives in opt-in
 * integration tests.
 */
class XlsxFromRecordsToolTest {

    // ─── sanitizeSheetName ─────────────────────────────────────────

    @Test
    void sanitizeSheetName_nullOrBlankFallsBackToDefault() {
        assertThat(XlsxFromRecordsTool.sanitizeSheetName(null))
                .isEqualTo("Sheet1");
        assertThat(XlsxFromRecordsTool.sanitizeSheetName(""))
                .isEqualTo("Sheet1");
        assertThat(XlsxFromRecordsTool.sanitizeSheetName("   "))
                .isEqualTo("Sheet1");
    }

    @Test
    void sanitizeSheetName_forbiddenCharsReplacedWithDash() {
        assertThat(XlsxFromRecordsTool.sanitizeSheetName("A/B:C\\D?E*F[G]H"))
                .isEqualTo("A-B-C-D-E-F-G-H");
    }

    @Test
    void sanitizeSheetName_keepsAcceptableChars() {
        assertThat(XlsxFromRecordsTool.sanitizeSheetName("Sales Q3 2026"))
                .isEqualTo("Sales Q3 2026");
    }

    @Test
    void sanitizeSheetName_truncatesAt31() {
        String long_ = "x".repeat(40);
        assertThat(XlsxFromRecordsTool.sanitizeSheetName(long_))
                .hasSize(31);
    }

    @Test
    void sanitizeSheetName_emptyAfterStripFallsBack() {
        assertThat(XlsxFromRecordsTool.sanitizeSheetName("   "))
                .isEqualTo("Sheet1");
    }

    // ─── defaultOutputPath ─────────────────────────────────────────

    @Test
    void defaultOutputPath_withTitleUsesSlug() {
        String p = XlsxFromRecordsTool.defaultOutputPath("Sales Q3");
        assertThat(p).startsWith("reports/Sales-Q3-").endsWith(".xlsx");
    }

    @Test
    void defaultOutputPath_nullTitleFallsBack() {
        String p = XlsxFromRecordsTool.defaultOutputPath(null);
        assertThat(p).startsWith("reports/records-").endsWith(".xlsx");
    }

    // ─── render — actual XLSX roundtrip ───────────────────────────

    private static RecordsItem item(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return new RecordsItem(values, new LinkedHashMap<>(), new ArrayList<>());
    }

    @Test
    void render_producesValidXlsxBytes() throws Exception {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("name", "qty", "price"),
                List.of(
                        item("name", "Apple", "qty", "3", "price", "1.20"),
                        item("name", "Banana", "qty", "5", "price", "0.50")),
                new LinkedHashMap<>());
        byte[] bytes = XlsxFromRecordsTool.render(doc, "Inventory");

        assertThat(bytes).isNotEmpty();
        // XLSX is a ZIP — magic header PK\x03\x04
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
    }

    @Test
    void render_writesHeaderAndBodyInCorrectShape() throws Exception {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("name", "qty"),
                List.of(
                        item("name", "Apple", "qty", "3"),
                        item("name", "Banana", "qty", "5")),
                new LinkedHashMap<>());
        byte[] bytes = XlsxFromRecordsTool.render(doc, "Stock");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Stock");

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("name");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("qty");

            Row r1 = sheet.getRow(1);
            assertThat(r1.getCell(0).getStringCellValue()).isEqualTo("Apple");
            assertThat(r1.getCell(1).getStringCellValue()).isEqualTo("3");

            Row r2 = sheet.getRow(2);
            assertThat(r2.getCell(0).getStringCellValue()).isEqualTo("Banana");
            assertThat(r2.getCell(1).getStringCellValue()).isEqualTo("5");
        }
    }

    @Test
    void render_missingFieldsLeaveCellsBlank() throws Exception {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b", "c"),
                List.of(item("a", "x", "c", "z")),  // b is missing
                new LinkedHashMap<>());
        byte[] bytes = XlsxFromRecordsTool.render(doc, "T");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row r1 = wb.getSheetAt(0).getRow(1);
            assertThat(r1.getCell(0).getStringCellValue()).isEqualTo("x");
            // Cell that was never written stays null in POI's model.
            assertThat(r1.getCell(1)).isNull();
            assertThat(r1.getCell(2).getStringCellValue()).isEqualTo("z");
        }
    }

    @Test
    void render_overflowValuesLandRightOfSchema() throws Exception {
        List<String> ov = new ArrayList<>();
        ov.add("extra1");
        ov.add("extra2");
        RecordsItem withOverflow = new RecordsItem(
                Map.of("a", "x"),
                new LinkedHashMap<>(),
                ov);
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a"),
                List.of(withOverflow),
                new LinkedHashMap<>());

        byte[] bytes = XlsxFromRecordsTool.render(doc, "T");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            // Header row should have 3 cells: 1 schema + 2 overflow
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("a");
            assertThat(header.getCell(1).getStringCellValue())
                    .isEqualTo("(overflow 1)");
            assertThat(header.getCell(2).getStringCellValue())
                    .isEqualTo("(overflow 2)");
            // Body
            Row r1 = sheet.getRow(1);
            assertThat(r1.getCell(0).getStringCellValue()).isEqualTo("x");
            assertThat(r1.getCell(1).getStringCellValue()).isEqualTo("extra1");
            assertThat(r1.getCell(2).getStringCellValue()).isEqualTo("extra2");
        }
    }

    @Test
    void render_emptyItemsStillProducesHeaderRow() throws Exception {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b"),
                List.of(),
                new LinkedHashMap<>());
        byte[] bytes = XlsxFromRecordsTool.render(doc, "Empty");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("a");
            assertThat(sheet.getRow(1)).isNull();
        }
    }
}
