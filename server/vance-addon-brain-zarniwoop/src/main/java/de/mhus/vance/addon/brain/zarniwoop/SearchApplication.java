package de.mhus.vance.addon.brain.zarniwoop;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: search} — a search surface for people.
 *
 * <p><b>The manifest carries only the shape of the surface</b> (§8 of
 * {@code planning/zarniwoop-search-app.md}): which modality it opens on, how
 * many hits, and searches somebody wants back. Not results — those come from a
 * foreign index and are stale the moment they are written, and a materialised
 * copy of a search would be a second, wrong archive.
 *
 * <p>Which raises the obvious question: why be an application at all, when
 * nothing is stored? Two reasons. The kind registry is how an addon surface
 * reaches a Cortex tab, and a search that keeps nothing is un-Vance-like — the
 * clip path (a later phase) is how a hit becomes a document, and it needs
 * somewhere to belong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchApplication implements VanceApplication {

    public static final String APP_NAME = "search";

    private static final String YAML_MIME = "application/yaml";

    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;

    @Override
    public String appName() {
        return APP_NAME;
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() == null ? Map.of() : ctx.params();
        String manifestPath = folder + "/" + APP_MANIFEST;

        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '" + manifestPath
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        SearchConfig config = new SearchConfig(
                SearchConfig.modality(params.get("defaultModality")),
                params.get("defaultNum") instanceof Number n ? n.intValue() : 0,
                List.of());

        Map<String, Object> configBlock = new LinkedHashMap<>();
        configBlock.put(SearchConfig.BLOCK, config.toBlock());

        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description, configBlock, new LinkedHashMap<>());

        DocumentDocument stored = write(ctx.tenantId(), ctx.projectName(), manifestPath,
                title == null ? "Search" : title,
                ApplicationCodec.serialize(manifest, YAML_MIME),
                existing.orElse(null), ctx.userId());

        log.info("SearchApplication.create tenant='{}' folder='{}' modality={}",
                ctx.tenantId(), folder, config.defaultModality());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("defaultModality", config.defaultModality().name().toLowerCase(Locale.ROOT));

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), List.of(),
                "Search ready. Open it and type — the modalities on offer follow the "
                        + "providers configured in research.endpoint.*.",
                stats);
    }

    /**
     * No artefacts, by design — the same reason as the feeds app: everything
     * shown lives at the source and there is nothing derived to regenerate.
     * Implemented rather than left out so the generic {@code app_rebuild} tool
     * answers cleanly instead of failing.
     */
    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = normaliseFolder(ctx.folder());
        log.debug("SearchApplication.refresh tenant='{}' folder='{}' (nothing derived)",
                ctx.tenantId(), folder);
        return new RefreshResult(APP_NAME, folder, List.of());
    }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🔍", null);
    }

    /**
     * Configured shape only — deliberately no search. A dashboard card that ran
     * a query would spend provider quota on opening a desktop, and it would show
     * whatever that query happened to return today.
     */
    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        SearchConfig config = readConfig(
                ctx.tenantId(), ctx.projectName(), normaliseFolder(ctx.folder()));
        List<StatusMetric> metrics = new ArrayList<>();
        metrics.add(new StatusMetric("Modality",
                config.defaultModality().name().toLowerCase(Locale.ROOT)));
        if (!config.savedSearches().isEmpty()) {
            metrics.add(new StatusMetric("Saved",
                    String.valueOf(config.savedSearches().size())));
        }
        List<StatusItem> items = new ArrayList<>();
        for (SearchConfig.SavedSearch saved : config.savedSearches()) {
            items.add(new StatusItem(saved.name(), saved.query(), null, null));
        }
        return Optional.of(new AppStatus(
                config.savedSearches().isEmpty()
                        ? "Ready"
                        : config.savedSearches().size() + " saved search(es)",
                null, metrics, items, null));
    }

    /**
     * What the person is looking at, so the chat engine can answer "what is this"
     * without guessing. Configuration only — never results, which change between
     * the prompt and the answer.
     */
    @Override
    public @Nullable String promptInject(PromptInjectContext ctx) {
        SearchConfig config = readConfig(
                ctx.tenantId(), ctx.projectName(), normaliseFolder(ctx.folder()));
        StringBuilder sb = new StringBuilder("Open search surface, default modality ")
                .append(config.defaultModality().name().toLowerCase(Locale.ROOT))
                .append(".\n");
        if (!config.savedSearches().isEmpty()) {
            sb.append("Saved searches:\n");
            for (SearchConfig.SavedSearch saved : config.savedSearches()) {
                sb.append("- ").append(saved.name()).append(": ").append(saved.query())
                        .append(" (").append(saved.modality().name().toLowerCase(Locale.ROOT))
                        .append(")\n");
            }
        }
        // What the reader has open, when they opened a hit. "Summarise the
        // one I have open" is a sentence the model can only act on if the
        // turn already said which — and asking the browser would block the
        // sampling loop on a tab that may be asleep.
        //
        // The URL is deliberately the payload. A search is stateless: a hit
        // has no handle on this side to fetch it back by, the way a feed
        // entry has an id in an archive. Its address is what it is.
        //
        // "open" was chosen over "selected" for a reason that turned out to be
        // only half enough: to a chat engine "selection" means a character range
        // in a document, so the word collides. But avoiding it is not sufficient
        // — the engine still hedged ("I have no context for an open search hit")
        // because nothing told it that this hint IS the answer to "which one do
        // I have open". Say what it is not, and forbid the hedge. Same fix in
        // the links and feeds apps.
        String selected = ctx.selection();
        if (selected != null && !selected.isBlank()) {
            sb.append("The reader has opened one hit in this list. This IS what they mean by "
                            + "\"this hit\", \"the open result\" or \"the one I marked\" — it is "
                            + "the app's own pick, NOT a text selection inside a document. Never "
                            + "answer that no selection arrived, and never ask them to mark it "
                            + "again: ")
                    .append(selected.trim()).append('\n')
                    .append("Read it with web_fetch on the URL — a search result is a "
                            + "pointer, not a document we hold.\n");
        }
        return sb.toString();
    }

    // ── shared with the controller ───────────────────────────────────

    SearchConfig readConfig(String tenantId, String projectName, String folder) {
        return SearchConfig.from(readManifest(tenantId, projectName, folder));
    }

    ApplicationDocument readManifest(String tenantId, String projectName, String folder) {
        String manifestPath = normaliseFolder(folder) + "/" + APP_MANIFEST;
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectName, manifestPath);
        if (doc.isEmpty()) {
            throw new ToolException("No search manifest at '" + manifestPath + "'");
        }
        return ApplicationCodec.parse(
                documentService.readContent(doc.get()), doc.get().getMimeType());
    }

    /** Replace the {@code config.search} block, keeping title and description. */
    DocumentDocument writeConfig(String tenantId, String projectName, String folder,
                                 SearchConfig config, @Nullable String userId) {
        String normalised = normaliseFolder(folder);
        String manifestPath = normalised + "/" + APP_MANIFEST;
        ApplicationDocument current = readManifest(tenantId, projectName, normalised);

        Map<String, Object> configBlock = new LinkedHashMap<>(current.config());
        configBlock.put(SearchConfig.BLOCK, config.toBlock());
        ApplicationDocument updated = new ApplicationDocument(
                "application", APP_NAME, current.title(), current.description(),
                configBlock, new LinkedHashMap<>(current.extra()));

        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectName, manifestPath);
        return write(tenantId, projectName, manifestPath,
                current.title() == null ? "Search" : current.title(),
                ApplicationCodec.serialize(updated, YAML_MIME),
                existing.orElse(null), userId);
    }

    static String normaliseFolder(@Nullable String folder) {
        if (folder == null) {
            throw new ToolException("folder is required");
        }
        String f = folder.trim();
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        if (f.isEmpty()) {
            throw new ToolException("folder must not be empty");
        }
        return f;
    }

    // ── internals ────────────────────────────────────────────────────

    private DocumentDocument write(String tenantId, String projectName, String path,
                                   String title, String body,
                                   @Nullable DocumentDocument existing,
                                   @Nullable String userId) {
        var actor = contextFactory.writeActor(tenantId, userId, path);
        if (existing != null) {
            return documentService.update(existing.getId(), title,
                    List.of("application", APP_NAME),
                    body, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY, actor);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(tenantId, projectName, path, title,
                    List.of("application", APP_NAME), YAML_MIME, in, userId, actor);
        } catch (IOException e) {
            throw new ToolException("Could not write manifest '" + path + "': " + e.getMessage());
        }
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
