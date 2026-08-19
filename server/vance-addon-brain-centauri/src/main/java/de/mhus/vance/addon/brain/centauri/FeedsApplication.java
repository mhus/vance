package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.centauri.FeedStream;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: feeds} — the reading surface of
 * Centauri.
 *
 * <p><b>The manifest carries only configuration.</b> Unlike workbook or
 * canvasbook this app is not a container over documents: which streams to read
 * and how to filter them is all that is stored, and the content itself is
 * transient and remote. That is why {@link #refresh} produces no artefacts —
 * there is nothing derived to write, and inventing a materialised copy of a feed
 * would turn a reading surface into a second, stale archive.
 *
 * <p>Clipping is how an entry becomes permanent, and that is an explicit act
 * with an explicit target — see {@code CentauriAppController}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedsApplication implements VanceApplication {

    public static final String APP_NAME = "feeds";

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
        FeedsConfig config = fromParams(params);

        Map<String, Object> configBlock = new LinkedHashMap<>();
        configBlock.put(APP_NAME, config.toBlock());

        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description, configBlock, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored = write(ctx.tenantId(), ctx.projectName(), manifestPath,
                title == null ? "Feeds" : title, body, existing.orElse(null), ctx.userId());

        log.info("FeedsApplication.create tenant='{}' folder='{}' streams={}",
                ctx.tenantId(), folder, config.streams().size());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("streamCount", config.streams().size());

        String nextStep = config.streams().isEmpty()
                ? "Feed ready but empty. Add streams in the configuration tab, or configure a "
                        + "source first with centauri.endpoint.<id>.* settings."
                : "Feed ready with " + config.streams().size() + " stream(s). Open it to read.";

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), List.of(), nextStep, stats);
    }

    /**
     * No artefacts, by design. Everything this app shows lives at the source; a
     * refresh has nothing to regenerate. Implemented rather than left out so the
     * generic {@code app_rebuild} tool answers cleanly instead of failing.
     */
    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = normaliseFolder(ctx.folder());
        FeedsConfig config = readConfig(ctx.tenantId(), ctx.projectName(), folder);
        log.debug("FeedsApplication.refresh tenant='{}' folder='{}' streams={} (nothing derived)",
                ctx.tenantId(), folder, config.streams().size());
        return new RefreshResult(APP_NAME, folder, List.of());
    }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("📰", null);
    }

    /**
     * Configured shape only — deliberately no fetch. A dashboard card that
     * called five foreign sources would make opening the desktop as slow and as
     * failure-prone as opening the feed itself.
     */
    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        FeedsConfig config = readConfig(
                ctx.tenantId(), ctx.projectName(), normaliseFolder(ctx.folder()));
        if (config.streams().isEmpty()) {
            return Optional.of(AppStatus.of("No streams configured",
                    StatusSeverity.ATTENTION, List.of()));
        }
        List<StatusItem> items = new ArrayList<>();
        for (FeedStream stream : config.streams()) {
            items.add(new StatusItem(
                    stream.sourceId(),
                    stream.selector().isEmpty() ? null : stream.selector(),
                    null, null));
        }
        long sources = config.streams().stream().map(FeedStream::sourceId).distinct().count();
        return Optional.of(new AppStatus(
                config.streams().size() + " stream(s) from " + sources + " source(s)",
                null,
                List.of(new StatusMetric("Streams", String.valueOf(config.streams().size())),
                        new StatusMetric("Sources", String.valueOf(sources))),
                items,
                null));
    }

    /**
     * What the reader is looking at, so the chat engine can answer "what is in
     * this feed" without scraping. Configuration only — never the entries, which
     * change between the prompt and the answer anyway.
     */
    @Override
    public @Nullable String promptInject(PromptInjectContext ctx) {
        FeedsConfig config = readConfig(
                ctx.tenantId(), ctx.projectName(), normaliseFolder(ctx.folder()));
        if (config.streams().isEmpty()) {
            return "The open feed has no streams configured yet.";
        }
        StringBuilder sb = new StringBuilder("Open feed streams:\n");
        for (FeedStream stream : config.streams()) {
            sb.append("- ").append(stream.sourceId());
            if (!stream.selector().isEmpty()) {
                sb.append(" · ").append(stream.selector());
            }
            sb.append('\n');
        }
        if (!config.languages().isEmpty()) {
            sb.append("Languages: ").append(String.join(", ", config.languages())).append('\n');
        }
        if (!config.exclude().isEmpty()) {
            sb.append("Excluded: ").append(String.join(", ", config.exclude())).append('\n');
        }
        return sb.toString();
    }

    // ── shared with the controller ───────────────────────────────────

    /**
     * The manifest of this feed. Public because the tool package reads it too —
     * the alternative would be a second parse of the same document.
     */
    public FeedsConfig readConfig(String tenantId, String projectName, String folder) {
        return FeedsConfig.from(readManifest(tenantId, projectName, folder));
    }

    ApplicationDocument readManifest(String tenantId, String projectName, String folder) {
        String manifestPath = normaliseFolder(folder) + "/" + APP_MANIFEST;
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectName, manifestPath);
        if (doc.isEmpty()) {
            throw new ToolException("No feed manifest at '" + manifestPath + "'");
        }
        String body = documentService.readContent(doc.get());
        return ApplicationCodec.parse(body, doc.get().getMimeType());
    }

    /** Replace the {@code config.feeds} block, keeping title and description. */
    DocumentDocument writeConfig(String tenantId, String projectName, String folder,
                                 FeedsConfig config, @Nullable String userId) {
        String normalised = normaliseFolder(folder);
        String manifestPath = normalised + "/" + APP_MANIFEST;
        ApplicationDocument current = readManifest(tenantId, projectName, normalised);

        Map<String, Object> configBlock = new LinkedHashMap<>(current.config());
        configBlock.put(APP_NAME, config.toBlock());
        ApplicationDocument updated = new ApplicationDocument(
                "application", APP_NAME, current.title(), current.description(),
                configBlock, new LinkedHashMap<>(current.extra()));

        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectName, manifestPath);
        return write(tenantId, projectName, manifestPath,
                current.title() == null ? "Feeds" : current.title(),
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

    private static FeedsConfig fromParams(Map<String, Object> params) {
        List<FeedStream> streams = new ArrayList<>();
        if (params.get("streams") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String s && !s.isBlank()) {
                    streams.add(new FeedStream(s.trim(), ""));
                } else if (entry instanceof Map<?, ?> map) {
                    String source = asString(map.get("source"));
                    if (source != null) {
                        String selector = asString(map.get("selector"));
                        streams.add(new FeedStream(source, selector == null ? "" : selector));
                    }
                }
            }
        }
        int pageSize = params.get("pageSize") instanceof Number n ? n.intValue() : 0;
        return new FeedsConfig(streams, null, java.util.Set.of(), List.of(), List.of(),
                asString(params.get("since")), pageSize);
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
