package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Replaces exactly one occurrence of {@code oldText} with
 * {@code newText} inside a file on the foot host. Fails if the
 * snippet isn't found or matches more than once — the LLM is
 * expected to add surrounding context until the match is unique.
 *
 * <p>Same contract as the prototype's {@code editFile}: targeted
 * edits stay safe (no accidental multi-replace) and cheap (no need
 * to re-write the whole file).
 *
 * <p>Optional If-Match guard: pass the {@code contentHash} from the
 * last read as {@code expectedContentHash} and the edit is refused
 * when the file changed meanwhile — a stale match that would have
 * silently rewritten the wrong content becomes a readable error
 * instead. The result carries the {@code contentHash} of the updated
 * content so consecutive edits can chain without re-reading.
 */
@Component
public class ClientFileEditTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                                    Map.of(
                                            "type", "string",
                                            "description", "Absolute or working-dir relative file path."),
                            "oldText",
                                    Map.of(
                                            "type", "string",
                                            "description", "Exact snippet to replace. Whitespace-sensitive."),
                            "newText",
                                    Map.of(
                                            "type", "string",
                                            "description", "Replacement text."),
                            "expectedContentHash",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional If-Match guard: the contentHash "
                                                    + "from your last read of this file. The edit "
                                                    + "is refused when the file changed meanwhile.")),
            "required", List.of("path", "oldText", "newText"));

    @Override
    public String name() {
        return "client_file_edit";
    }

    @Override
    public String description() {
        return "Replace one occurrence of oldText with newText inside a file "
                + "on the foot host. Fails if oldText is not found or appears "
                + "more than once — add surrounding context until the match "
                + "is unique. Preferred over rewriting the whole file. Pass "
                + "the contentHash from your last file_read as "
                + "expectedContentHash to refuse the edit when the file "
                + "changed since that read.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Explicit CLIENT variant of file_edit — targets the user's machine (foot host) regardless of the work target. Prefer file_edit.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        // "client-file" tells the history-tagging hook to encode the
        // returned "path" as a CLIENT_FILE: resource key — see
        // planning/process-history-search.md §5.1.
        return java.util.Set.of("write", "side-effect", "client-file");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target — Foot must be connected. Match not unique = expand surrounding context; "
                + "contentHash mismatch = file changed since your read, read it again; "
                + "file missing = file_read first.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawPath = params == null ? null : params.get("path");
        Object rawOld = params == null ? null : params.get("oldText");
        Object rawNew = params == null ? null : params.get("newText");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("'path' is required");
        }
        if (!(rawOld instanceof String oldText) || oldText.isEmpty()) {
            throw new IllegalArgumentException("'oldText' is required");
        }
        if (!(rawNew instanceof String newText)) {
            throw new IllegalArgumentException("'newText' is required");
        }
        String expectedContentHash = expectedContentHashOrNull(params);
        Path p = ClientFilePaths.resolve(path);
        // Read and write are guarded separately, and the two match failures
        // are raised outside both: a blanket catch around the whole block
        // rewrote "oldText not found" into "Edit failed: <that message>" and,
        // worse, turned a NoSuchFileException into the bare path — see
        // ClientFilePaths.describeFailure.
        String content;
        try {
            content = Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(ClientFilePaths.describeFailure(p, e, "Edit"), e);
        }
        // If-Match before the snippet match: a stale file is the
        // precondition failure — telling the model to re-read is the
        // right advice, not "expand your snippet context".
        if (expectedContentHash != null) {
            String actual = ContentHashes.sha256Hex(content);
            if (!expectedContentHash.equals(actual)) {
                throw new IllegalArgumentException("File changed since it was read (contentHash mismatch: expected "
                        + ContentHashes.abbreviate(expectedContentHash)
                        + ", found " + ContentHashes.abbreviate(actual)
                        + ") — read the file again and retry with the "
                        + "current contentHash");
            }
        }
        int first = content.indexOf(oldText);
        if (first < 0) {
            throw new IllegalArgumentException(
                    "oldText not found in " + p.toAbsolutePath().normalize()
                            + " — read the file and copy the snippet verbatim "
                            + "(whitespace and indentation included)");
        }
        int second = content.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalArgumentException("oldText appears multiple times in "
                    + p.toAbsolutePath().normalize() + " — add surrounding context until the match is unique");
        }
        String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
        try {
            Files.writeString(p, updated, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(ClientFilePaths.describeFailure(p, e, "Edit"), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", p.toAbsolutePath().toString());
        out.put("replaced", 1);
        out.put("totalChars", updated.length());
        out.put("contentHash", ContentHashes.sha256Hex(updated));
        return out;
    }

    /**
     * Parses the optional If-Match guard: absent or {@code null} means
     * "no guard" (the pre-If-Match behaviour), a present value must be
     * a non-blank string — a blank one signals a confused caller and
     * is refused rather than silently ignored.
     */
    private static String expectedContentHashOrNull(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("expectedContentHash");
        if (raw == null) return null;
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("'expectedContentHash' must be a non-empty string — pass the "
                    + "contentHash from your last read, or omit it");
        }
        return s;
    }
}
