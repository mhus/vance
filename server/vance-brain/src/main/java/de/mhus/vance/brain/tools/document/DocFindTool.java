package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.tools.workspace.GlobMatchers;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Find documents by metadata: case-insensitive substring over path, name,
 * title and tags, plus the file_find-style filters — path glob, size range
 * and creation-time range — all AND-combined. Returns the same metadata
 * shape as {@code doc_list} so Eddie can pipe a hit straight into
 * {@code doc_read(id=...)}.
 *
 * <p>Everything here runs on the {@code listByProject} rows alone: zero
 * body I/O, one bounded Mongo query — the cheap "I know roughly what it's
 * called" entry-point. For content search use {@code doc_grep_path}; for
 * semantic search the project's RAG namespaces exist separately
 * ({@code rag_query}).
 *
 * <p>There is no {@code modifiedAfter/Before} (unlike {@code file_find}):
 * document rows track {@code createdAt} but no modification timestamp, so
 * the creation-time range is the honest equivalent that exists.
 */
@Component
@RequiredArgsConstructor
public class DocFindTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "projectId",
                Map.of(
                        "type", "string",
                        "description", "Optional project name. Defaults to the active project."));
        p.put(
                "query",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Case-insensitive substring to match against path, name, "
                                + "title, or tag. Optional when pathGlob is given; combined with "
                                + "all other filters (AND)."));
        p.put(
                "pathPrefix",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Path-prefix scope. Omitted → defaults to 'documents/' "
                                + "(excludes trash, kit config, chat attachments, engine scratch, "
                                + "and other system folders). Pass '*' to search the entire project. "
                                + "Any specific prefix overrides both."));
        p.put(
                "pathGlob",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Glob matched against the document path relative to "
                                + "pathPrefix, e.g. '**/*.md' or 'notes/2026/*.md'. Default: no "
                                + "glob filter."));
        p.put(
                "minSizeBytes",
                Map.of("type", "integer", "description", "Skip documents smaller than this. Default: no lower bound."));
        p.put(
                "maxSizeBytes",
                Map.of("type", "integer", "description", "Skip documents larger than this. Default: no upper bound."));
        p.put(
                "createdAfter",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "ISO-8601 instant — only documents created strictly after. "
                                + "Default: no lower bound. Documents track no modification time; "
                                + "creation is the equivalent that exists."));
        p.put(
                "createdBefore",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "ISO-8601 instant — only documents created strictly before. " + "Default: no upper bound."));
        p.put(
                "sortBy",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("path", "size", "created"),
                        "description",
                        "Sort key. 'path' (default, ascending), 'size' (descending), " + "'created' (newest first)."));
        p.put(
                "limit",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Max number of hits (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ")."));
        return p;
    }

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_find";
    }

    @Override
    public String description() {
        return "Find documents in a project by case-insensitive substring match against "
                + "path / name / title / tags, optionally narrowed by path glob, size range "
                + "and creation-time range (all AND-combined). Use this when you know roughly "
                + "what the doc is called. For content search use doc_grep_path; for semantic "
                + "search use rag_query.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only", "document");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawQuery = params == null ? null : params.get("query");
        String query = rawQuery instanceof String s && !s.isBlank() ? s : null;
        String needle = query == null ? null : query.trim().toLowerCase(Locale.ROOT);
        int limit = clampLimit(params == null ? null : params.get("limit"));
        Object rawPrefix = params == null ? null : params.get("pathPrefix");
        String pathPrefix = DocumentService.resolveScope(rawPrefix instanceof String s && !s.isBlank() ? s : null);

        String pathGlob = paramString(params, "pathGlob");
        PathMatcher glob = GlobMatchers.buildGlobMatcher(pathGlob);
        Long minSize = paramLong(params, "minSizeBytes");
        Long maxSize = paramLong(params, "maxSizeBytes");
        Instant createdAfter = parseInstant(params, "createdAfter");
        Instant createdBefore = parseInstant(params, "createdBefore");
        String sortBy = paramString(params, "sortBy");

        if (needle == null && glob == null) {
            throw new ToolException("Provide at least 'query' or 'pathGlob' — a find without "
                    + "any criteria would return arbitrary documents");
        }

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        List<DocumentDocument> all = documentService.listByProject(ctx.tenantId(), project.getName());

        List<DocumentDocument> matched = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (!pathPrefix.isEmpty() && (d.getPath() == null || !d.getPath().startsWith(pathPrefix))) continue;
            if (needle != null && !matches(d, needle)) continue;
            if (glob != null && !globMatches(glob, pathPrefix, d.getPath())) continue;
            if (minSize != null && d.getSize() < minSize) continue;
            if (maxSize != null && d.getSize() > maxSize) continue;
            if (createdAfter != null
                    && (d.getCreatedAt() == null || !d.getCreatedAt().isAfter(createdAfter))) continue;
            if (createdBefore != null
                    && (d.getCreatedAt() == null || !d.getCreatedAt().isBefore(createdBefore))) continue;
            matched.add(d);
        }

        Comparator<DocumentDocument> order = "size".equals(sortBy)
                ? Comparator.comparingLong(DocumentDocument::getSize).reversed()
                : "created".equals(sortBy)
                        ? Comparator.comparing(
                                        DocumentDocument::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                        : Comparator.comparing(DocumentDocument::getPath);
        matched.sort(order);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentDocument d : matched) {
            if (rows.size() >= limit) break;
            rows.add(rowOf(d));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        if (query != null) out.put("query", query);
        if (pathGlob != null) out.put("pathGlob", pathGlob);
        out.put("hits", rows);
        out.put("count", rows.size());
        out.put("truncated", matched.size() > rows.size());
        return out;
    }

    private static Map<String, Object> rowOf(DocumentDocument d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        row.put("path", d.getPath());
        row.put("name", d.getName());
        if (d.getTitle() != null) row.put("title", d.getTitle());
        if (d.getMimeType() != null) row.put("mimeType", d.getMimeType());
        row.put("size", d.getSize());
        if (d.getCreatedAt() != null) row.put("createdAt", d.getCreatedAt().toString());
        if (d.getTags() != null && !d.getTags().isEmpty()) {
            row.put("tags", d.getTags());
        }
        return row;
    }

    /**
     * Glob semantics mirror file_find: the glob judges the path
     * <em>relative to the scope prefix</em>, so a <code>**&#47;*.md</code> glob
     * means "markdown anywhere under the scope". Under '*' the full path is the relative
     * path.
     */
    private static boolean globMatches(PathMatcher glob, String pathPrefix, @Nullable String docPath) {
        if (docPath == null) return false;
        String relative = pathPrefix.isEmpty() || !docPath.startsWith(pathPrefix)
                ? docPath
                : docPath.substring(pathPrefix.length());
        while (relative.startsWith("/")) relative = relative.substring(1);
        return glob.matches(Path.of(relative));
    }

    private static boolean matches(DocumentDocument d, String needle) {
        if (containsCi(d.getPath(), needle)) return true;
        if (containsCi(d.getName(), needle)) return true;
        if (containsCi(d.getTitle(), needle)) return true;
        if (d.getTags() != null) {
            for (String tag : d.getTags()) {
                if (containsCi(tag, needle)) return true;
            }
        }
        return false;
    }

    private static boolean containsCi(@Nullable String haystack, String needleLc) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLc);
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static @Nullable Long paramLong(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Instant parseInstant(@Nullable Map<String, Object> params, String key) {
        String raw = paramString(params, key);
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ToolException(
                    "'" + key + "' must be an ISO-8601 instant (e.g. '2026-01-31T12:00:00Z'): " + e.getMessage(), e);
        }
    }

    private static int clampLimit(@Nullable Object raw) {
        int n = DEFAULT_LIMIT;
        if (raw instanceof Number number) n = number.intValue();
        else if (raw instanceof String s) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (n < 1) return 1;
        return Math.min(n, MAX_LIMIT);
    }
}
