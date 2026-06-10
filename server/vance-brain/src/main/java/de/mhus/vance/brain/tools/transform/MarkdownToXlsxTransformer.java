package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.brain.tools.report.XlsxFromRecordsTool;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Converts a markdown document into an XLSX by extracting the
 * first GFM table found in the body. Header cells become the
 * schema, each body row becomes a record, the result rides
 * through {@link XlsxFromRecordsTool#render} so the sheet shape
 * matches the records-based path exactly.
 *
 * <p>Caveat: only the first table is exported in this iteration.
 * Markdown documents with multiple tables stay a future
 * extension — multi-sheet workbook support is a small step but
 * needs a clear "which table is sheet N" disambiguation
 * (heading? table caption? auto-numbered?).
 */
@Component
@RequiredArgsConstructor
public class MarkdownToXlsxTransformer implements DocumentTransformer {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create());

    private final Parser parser = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private final DocumentService documentService;

    @Override public String targetFormat()    { return "xlsx"; }
    @Override public String targetMimeType()  { return XLSX_MIME; }
    @Override public String targetExtension() { return "xlsx"; }

    @Override
    public boolean canTransform(DocumentDocument source) {
        String mime = source.getMimeType();
        if (mime == null) return false;
        String lower = mime.toLowerCase();
        return lower.equals("text/markdown") || lower.equals("text/x-markdown");
    }

    @Override
    public Result transform(DocumentDocument source, String title) {
        String md = loadAsText(source);
        TableBlock table = findFirstTable(parser.parse(md));
        if (table == null) {
            throw new ToolException(
                    "Markdown document '" + source.getPath()
                            + "' contains no table. Add a GFM-style "
                            + "Markdown table (header row + "
                            + "alignment row + body rows) or convert "
                            + "to a kind:records document first.");
        }
        RecordsDocument records = toRecords(table);
        if (records.schema().isEmpty()) {
            throw new ToolException(
                    "Markdown table has no columns — nothing to "
                            + "export.");
        }
        byte[] bytes = XlsxFromRecordsTool.render(records, title);
        return new Result(bytes, title);
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

    // ── Visitor helpers ──────────────────────────────────────────

    /** First TableBlock encountered in the AST, or {@code null}. */
    static TableBlock findFirstTable(Node root) {
        TableBlockCollector v = new TableBlockCollector();
        root.accept(v);
        return v.firstTable;
    }

    private static final class TableBlockCollector extends AbstractVisitor {
        TableBlock firstTable;

        @Override
        public void visit(CustomBlock customBlock) {
            if (firstTable != null) return;
            if (customBlock instanceof TableBlock tb) {
                firstTable = tb;
                return;
            }
            visitChildren(customBlock);
        }
    }

    /** Build a {@link RecordsDocument} from one GFM-tables
     *  {@link TableBlock}. Header row → schema (deduped + trimmed);
     *  body rows → items (one map per row, schema-keyed). */
    static RecordsDocument toRecords(TableBlock table) {
        List<String> schema = new ArrayList<>();
        List<RecordsItem> items = new ArrayList<>();

        for (Node n = table.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof TableHead head) {
                for (Node r = head.getFirstChild(); r != null; r = r.getNext()) {
                    if (r instanceof TableRow row) {
                        collectHeader(row, schema);
                        break;  // only one header row
                    }
                }
            }
        }
        for (Node n = table.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof TableBody body) {
                for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                    if (r instanceof TableRow row) {
                        items.add(collectRow(row, schema));
                    }
                }
            }
        }
        return new RecordsDocument("records", schema, items, new LinkedHashMap<>());
    }

    private static void collectHeader(TableRow row, List<String> schema) {
        int idx = 0;
        for (Node n = row.getFirstChild(); n != null; n = n.getNext()) {
            if (!(n instanceof TableCell cell)) continue;
            String name = plaintextOf(cell).trim();
            if (name.isEmpty()) name = "col_" + (idx + 1);
            // Dedup: append "_2", "_3" on repeat — POI doesn't
            // tolerate duplicate column names through autofilter.
            String unique = name;
            int dedup = 2;
            while (schema.contains(unique)) {
                unique = name + "_" + dedup++;
            }
            schema.add(unique);
            idx++;
        }
    }

    private static RecordsItem collectRow(TableRow row, List<String> schema) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> overflow = new ArrayList<>();
        int col = 0;
        for (Node n = row.getFirstChild(); n != null; n = n.getNext()) {
            if (!(n instanceof TableCell cell)) continue;
            String v = plaintextOf(cell).trim();
            if (col < schema.size()) {
                values.put(schema.get(col), v);
            } else {
                overflow.add(v);
            }
            col++;
        }
        return new RecordsItem(values, new LinkedHashMap<>(), overflow);
    }

    /** Flatten the inline content of a TableCell into plain text.
     *  Inline formatting (bold/italic/links) is intentionally
     *  dropped — XLSX cells are plain strings in this iteration. */
    private static String plaintextOf(Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text t)          { sb.append(t.getLiteral()); }
            @Override public void visit(Code c)          { sb.append(c.getLiteral()); }
            @Override public void visit(SoftLineBreak b) { sb.append(' '); }
        });
        return sb.toString();
    }
}
