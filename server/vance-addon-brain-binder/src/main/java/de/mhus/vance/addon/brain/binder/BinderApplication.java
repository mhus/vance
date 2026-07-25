package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: binder} folders — a
 * lightweight reference binder. The manifest holds an ordered,
 * section-grouped list of {@code vance:} refs to arbitrary project
 * documents; the app renders each per-kind read-only (via the host
 * embed component) with a deep-link into Cortex for editing.
 *
 * <p>Unlike workbook/canvasbook the list is NOT folder-derived — it is
 * an explicit anchored list in the manifest, so entries may point at
 * documents anywhere in the project. The only derived artefact is the
 * optional {@code _index.md} link list.
 *
 * <p>See {@code planning/app-binder.md}.
 */
@Service
@Slf4j
public class BinderApplication implements VanceApplication {

    public static final String APP_NAME = "binder";
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private final BinderResolver resolver;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;

    public BinderApplication(BinderResolver resolver,
                             DocumentService documentService,
                             DocumentLinkBuilder linkBuilder,
                             SecurityContextFactory contextFactory) {
        this.resolver = resolver;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in a binder at `" + ctx.folder() + "` — a curated list of "
                + "references to project documents (not a container that holds them). "
                + "Anchor a document with "
                + "`binder_entry_add(folder=\"" + ctx.folder() + "\", ref=\"vance:/<path>\")` "
                + "and detach one with `binder_entry_remove`. To CHANGE a document's content, "
                + "edit the target document directly with its own tools — the binder only "
                + "references it. `manual_read('app-binder')` for the data model.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = BinderResolver.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + APP_MANIFEST;

        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '" + manifestPath
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        String landingRef = asString(params.get("landingRef"));

        List<BinderEntry> entries = new ArrayList<>();
        if (params.get("entries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    BinderEntry e = BinderEntry.fromMap(m);
                    if (e != null) entries.add(canonicalise(ctx, e));
                } else if (o instanceof String s && !s.isBlank()) {
                    entries.add(canonicalise(ctx, new BinderEntry(s.trim(), null, null)));
                }
            }
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(APP_NAME, BinderManifestOps.buildBlock(
                landingRef, entries, BinderConfig.DEFAULT_INDEX));

        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description, config, new LinkedHashMap<>());
        String manifestBody = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(existing.get().getId(),
                    title != null ? title : "Binder",
                    List.of("application", "binder"),
                    manifestBody, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(ctx.tenantId(), ctx.projectName(), manifestPath,
                        title != null ? title : "Binder",
                        List.of("application", "binder"),
                        YAML_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        RefreshResult refresh = refresh(new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        log.info("BinderApplication.create tenant='{}' folder='{}' entries={}",
                ctx.tenantId(), folder, entries.size());

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("entryCount", entries.size());

        String nextStep = "Binder ready. Anchor documents with "
                + "`binder_entry_add(folder=\"" + folder + "\", ref=\"vance:/<path>\")`.";

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), refresh.artefacts(), nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = BinderResolver.normaliseFolder(ctx.folder());
        BinderResolver.Scan scan = resolver.scan(ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.manifestDoc().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        String indexBody = renderIndex(scan, title);
        String outputPath =
                BinderResolver.resolveOutputPath(folder, scan.config().indexOutputPath());
        DocumentDocument stored = writeArtefact(ctx, outputPath, indexBody, "Index — " + title);

        long missing = scan.entries().stream().filter(e -> !e.exists()).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entryCount", scan.entries().size());
        stats.put("missingCount", missing);
        ArtefactResult index = new ArtefactResult(
                "index", stored.getPath(), linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info("BinderApplication.refresh tenant='{}' folder='{}' entries={} missing={}",
                ctx.tenantId(), folder, scan.entries().size(), missing);
        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🗂️", null);
    }

    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        BinderResolver.Scan scan;
        try {
            scan = resolver.scan(ctx.tenantId(), ctx.projectName(), ctx.folder());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<BinderResolver.ResolvedEntry> entries = scan.entries();
        long missing = entries.stream().filter(x -> !x.exists()).count();

        List<StatusItem> items = new ArrayList<>();
        for (BinderResolver.ResolvedEntry e : entries) {
            if (items.size() >= 8) break;
            StatusSeverity sev = e.exists() ? null : StatusSeverity.ATTENTION;
            items.add(new StatusItem(e.title(), e.kind(), sev, e.ref()));
        }

        String headline = entries.size() + (entries.size() == 1 ? " Dokument" : " Dokumente");
        StatusSeverity sev = missing > 0 ? StatusSeverity.ATTENTION : StatusSeverity.OK;
        List<StatusMetric> metrics = new ArrayList<>();
        metrics.add(new StatusMetric("Einträge", Integer.toString(entries.size())));
        if (missing > 0) metrics.add(new StatusMetric("Fehlend", Long.toString(missing)));

        return Optional.of(new AppStatus(headline, sev, metrics, items, null));
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Resolve an entry's ref against the store so it is stored canonically. */
    private BinderEntry canonicalise(CreateContext ctx, BinderEntry e) {
        BinderResolver.ResolvedEntry r =
                resolver.resolve(ctx.tenantId(), ctx.projectName(), e);
        return new BinderEntry(r.ref(), e.section(), e.title());
    }

    private static String renderIndex(BinderResolver.Scan scan, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(title)).append(" — Index\"\n");
        sb.append("description: \"Automatisch generiert aus Binder-Einträgen.\"\n");
        sb.append("---\n");
        sb.append("# ").append(title).append("\n\n");
        sb.append("```vance-callout\nseverity: note\ntitle: Auto-generiert\n")
                .append("body: Diese Seite wird bei jedem `app_rebuild` neu geschrieben — ")
                .append("Edits hier gehen verloren.\n```\n\n");
        if (scan.entries().isEmpty()) {
            sb.append("Noch keine Dokumente in diesem Binder.\n");
            return sb.toString();
        }
        // Group by section, "without section" leads.
        Map<String, List<BinderResolver.ResolvedEntry>> bySection = new LinkedHashMap<>();
        bySection.put("", new ArrayList<>());
        for (BinderResolver.ResolvedEntry e : scan.entries()) {
            String s = e.section() == null || e.section().isBlank() ? "" : e.section();
            bySection.computeIfAbsent(s, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<BinderResolver.ResolvedEntry>> group : bySection.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            if (!group.getKey().isEmpty()) sb.append("## ").append(group.getKey()).append("\n\n");
            for (BinderResolver.ResolvedEntry e : group.getValue()) {
                if (!e.exists()) {
                    sb.append("- ⚠ ").append(e.title()).append(" *(fehlt: `")
                            .append(e.path()).append("`)*\n");
                } else {
                    sb.append("- [").append(e.title()).append("](").append(e.ref()).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(),
                    title, List.of("binder", "generated", "index"),
                    body, null, null, null, null, MD_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(ctx.tenantId(), ctx.projectName(),
                    outputPath, title, List.of("binder", "generated", "index"),
                    MD_MIME, in, ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
