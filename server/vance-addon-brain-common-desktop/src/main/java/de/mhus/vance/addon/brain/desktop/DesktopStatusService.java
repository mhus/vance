package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.AppCard;
import de.mhus.vance.brain.applications.VanceApplication.AppStatus;
import de.mhus.vance.brain.applications.VanceApplication.DescribeContext;
import de.mhus.vance.brain.applications.VanceApplication.StatusContext;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Aggregates every app under a Common Desktop folder into a
 * {@link DesktopView}. Read-only: enumerates {@code kind: application}
 * documents via {@link DocumentService} (data-ownership — never touches
 * MongoTemplate), asks each app's {@code describe()} for its launcher
 * identity and (guarded) {@code status()} for its dynamic body.
 *
 * <p>A failing or unknown app degrades to a launcher-only card rather
 * than dropping out or breaking the whole board.
 */
@Service
@Slf4j
public class DesktopStatusService {

    private static final String APPLICATION_KIND = "application";
    private static final String MANIFEST_SUFFIX = "/" + VanceApplication.APP_MANIFEST;
    private static final String FALLBACK_ICON = "📦";

    private final DocumentService documentService;
    private final VanceApplicationRegistry registry;

    public DesktopStatusService(DocumentService documentService,
                                VanceApplicationRegistry registry) {
        this.documentService = documentService;
        this.registry = registry;
    }

    /**
     * Build the live desktop view for the {@code common-desktop} app at
     * {@code desktopFolder}.
     *
     * @throws ToolException when the folder has no {@code common-desktop}
     *         manifest.
     */
    public DesktopView aggregate(String tenantId, String projectName,
                                 String desktopFolder, @Nullable String userId) {
        ApplicationDocument manifest = loadDesktopManifest(tenantId, projectName, desktopFolder);
        DesktopAppConfig config = DesktopAppConfig.from(manifest);
        String root = resolveRoot(desktopFolder, config.root());

        List<DesktopCard> cards = new ArrayList<>();
        for (DocumentDocument doc : documentService.listByKind(tenantId, projectName, APPLICATION_KIND)) {
            String path = doc.getPath();
            if (path == null || !path.endsWith(MANIFEST_SUFFIX)) continue;
            String appFolder = path.substring(0, path.length() - MANIFEST_SUFFIX.length());
            if (appFolder.equals(desktopFolder)) continue;          // self
            if (!underRoot(appFolder, root, config.recurse())) continue;

            ApplicationDocument appManifest = parseQuietly(doc);
            if (appManifest == null) continue;
            String appType = appManifest.app();
            if (appType == null || appType.isBlank()) continue;
            String appTypeKey = appType.toLowerCase(Locale.ROOT);
            if (DesktopAppConfig.APP_NAME.equals(appTypeKey)) continue;   // never nest desktops
            if (config.exclude().contains(appTypeKey)) continue;
            if (!config.include().isEmpty() && !config.include().contains(appTypeKey)) continue;

            cards.add(buildCard(tenantId, projectName, doc.getId(), appFolder, path,
                    appType, appManifest, userId));
        }

        applyOrder(cards, config.order());
        log.debug("DesktopStatusService.aggregate tenant='{}' folder='{}' root='{}' → {} apps",
                tenantId, desktopFolder, root, cards.size());
        return DesktopView.builder().folder(desktopFolder).cards(cards).build();
    }

    // ── Per-app card ──────────────────────────────────────────────

