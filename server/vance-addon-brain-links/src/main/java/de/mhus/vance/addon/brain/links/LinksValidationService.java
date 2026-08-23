package de.mhus.vance.addon.brain.links;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationResult;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Static self-check of a links manifest.
 *
 * <p><b>Why this exists at all.</b> Reading a manifest is deliberately lenient:
 * a row without a usable URL is skipped so one typo cannot take down the app you
 * would fix the typo in ({@link LinksConfig#from}). That is right for rendering
 * and wrong for authoring — an agent that writes a bad URL into {@code _app.yaml}
 * with the generic document tools gets no complaint, the entry simply is not
 * there. So this service reads the <b>raw</b> YAML and reports precisely what the
 * lenient reader threw away.
 *
 * <p>Every check below therefore has the same shape: it names something the
 * normal load path does <em>silently</em>. A validator that also flagged
 * harmless things (an undeclared group, say — {@code orderedGroups()} appends it
 * and everything works) would train its reader to ignore it.
 *
 * <p>Advisory only, like every other validator in the tree: findings never
 * block a write. Envelope is the shared
 * {@code { target, ok, errors, warnings, findings[] }}.
 */
@Service
public class LinksValidationService {

    private final DocumentService documentService;

    public LinksValidationService(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** Check the manifest of an existing links app (post-write self-check). */
    public KindValidationResult validateFolder(String tenantId, String projectId, String folder) {
        String normalised = LinksStore.normaliseFolder(folder);
        String path = LinksStore.manifestPath(normalised);
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, path);
        if (doc.isEmpty()) {
            return new KindValidationResult(path, List.of(Finding.error(
                    path, "manifest-missing", "No links manifest at '" + path + "'.")));
        }
        String body;
        try {
            body = documentService.readContent(doc.get());
        } catch (RuntimeException e) {
            return new KindValidationResult(path, List.of(Finding.error(
                    path, "unreadable", "Could not read '" + path + "': " + e.getMessage())));
        }
        return new KindValidationResult(path, findings(body));
    }

    /** Check content the agent is about to write (pre-write self-check). */
    public KindValidationResult validateContent(String content) {
        return new KindValidationResult("(content)", findings(content));
    }

    // ── the checks ────────────────────────────────────────────────

    List<Finding> findings(@Nullable String body) {
        List<Finding> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            out.add(Finding.error("(document)", "empty", "The manifest is empty."));
            return out;
        }

        Object parsed;
        try {
            parsed = new Yaml().load(body);
        } catch (RuntimeException e) {
            // A parse error is the one case the app itself also cannot survive,
            // so it is the only finding worth reporting — everything below would
            // be guesswork on a broken tree.
            out.add(Finding.error("(document)", "yaml-broken",
                    "Not valid YAML: " + oneLine(e.getMessage())));
            return out;
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            out.add(Finding.error("(document)", "not-a-mapping",
                    "The manifest must be a YAML mapping at the top level."));
            return out;
        }

        checkMeta(root, out);
        Object blockRaw = root.get(LinksConfig.BLOCK);
        if (blockRaw == null) {
            out.add(Finding.warning("links", "block-missing",
                    "No `links:` block — the app opens empty. Add `links: { entries: [] }`."));
            return out;
        }
        if (!(blockRaw instanceof Map<?, ?> block)) {
            out.add(Finding.error("links", "block-not-a-mapping",
                    "`links:` must be a mapping, not " + typeName(blockRaw)
                            + ". The whole block is ignored as written."));
            return out;
        }

        checkGroups(block.get("groups"), out);
        checkEntries(block.get("entries"), out);
        checkIndex(block.get("index"), out);
        return out;
    }

    private static void checkMeta(Map<?, ?> root, List<Finding> out) {
        Object metaRaw = root.get("$meta");
        if (!(metaRaw instanceof Map<?, ?> meta)) {
            out.add(Finding.error("$meta", "meta-missing",
                    "No `$meta` header. A links app needs "
                            + "`$meta: { kind: application, app: links }`."));
            return;
        }
        String kind = str(meta.get("kind"));
        String app = str(meta.get("app"));
        if (kind == null || !kind.equalsIgnoreCase("application")) {
            out.add(Finding.error("$meta.kind", "wrong-kind",
                    "`$meta.kind` must be `application`, found "
                            + (kind == null ? "nothing" : "`" + kind + "`") + "."));
        }
        if (app == null || !app.equalsIgnoreCase(LinksConfig.BLOCK)) {
            out.add(Finding.error("$meta.app", "wrong-app",
                    "`$meta.app` must be `links`, found "
                            + (app == null ? "nothing" : "`" + app + "`")
                            + " — the links app will not open this folder."));
        }
    }

    private static void checkGroups(@Nullable Object raw, List<Finding> out) {
        if (raw == null) return;
        if (!(raw instanceof List<?> list)) {
            out.add(Finding.warning("links.groups", "groups-not-a-list",
                    "`groups` must be a list of names; " + typeName(raw)
                            + " is ignored, so declared group order is lost."));
            return;
        }
        SequencedSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String name = str(list.get(i));
            String loc = "links.groups[" + i + "]";
            if (name == null) {
                out.add(Finding.warning(loc, "group-not-a-name",
                        "Not a non-empty string — this heading is dropped when read."));
            } else if (!seen.add(name)) {
                out.add(Finding.warning(loc, "group-duplicate",
                        "`" + name + "` is declared twice; the second one is dropped."));
            }
        }
    }

    private static void checkEntries(@Nullable Object raw, List<Finding> out) {
        if (raw == null) {
            out.add(Finding.warning("links.entries", "entries-missing",
                    "No `entries` — the list is empty."));
            return;
        }
        if (!(raw instanceof List<?> list)) {
            out.add(Finding.error("links.entries", "entries-not-a-list",
                    "`entries` must be a list; " + typeName(raw)
                            + " means every link is ignored."));
            return;
        }

        SequencedSet<String> seenUrls = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String loc = "links.entries[" + i + "]";
            Object row = list.get(i);

            if (row instanceof String bare) {
                checkUrl(bare, loc, seenUrls, out);
                continue;
            }
            if (!(row instanceof Map<?, ?> map)) {
                out.add(Finding.error(loc, "entry-not-a-mapping",
                        "An entry must be a mapping (or a bare URL string); "
                                + typeName(row) + " is dropped when read."));
                continue;
            }
            Object urlRaw = map.get("url");
            if (urlRaw == null) {
                out.add(Finding.error(loc, "url-missing",
                        "No `url` — this entry is dropped when read."));
            } else if (str(urlRaw) == null) {
                out.add(Finding.error(loc, "url-not-a-string",
                        "`url` must be a non-empty string; " + typeName(urlRaw)
                                + " is dropped when read."));
            } else {
                checkUrl(str(urlRaw), loc, seenUrls, out);
            }

            // Fields the reader silently ignores when they are the wrong type.
            for (String field : List.of("title", "teaser", "group", "note", "image")) {
                Object v = map.get(field);
                if (v != null && str(v) == null) {
                    out.add(Finding.warning(loc + "." + field, "field-ignored",
                            "`" + field + "` must be a non-empty string; "
                                    + typeName(v) + " is ignored when read."));
                }
            }
            Object tags = map.get("tags");
            if (tags != null && !(tags instanceof List<?>)) {
                out.add(Finding.warning(loc + ".tags", "tags-not-a-list",
                        "`tags` must be a list; " + typeName(tags)
                                + " is ignored when read."));
            }
            // A picture the browser will refuse to load renders as nothing, and
            // "no picture" is indistinguishable from "the page has none".
            String image = str(map.get("image"));
            if (image != null && !LinkUrls.isHttp(image)) {
                out.add(Finding.warning(loc + ".image", "image-not-http",
                        "`image` is not an http(s) URL, so nothing is shown. "
                                + "Leave it out to use the page's own preview picture."));
            }
            Object addedAt = map.get("addedAt");
            if (addedAt instanceof String stamp && !stamp.isBlank()) {
                try {
                    java.time.Instant.parse(stamp.trim());
                } catch (RuntimeException e) {
                    out.add(Finding.warning(loc + ".addedAt", "addedAt-unreadable",
                            "Not an ISO-8601 instant (e.g. 2026-08-21T08:00:00Z); "
                                    + "the sort key is lost."));
                }
            }
        }
    }

    /**
     * The URL, against the same rule the app addresses entries by. Two entries
     * that normalise to one string are worth an error rather than a warning:
     * remove and update resolve by URL and would only ever reach the first, so
     * the second is unreachable through every tool and the whole UI.
     */
    private static void checkUrl(@Nullable String url, String loc,
                                 SequencedSet<String> seen, List<Finding> out) {
        if (url == null) return;
        String normalised;
        try {
            normalised = LinkUrls.normalise(url);
        } catch (RuntimeException e) {
            out.add(Finding.error(loc + ".url", "url-unusable",
                    "`" + url + "` is dropped when read: " + oneLine(e.getMessage())));
            return;
        }
        if (!seen.add(normalised)) {
            out.add(Finding.error(loc + ".url", "url-duplicate",
                    "`" + normalised + "` is already in this list. The later entry "
                            + "cannot be edited or removed — both resolve to the first."));
        }
    }

    private static void checkIndex(@Nullable Object raw, List<Finding> out) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> index)) {
            out.add(Finding.warning("links.index", "index-not-a-mapping",
                    "`index` must be a mapping like `{ outputPath: _index.md }`; "
                            + typeName(raw) + " is ignored."));
            return;
        }
        String out1 = str(index.get("outputPath"));
        if (out1 == null) return;
        if (out1.startsWith("/")) {
            out.add(Finding.warning("links.index.outputPath", "index-absolute",
                    "A leading slash makes the index a project-root path, not a file "
                            + "inside the app folder."));
        }
        if (out1.contains("..")) {
            out.add(Finding.warning("links.index.outputPath", "index-escapes",
                    "`..` writes the generated index outside the app folder."));
        }
    }

    // ── helpers ───────────────────────────────────────────────────

    private static @Nullable String str(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String typeName(@Nullable Object v) {
        if (v == null) return "nothing";
        if (v instanceof List<?>) return "a list";
        if (v instanceof Map<?, ?>) return "a mapping";
        if (v instanceof Number) return "a number";
        if (v instanceof Boolean) return "a boolean";
        return "a " + v.getClass().getSimpleName();
    }

    private static String oneLine(@Nullable String s) {
        if (s == null) return "no reason given";
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() > 200 ? v.substring(0, 200) + "…" : v;
    }

    /** Guard so a caller cannot ask for both surfaces at once. */
    public static void requireExactlyOne(@Nullable String folder, @Nullable String content) {
        boolean hasFolder = folder != null && !folder.isBlank();
        boolean hasContent = content != null && !content.isBlank();
        if (hasFolder == hasContent) {
            throw new ToolException("Give exactly one of 'folder' (check a saved app) "
                    + "or 'content' (check what you are about to write).");
        }
    }
}
