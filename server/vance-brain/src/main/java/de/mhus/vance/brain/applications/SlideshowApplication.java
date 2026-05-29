package de.mhus.vance.brain.applications;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.slideshow.SlideshowFolderReader;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.DataCodec;
import de.mhus.vance.shared.document.kind.DataDocument;
import de.mhus.vance.shared.document.kind.SlideshowAppConfig;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Concrete {@link VanceApplication} for {@code app: slideshow}
 * folders. The single derived artefact is {@code _index.yaml} —
 * a {@code kind: data} document listing every slide with path,
 * caption and probed pixel dimensions. The web UI fetches
 * {@code _index.yaml} once at load and reserves aspect ratios
 * before any image download — no layout shift while the slides
 * stream in.
 */
@Service
@Slf4j
public class SlideshowApplication implements VanceApplication {

    public static final String APP_NAME = SlideshowAppConfig.APP_NAME;

    private static final String YAML_MIME = "application/yaml";

    private final SlideshowFolderReader folderReader;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public SlideshowApplication(SlideshowFolderReader folderReader,
                                DocumentService documentService,
                                DocumentLinkBuilder linkBuilder) {
        this.folderReader = folderReader;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
    }

    @Override public String appName() { return APP_NAME; }

    /**
     * Bootstrap a new slideshow app. Writes the {@code _app.yaml}
     * manifest and runs an immediate refresh to produce the
     * {@code _index.yaml} for any images already in the folder.
     *
     * <p>Expected {@code params}: {@code title}, {@code description},
     * {@code autoplaySeconds}, {@code aspectRatio}, {@code order}
     * (List of relative image paths), {@code captions}
     * (Map of relative-path → caption).
     */
    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = ctx.folder();
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + SlideshowFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        Integer autoplay = asInt(params.get("autoplaySeconds"));
        String aspectRatio = asString(params.get("aspectRatio"));
        List<String> order = asStringList(params.get("order"));
        Map<String, String> captions = asStringMap(params.get("captions"));

        Map<String, Object> slideshowBlock = new LinkedHashMap<>();
        if (!order.isEmpty()) slideshowBlock.put("order", order);
        if (!captions.isEmpty()) slideshowBlock.put("captions", captions);
        if (autoplay != null && autoplay > 0) slideshowBlock.put("autoplaySeconds", autoplay);
        if (aspectRatio != null) slideshowBlock.put("aspectRatio", aspectRatio);
        slideshowBlock.put("index", Map.of("outputPath", "_index.yaml"));

        Map<String, Object> appConfig = new LinkedHashMap<>();
        appConfig.put(SlideshowAppConfig.APP_NAME, slideshowBlock);
        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description,
                appConfig, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Slideshow",
                    List.of("application", "slideshow"),
                    body, null, null, null, null, YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Slideshow",
                        List.of("application", "slideshow"),
                        YAML_MIME, in, ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath
                                + "': " + e.getMessage());
            }
        }

        // Run refresh so the index is ready immediately. Slideshow
        // apps almost always have images already in the folder when
        // the manifest is created.
        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder,
                ctx.userId(), ctx.processId());
        RefreshResult refresh = refresh(rc);

        log.info("SlideshowApplication.create tenant='{}' folder='{}' "
                        + "manifestPath='{}'",
                ctx.tenantId(), folder, manifestPath);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);

        String nextStep = "Slideshow ready — open `" + manifestPath
                + "` in the App editor for the full viewer. The "
                + "`artefacts` list carries the `_index.yaml` with "
                + "every slide's dimensions and caption.";

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                new ArrayList<>(), refresh.artefacts(), nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        SlideshowFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), ctx.folder());

        // Build the index body — straight list of slides preserving
        // the resolved order.
        List<Map<String, Object>> slidesList = new ArrayList<>(scan.slides().size());
        for (SlideshowFolderReader.Slide s : scan.slides()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", s.doc().getPath());
            m.put("relativePath", s.relativePath());
            if (s.caption() != null) m.put("caption", s.caption());
            if (s.width() != null) m.put("width", s.width());
            if (s.height() != null) m.put("height", s.height());
            m.put("sizeBytes", s.sizeBytes());
            m.put("mimeType", s.mimeType());
            slidesList.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("folder", scan.folder());
        body.put("slideCount", slidesList.size());
        if (scan.slideshowConfig().autoplaySeconds() > 0) {
            body.put("autoplaySeconds", scan.slideshowConfig().autoplaySeconds());
        }
        if (scan.slideshowConfig().aspectRatio() != null) {
            body.put("aspectRatio", scan.slideshowConfig().aspectRatio());
        }
        body.put("slides", slidesList);

        DataDocument data = new DataDocument("data", body, new LinkedHashMap<>());
        String yaml = DataCodec.serialize(data, YAML_MIME);

        String outputPath = SlideshowFolderReader.resolveOutputPath(
                scan.folder(), scan.slideshowConfig().index().outputPath());
        String title = scan.manifest().title() != null
                ? scan.manifest().title() : leafFolderName(scan.folder());
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, yaml,
                "Slideshow index — " + title, YAML_MIME,
                List.of("slideshow", "generated", "index"));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("slideCount", slidesList.size());
        int withDim = 0;
        long totalBytes = 0;
        for (SlideshowFolderReader.Slide s : scan.slides()) {
            if (s.width() != null && s.height() != null) withDim++;
            totalBytes += s.sizeBytes();
        }
        stats.put("dimensionsKnown", withDim);
        stats.put("totalBytes", totalBytes);

        ArtefactResult index = new ArtefactResult(
                "index", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                stats);

        log.info("SlideshowApplication.refresh tenant='{}' folder='{}' "
                        + "→ {} slides", ctx.tenantId(), scan.folder(),
                slidesList.size());

        return new RefreshResult(APP_NAME, scan.folder(), List.of(index));
    }

    // ── Common write path ─────────────────────────────────────────

    private DocumentDocument writeArtefact(RefreshContext ctx,
                                           String outputPath,
                                           String body,
                                           String title,
                                           String mime,
                                           List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title, tags, body, null, null, null, null, mime);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    outputPath, title, tags, mime, in, ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    private static String asString(Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static List<String> asStringList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = asString(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static Map<String, String> asStringMap(Object v) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(v instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            String value = asString(e.getValue());
            if (value != null) out.put(key, value);
        }
        return out;
    }
}
