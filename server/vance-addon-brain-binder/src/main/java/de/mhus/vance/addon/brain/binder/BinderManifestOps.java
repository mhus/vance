package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read-modify-write mutations on a binder {@code _app.yaml} manifest —
 * add / remove / reorder / re-section / set-landing. Every mutation
 * loads the manifest, edits the typed {@code binder.entries} list, and
 * writes the whole block back through {@link ApplicationCodec} +
 * {@link DocumentService} (never a partial YAML patch).
 *
 * <p>Entry identity is the resolved project path
 * ({@link BinderResolver#stripToPath}): a ref and its stored canonical
 * form compare equal even if one carries a {@code ?kind=} query and the
 * other doesn't.
 */
@Component
public class BinderManifestOps {

    private static final String YAML_MIME = "application/yaml";
    private static final List<String> KINDS = List.of("application", "binder");

    private final DocumentService documentService;
    private final BinderResolver resolver;
    private final SecurityContextFactory contextFactory;

    public BinderManifestOps(DocumentService documentService,
                             BinderResolver resolver,
                             SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.resolver = resolver;
        this.contextFactory = contextFactory;
    }

    /** Anchor a document. Idempotent on the target path (no duplicates). */
    public void addEntry(String tenantId, String projectId, String folder,
                         String ref, @Nullable String section, @Nullable String title,
                         @Nullable String userId) {
        BinderResolver.Scan scan = resolver.scan(tenantId, projectId, folder);
        String path = BinderResolver.stripToPath(ref);
        // Validate the target exists — anchoring a phantom is almost always a typo.
        BinderResolver.ResolvedEntry probe =
                resolver.resolve(tenantId, projectId, new BinderEntry(ref, null, null));
        if (!probe.exists()) {
            throw new ToolException("No document at '" + path + "' — nothing to anchor.");
        }
        List<BinderEntry> entries = new ArrayList<>(scan.config().entries());
        boolean already = entries.stream()
                .anyMatch(e -> BinderResolver.stripToPath(e.ref()).equals(path));
        if (!already) {
            entries.add(new BinderEntry(
                    BinderResolver.canonicalRef(probe.path(), probe.kind()), section, title));
        }
        persist(scan, entries, scan.config().landingRef());
    }

    /** Remove the entry that resolves to the given ref's path. */
    public void removeEntry(String tenantId, String projectId, String folder,
                            String ref, @Nullable String userId) {
        BinderResolver.Scan scan = resolver.scan(tenantId, projectId, folder);
        String path = BinderResolver.stripToPath(ref);
        List<BinderEntry> entries = new ArrayList<>(scan.config().entries());
        entries.removeIf(e -> BinderResolver.stripToPath(e.ref()).equals(path));
        String landing = scan.config().landingRef();
        if (landing != null && BinderResolver.stripToPath(landing).equals(path)) {
            landing = null;
        }
        persist(scan, entries, landing);
    }

    /** Reorder entries to match {@code orderedRefs}; unknown refs kept at tail. */
    public void reorder(String tenantId, String projectId, String folder,
                        List<String> orderedRefs, @Nullable String userId) {
        BinderResolver.Scan scan = resolver.scan(tenantId, projectId, folder);
        List<BinderEntry> current = new ArrayList<>(scan.config().entries());
        List<BinderEntry> ordered = new ArrayList<>();
        for (String ref : orderedRefs) {
            String path = BinderResolver.stripToPath(ref);
            current.stream()
                    .filter(e -> BinderResolver.stripToPath(e.ref()).equals(path))
                    .findFirst()
                    .ifPresent(e -> { ordered.add(e); current.remove(e); });
        }
        ordered.addAll(current); // any not listed keep original relative order
        persist(scan, ordered, scan.config().landingRef());
    }

    /**
     * Set the section (always applied; blank clears) and optionally the
     * display-title of the matching entry. A {@code null} {@code title}
     * PRESERVES the existing title override — only a non-null value sets
     * or (when blank) clears it. So a pure section-move keeps the title,
     * and a rename passes the current section plus the new title.
     */
    public void setSection(String tenantId, String projectId, String folder,
                           String ref, @Nullable String section, @Nullable String title,
                           @Nullable String userId) {
        BinderResolver.Scan scan = resolver.scan(tenantId, projectId, folder);
        String path = BinderResolver.stripToPath(ref);
        List<BinderEntry> entries = new ArrayList<>();
        boolean found = false;
        for (BinderEntry e : scan.config().entries()) {
            if (BinderResolver.stripToPath(e.ref()).equals(path)) {
                String nextTitle = title != null ? blankToNull(title) : e.title();
                entries.add(new BinderEntry(e.ref(), blankToNull(section), nextTitle));
                found = true;
            } else {
                entries.add(e);
            }
        }
        if (!found) throw new ToolException("No binder entry for '" + path + "'.");
        persist(scan, entries, scan.config().landingRef());
    }

    /** Set (or clear, when {@code ref} is null/blank) the landing ref. */
    public void setLanding(String tenantId, String projectId, String folder,
                           @Nullable String ref, @Nullable String userId) {
        BinderResolver.Scan scan = resolver.scan(tenantId, projectId, folder);
        String landing = null;
        if (ref != null && !ref.isBlank()) {
            String path = BinderResolver.stripToPath(ref);
            boolean known = scan.config().entries().stream()
                    .anyMatch(e -> BinderResolver.stripToPath(e.ref()).equals(path));
            if (!known) throw new ToolException("Landing ref '" + path + "' is not an entry.");
            landing = BinderResolver.canonicalRef(path, null);
        }
        persist(scan, scan.config().entries(), landing);
    }

    // ── persistence ───────────────────────────────────────────────

    private void persist(BinderResolver.Scan scan, List<BinderEntry> entries,
                         @Nullable String landingRef) {
        ApplicationDocument doc = scan.manifestDoc();
        Map<String, Object> config = new LinkedHashMap<>(doc.config());
        config.put(BinderConfig.APP_NAME,
                buildBlock(landingRef, entries, scan.config().indexOutputPath()));
        ApplicationDocument next = new ApplicationDocument(
                "application", BinderConfig.APP_NAME, doc.title(), doc.description(),
                config, doc.extra());
        String body = ApplicationCodec.serialize(next, YAML_MIME);
        DocumentDocument manifest = scan.manifest();
        documentService.update(manifest.getId(),
                manifest.getTitle(), KINDS, body, null,
                null, null, null, YAML_MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(manifest.getTenantId(), null, manifest.getPath()));
    }

    static Map<String, Object> buildBlock(@Nullable String landingRef,
                                           List<BinderEntry> entries,
                                           String indexOutputPath) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (landingRef != null && !landingRef.isBlank()) block.put("landingRef", landingRef);
        List<Map<String, Object>> list = new ArrayList<>();
        for (BinderEntry e : entries) list.add(e.toMap());
        block.put("entries", list);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("outputPath", indexOutputPath);
        block.put("index", index);
        return block;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