    private DesktopCard buildCard(String tenantId, String projectName,
                                  @Nullable String manifestId,
                                  String appFolder, String manifestPath,
                                  String appType, ApplicationDocument manifest,
                                  @Nullable String userId) {
        Map<String, Object> configBlock = configBlock(manifest, appType);
        String icon = FALLBACK_ICON;
        String openLink = null;
        DesktopStatusView status = null;

        Optional<VanceApplication> appOpt = registry.find(appType);
        if (appOpt.isPresent()) {
            VanceApplication app = appOpt.get();
            try {
                AppCard card = app.describe(new DescribeContext(
                        tenantId, projectName, appFolder, userId, configBlock));
                icon = card.icon();
                openLink = card.openLink();
            } catch (RuntimeException e) {
                log.warn("desktop describe() failed for app='{}' folder='{}': {}",
                        appType, appFolder, e.toString());
            }
            try {
                Optional<AppStatus> st = app.status(new StatusContext(
                        tenantId, projectName, appFolder, userId, null, configBlock));
                if (st.isPresent()) status = DesktopMapper.toView(st.get());
            } catch (RuntimeException e) {
                log.warn("desktop status() failed for app='{}' folder='{}': {}",
                        appType, appFolder, e.toString());
                status = DesktopMapper.errorView("Status failed: " + e.getMessage());
            }
        }

        if (openLink == null) openLink = "vance:/" + manifestPath;
        String title = manifest.title() != null && !manifest.title().isBlank()
                ? manifest.title() : leaf(appFolder);

        return DesktopCard.builder()
                .id(manifestId)
                .app(appType)
                .folder(appFolder)
                .title(title)
                .description(manifest.description())
                .icon(icon)
                .openLink(openLink)
                .status(status)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configBlock(ApplicationDocument manifest, String appType) {
        Object raw = manifest.config().get(appType);
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new LinkedHashMap<>();
    }

    // ── Ordering ──────────────────────────────────────────────────

    /** Apps whose type is listed in {@code order} come first (in that
     *  order); the rest follow sorted by folder. Stable. */
    private static void applyOrder(List<DesktopCard> cards, List<String> order) {
        cards.sort(Comparator
                .comparingInt((DesktopCard c) -> {
                    int idx = order.indexOf(c.getApp().toLowerCase(Locale.ROOT));
                    return idx < 0 ? Integer.MAX_VALUE : idx;
                })
                .thenComparing(DesktopCard::getFolder));
    }

    // ── Path scoping ──────────────────────────────────────────────

    /** {@code root} config resolved against the desktop folder.
     *  {@code null}/{@code "."} = the desktop folder itself; a leading
     *  {@code /} makes it project-absolute. */
    private static String resolveRoot(String desktopFolder, @Nullable String root) {
        if (root == null || root.isBlank() || root.equals(".")) return desktopFolder;
        String r = root.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        while (r.startsWith("/")) r = r.substring(1);
        return r.isEmpty() ? desktopFolder : r;
    }

    private static boolean underRoot(String appFolder, String root, boolean recurse) {
        String prefix = root + "/";
        if (!appFolder.startsWith(prefix)) return false;
        if (recurse) return true;
        return appFolder.indexOf('/', prefix.length()) < 0;   // direct child only
    }

    private static String leaf(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    // ── Manifest loading / parsing ────────────────────────────────

    private ApplicationDocument loadDesktopManifest(String tenantId, String projectName,
                                                    String desktopFolder) {
        String path = desktopFolder + MANIFEST_SUFFIX;
        DocumentDocument doc = documentService.findByPath(tenantId, projectName, path)
                .orElseThrow(() -> new ToolException(
                        "No _app.yaml manifest found at '" + path + "'."));
        ApplicationDocument manifest = parseQuietly(doc);
        if (manifest == null || !DesktopAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
            throw new ToolException(
                    "Folder '" + desktopFolder + "' is not a common-desktop app.");
        }
        return manifest;
    }

    private @Nullable ApplicationDocument parseQuietly(DocumentDocument doc) {
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) return null;
        try {
            return ApplicationCodec.parse(loadAsText(doc), mime);
        } catch (RuntimeException e) {
            log.warn("desktop: could not parse manifest '{}': {}", doc.getPath(), e.toString());
            return null;
        }
    }

    private String loadAsText(DocumentDocument doc) {
        String cached = documentService.readContent(doc);
        if (cached != null) return cached;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read manifest content: " + e.getMessage());
        }
    }
}
