package de.mhus.vance.brain.tools.report;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Render a {@code kind: records} document as an Excel {@code .xlsx}
 * file (POI XSSF) and import the result back as a Vance Document
 * the user can download.
 *
 * <p>Use case: a {@code records} document is the natural shape for
 * structured tabular data the LLM produces — e.g. extracted line
 * items, exported survey answers, generated test cases. Users
 * often want to hand that to colleagues / open it in Excel for
 * sorting / charts / formulas — the Excel export removes the
 * "download CSV and import" friction.
 *
 * <p>Sheet shape:
 * <ul>
 *   <li>Row 1 = header row from {@link RecordsDocument#schema()},
 *       bold + frozen + auto-filter.</li>
 *   <li>Rows 2..N = one row per {@link RecordsItem#values()}, in
 *       schema order. Missing fields are blank cells.</li>
 *   <li>{@code overflow} markdown surplus values are appended at
 *       the right edge so nothing is silently dropped on the way
 *       out.</li>
 * </ul>
 *
 * <p>POI XSSF is already on the classpath via the DOCX renderer
 * (planning/web-office-suite.md §2 Layer A); this tool adds the
 * Excel sibling at minimal cost.
 */
@Component
@Slf4j
public class XlsxFromRecordsTool implements Tool {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("schema", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Inline column names — use "
                                + "this PLUS `items` to render an "
                                + "XLSX directly without first "
                                + "creating a records document. "
                                + "Mutually exclusive with `documentRef`."));
                put("items", Map.of(
                        "type", "array",
                        "items", Map.of("type", "object"),
                        "description", "Inline row data, one object "
                                + "per record. Keys are schema field "
                                + "names, values are cell strings. "
                                + "Missing keys land as blank cells. "
                                + "Mutually exclusive with `documentRef`."));
                put("documentRef", Map.of(
                        "type", "string",
                        "description", "Path or id of an existing "
                                + "kind:records document to export. "
                                + "Use this only when the records "
                                + "already live as a Vance document; "
                                + "for fresh data prefer the inline "
                                + "`schema` + `items` form."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name; "
                                + "defaults to the active project."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Optional title — used as the "
                                + "sheet name and the file's "
                                + "core-property title. Truncated to "
                                + "31 chars for the sheet name "
                                + "(Excel hard limit)."));
                put("outputPath", Map.of(
                        "type", "string",
                        "description", "Optional path for the new "
                                + "Document. Default: "
                                + "'reports/<title-slug>-<timestamp>"
                                + ".xlsx'."));
            }},
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    public XlsxFromRecordsTool(EddieContext eddieContext,
                               DocumentService documentService,
                               DocumentLinkBuilder linkBuilder,
                               ThinkProcessService thinkProcessService,
                               ProgressEmitter progressEmitter) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
    }

    @Override
    public String name() {
        return "xlsx_from_records";
    }

    @Override
    public String description() {
        return "Export a kind:records document as a downloadable "
                + "Excel (.xlsx) file. Header row from the records "
                + "schema, one body row per record. Returns the "
                + "created Document plus `markdownLink` you paste "
                + "back into chat so the user can download the file. "
                + "Uses Apache POI XSSF — no external binary, pure "
                + "Java. Opens in Excel, LibreOffice, Pages, Google "
                + "Sheets.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String documentRef = paramString(params, "documentRef");
        List<String> inlineSchema = paramStringList(params, "schema");
        List<Map<String, Object>> inlineItems = paramMapList(params, "items");
        boolean hasInline = inlineSchema != null || inlineItems != null;
        if (hasInline && documentRef != null) {
            throw new ToolException(
                    "Provide either inline 'schema'/'items' OR "
                            + "'documentRef', not both");
        }
        if (!hasInline && documentRef == null) {
            throw new ToolException(
                    "Provide either inline 'schema'+'items' or "
                            + "'documentRef'");
        }
        if (hasInline && inlineSchema == null) {
            throw new ToolException(
                    "Inline mode requires 'schema' alongside 'items'");
        }

        String title = paramString(params, "title");
        String outputPath = paramString(params, "outputPath");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();

        ThinkProcessDocument process = loadProcess(ctx);

        RecordsDocument records;
        String sourceLabel;
        if (hasInline) {
            records = fromInline(inlineSchema, inlineItems);
            sourceLabel = "inline";
        } else {
            DocumentDocument source = resolveSourceDoc(documentRef, projectName, ctx);
            records = parseRecords(source);
            sourceLabel = source.getPath();
            if (title == null) {
                title = source.getTitle() != null
                        ? source.getTitle() : leafName(source.getPath());
            }
        }
        if (records.schema().isEmpty()) {
            throw new ToolException(
                    "Records source has no schema — cannot render an "
                            + "empty header row. Pass a non-empty "
                            + "'schema' (inline) or add a schema to "
                            + "the source document first.");
        }
        String effectiveTitle = title != null ? title : "Records";

        emit(process, StatusTag.INFO,
                "Rendering XLSX from records ("
                        + records.schema().size() + " columns, "
                        + records.items().size() + " rows)…");

        long started = System.currentTimeMillis();
        byte[] bytes = render(records, effectiveTitle);
        long elapsedMs = System.currentTimeMillis() - started;

        String finalPath = outputPath != null
                ? outputPath
                : defaultOutputPath(effectiveTitle);

        DocumentDocument created;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            created = documentService.create(
                    ctx.tenantId(),
                    projectName,
                    finalPath,
                    effectiveTitle,
                    List.of("report", "xlsx"),
                    XLSX_MIME,
                    in,
                    ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not store rendered XLSX: " + e.getMessage());
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, created.getPath(), "xlsx",
                DocumentLinkBuilder.defaultModeForKind("xlsx"));
        String markdownLink = linkBuilder.linkFor(created, projectName);

        log.info("XlsxFromRecordsTool tenant='{}' source='{}' "
                        + "rows={} cols={} bytes={} elapsedMs={} path='{}'",
                ctx.tenantId(), sourceLabel,
                records.items().size(), records.schema().size(),
                bytes.length, elapsedMs, finalPath);
        if (records.items().isEmpty()) {
            log.warn("XlsxFromRecordsTool: source '{}' had 0 records — "
                    + "exported an empty sheet. Did the caller forget "
                    + "to pass 'items' inline (or populate the "
                    + "records document via records_add_row)?",
                    sourceLabel);
        }
        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        "XLSX done — %d KB saved as '%s'.",
                        bytes.length / 1024, finalPath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", created.getPath());
        out.put("size", created.getSize());
        out.put("rows", records.items().size());
        out.put("columns", records.schema().size());
        out.put("elapsedMs", elapsedMs);
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        if (records.items().isEmpty()) {
            // Loud signal back to the LLM so it doesn't ship a
            // download link for an empty sheet — typical mistake is
            // creating a kind:records stub and exporting it without
            // first adding rows via records_add_row.
            out.put("warning",
                    "The records source has 0 rows, so the XLSX "
                            + "contains only the header. Did you "
                            + "forget to pass 'items' inline (or to "
                            + "populate the records document via "
                            + "records_add_row) before exporting?");
        }
        return out;
    }

    /**
     * Build a {@link RecordsDocument} from the inline {@code schema}
     * + {@code items} parameters. Each item is a map keyed by
     * schema field name; values get string-coerced (numbers,
     * booleans → {@code String.valueOf}; {@code null} → empty).
     */
    static RecordsDocument fromInline(List<String> schema,
                                      @Nullable List<Map<String, Object>> items) {
        List<RecordsItem> records = new java.util.ArrayList<>();
        if (items != null) {
            for (Map<String, Object> raw : items) {
                if (raw == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, Object> extra = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    String key = e.getKey();
                    if (key == null) continue;
                    String val = coerceCell(e.getValue());
                    if (schema.contains(key)) {
                        values.put(key, val);
                    } else {
                        extra.put(key, e.getValue());
                    }
                }
                records.add(new RecordsItem(values, extra, new java.util.ArrayList<>()));
            }
        }
        return new RecordsDocument("records",
                List.copyOf(schema), records, new LinkedHashMap<>());
    }

    private static String coerceCell(@Nullable Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        return String.valueOf(v);
    }

    // ── Rendering ─────────────────────────────────────────────────

    /**
     * Build the XLSX bytes from a parsed records document. Header
     * row gets the bold style + freeze + auto-filter. Body rows are
     * plain string cells (the records model is all-strings for v1
     * — typed columns are tracked in {@code doc-kind-records.md}
     * §6.1). Overflow values land in extra columns past the schema.
     */
    public static byte[] render(RecordsDocument records, String title) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sanitizeSheetName(title));
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            List<String> schema = records.schema();
            int overflowMax = records.items().stream()
                    .mapToInt(item -> item.overflow().size())
                    .max().orElse(0);
            int totalCols = schema.size() + overflowMax;

            Row header = sheet.createRow(0);
            for (int c = 0; c < schema.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(schema.get(c));
                cell.setCellStyle(headerStyle);
            }
            for (int c = 0; c < overflowMax; c++) {
                Cell cell = header.createCell(schema.size() + c);
                cell.setCellValue("(overflow " + (c + 1) + ")");
                cell.setCellStyle(headerStyle);
            }

            for (int r = 0; r < records.items().size(); r++) {
                RecordsItem item = records.items().get(r);
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < schema.size(); c++) {
                    String v = item.values().getOrDefault(schema.get(c), "");
                    if (!v.isEmpty()) row.createCell(c).setCellValue(v);
                }
                List<String> overflow = item.overflow();
                for (int c = 0; c < overflow.size(); c++) {
                    String v = overflow.get(c);
                    if (v != null && !v.isEmpty()) {
                        row.createCell(schema.size() + c).setCellValue(v);
                    }
                }
            }

            sheet.createFreezePane(0, 1);
            if (totalCols > 0) {
                sheet.setAutoFilter(new CellRangeAddress(
                        0, Math.max(0, records.items().size()),
                        0, totalCols - 1));
                for (int c = 0; c < totalCols; c++) {
                    sheet.autoSizeColumn(c);
                    // Cap auto-size so a single 200-char string
                    // doesn't make Excel scroll horizontally to find
                    // anything else.
                    int width = sheet.getColumnWidth(c);
                    int cap = 80 * 256;
                    if (width > cap) sheet.setColumnWidth(c, cap);
                }
            }

            applyCoreTitle(wb, title);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ToolException(
                    "XLSX rendering failed: " + e.getMessage());
        }
    }

    private static void applyCoreTitle(Workbook wb, String title) {
        if (!(wb instanceof XSSFWorkbook xssf)) return;
        try {
            var props = xssf.getProperties();
            if (props != null && props.getCoreProperties() != null) {
                props.getCoreProperties().setTitle(title);
            }
        } catch (Exception ignored) {
            // Core properties are nice-to-have; never fail over
            // them.
        }
    }

    /**
     * Excel hard-caps sheet names at 31 chars and forbids
     * {@code : \ / ? * [ ]}. We trim and replace; an empty result
     * falls back to {@code "Sheet1"}.
     */
    static String sanitizeSheetName(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return "Sheet1";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == ':' || c == '\\' || c == '/' || c == '?'
                    || c == '*' || c == '[' || c == ']') {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) return "Sheet1";
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    // ── Source document resolution ────────────────────────────────

    private DocumentDocument resolveSourceDoc(String ref,
                                              String projectName,
                                              ToolInvocationContext ctx) {
        boolean pathLike = ref.contains("/") || ref.contains(".");
        if (pathLike) {
            Optional<DocumentDocument> byPath = documentService.findByPath(
                    ctx.tenantId(), projectName, ref);
            if (byPath.isPresent()) return byPath.get();
        }
        Optional<DocumentDocument> byId = documentService.findById(ref);
        if (byId.isPresent()) {
            DocumentDocument doc = byId.get();
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException(
                        "Source document with id '" + ref
                                + "' is not in your tenant");
            }
            return doc;
        }
        if (!pathLike) {
            Optional<DocumentDocument> byPath = documentService.findByPath(
                    ctx.tenantId(), projectName, ref);
            if (byPath.isPresent()) return byPath.get();
        }
        throw new ToolException(
                "Source document '" + ref + "' not found in project '"
                        + projectName + "'");
    }

    private RecordsDocument parseRecords(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!RecordsCodec.supports(mime)) {
            throw new ToolException(
                    "Source document '" + doc.getPath()
                            + "' has mime '" + mime
                            + "' which the records codec doesn't "
                            + "support. Use a markdown / json / yaml "
                            + "records document.");
        }
        try {
            return RecordsCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse source records document: "
                            + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) return documentService.readContent(doc);
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read source document content: "
                            + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private @Nullable ThinkProcessDocument loadProcess(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) return null;
        Optional<ThinkProcessDocument> opt = thinkProcessService.findById(ctx.processId());
        return opt.orElse(null);
    }

    private void emit(@Nullable ThinkProcessDocument process,
                      StatusTag tag, String text) {
        if (process == null) return;
        progressEmitter.emitStatus(process, tag, text);
    }

    static String defaultOutputPath(@Nullable String title) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = title == null || title.isBlank()
                ? "records"
                : ReportFromMarkdownTool.slug(title);
        return "reports/" + slug + "-" + stamp + ".xlsx";
    }

    private static String leafName(String path) {
        if (path == null) return "records";
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> paramStringList(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (!(v instanceof List<?> list)) return null;
        List<String> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            else if (o != null) out.add(o.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<Map<String, Object>> paramMapList(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (!(v instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) {
                        coerced.put(e.getKey().toString(), e.getValue());
                    }
                }
                out.add(coerced);
            }
        }
        return out;
    }
}
