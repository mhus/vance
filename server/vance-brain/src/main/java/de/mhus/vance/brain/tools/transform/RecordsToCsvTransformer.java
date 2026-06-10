package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code kind: records} → CSV (RFC-4180). Lightweight tabular
 * export for tools that don't speak XLSX: spreadsheet imports,
 * shell-pipelines, database loaders. Output is UTF-8 with a BOM
 * — Excel needs the BOM to detect the encoding when opening a CSV
 * directly, otherwise umlauts get mojibaked.
 *
 * <p>No external library: the quoting rules are simple (RFC-4180
 * §2), the file is line-oriented. Bringing in Apache Commons CSV
 * for this would be a 250 KB dependency for ~30 lines of code.
 */
@Component
@RequiredArgsConstructor
public class RecordsToCsvTransformer implements DocumentTransformer {

    private static final byte[] UTF8_BOM = new byte[]{
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final DocumentService documentService;

    @Override public String targetFormat()    { return "csv"; }
    @Override public String targetMimeType()  { return "text/csv"; }
    @Override public String targetExtension() { return "csv"; }

    @Override
    public boolean canTransform(DocumentDocument source) {
        return RecordsCodec.supports(source.getMimeType())
                && "records".equalsIgnoreCase(source.getKind());
    }

    @Override
    public Result transform(DocumentDocument source, String title) {
        RecordsDocument records;
        try {
            records = RecordsCodec.parse(loadAsText(source), source.getMimeType());
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse source records document: "
                            + e.getMessage());
        }
        if (records.schema().isEmpty()) {
            throw new ToolException(
                    "Source records document has no schema — "
                            + "nothing to export.");
        }
        byte[] body = render(records).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return new Result(out, title);
    }

    /** Build the CSV body without a BOM. Package-private for tests. */
    static String render(RecordsDocument records) {
        StringBuilder sb = new StringBuilder();
        List<String> schema = records.schema();
        // Header
        for (int i = 0; i < schema.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteIfNeeded(schema.get(i)));
        }
        sb.append("\r\n");
        // Body
        for (RecordsItem item : records.items()) {
            for (int i = 0; i < schema.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quoteIfNeeded(
                        item.values().getOrDefault(schema.get(i), "")));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /**
     * RFC-4180 quoting:
     * <ul>
     *   <li>Quote if the value contains {@code , " \r \n} or starts/ends with whitespace.</li>
     *   <li>Inside a quoted value, {@code "} is doubled to {@code ""}.</li>
     *   <li>Empty values stay empty (not {@code ""}).</li>
     * </ul>
     */
    static String quoteIfNeeded(String v) {
        if (v == null || v.isEmpty()) return "";
        boolean needsQuote = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote && (v.charAt(0) == ' '
                || v.charAt(v.length() - 1) == ' ')) {
            needsQuote = true;
        }
        if (!needsQuote) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
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
}
