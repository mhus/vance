package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared folder-scan logic for designer-app folders. Groups every
 * document under the app folder by its first path segment; a segment is
 * a <em>design</em> when it contains an {@code index.html}. Optional
 * per-design metadata comes from a {@code design.yaml}
 * ({@code title}, {@code description}) — parsed leniently, a broken
 * metadata file never breaks the scan.
 *
 * <p>{@link #scan} is the catalogue view (designs only, manifest order
 * applied). {@link #scanFull} additionally reports what the catalogue
 * silently drops — folders without an entry file, designs with a broken
 * {@code design.yaml} — because the validator's whole job is to surface
 * exactly those states. One grouping pass feeds both, so the two views
 * cannot drift apart.
 */
@Service
@RequiredArgsConstructor
public class DesignerFolderReader {

    /** Config-block key in the manifest under which the app's options live. */
    private static final String APP_CONFIG_KEY = DesignerApplication.APP_NAME;

    private final DocumentService documentService;

    /**
     * One scanned design: name, optional metadata, and the files
     * relative to the design folder (metadata file excluded from
     * {@code fileCount}).
     */
    public record DesignScan(
            String name,
            @Nullable String title,
            @Nullable String description,
            List<String> files,
            int fileCount,
            Map<String, String> mimeByFile) {

        /** Display name: metadata title, else the folder name. */
        public String displayTitle() {
            return title == null || title.isBlank() ? name : title;
        }
    }

    /** A folder under the app that has files but no entry — not a design yet. */
    public record IncompleteFolder(String name, List<String> files) {}

    /** Everything the grouping pass sees, including what {@link #scan} drops. */
    public record FullScan(
            List<DesignScan> designs, List<IncompleteFolder> incomplete, List<String> brokenMetaDesigns) {}

    /** Result of a scan: the designs, sorted by manifest order. */
    public record Scan(List<DesignScan> designs) {

        public Optional<DesignScan> find(String name) {
            return designs.stream().filter(d -> d.name().equals(name)).findFirst();
        }
    }

    /** The catalogue view: designs with an entry file, manifest order applied. */
    public Scan scan(String tenantId, String projectId, String appFolder) {
        return new Scan(scanFull(tenantId, projectId, appFolder).designs());
    }

    /**
     * The full picture for validation: designs plus the states the
     * catalogue drops silently — incomplete folders and broken
     * {@code design.yaml} files.
     */
    public FullScan scanFull(String tenantId, String projectId, String appFolder) {
        String folder = DesignerPaths.normaliseFolder(appFolder);
        Map<String, List<String>> filesByDesign = new LinkedHashMap<>();
        Map<String, Map<String, String>> mimeByDesign = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        List<String> brokenMeta = new ArrayList<>();

        for (DocumentDocument doc : documentService.listUnderFolder(tenantId, projectId, folder + "/")) {
            String path = doc.getPath();
            if (path.equals(DesignerPaths.manifestPath(folder))) {
                continue;
            }
            Optional<String> design = DesignerPaths.designNameOf(folder, path);
            if (design.isEmpty()) {
                continue;
            }
            String name = design.get();
            String relative = path.substring(folder.length() + name.length() + 2);
            if (DesignerPaths.DESIGN_META_FILE.equals(relative)) {
                if (!readMeta(doc, name, titles, descriptions)) {
                    brokenMeta.add(name);
                }
                continue;
            }
            filesByDesign.computeIfAbsent(name, k -> new ArrayList<>()).add(relative);
            mimeByDesign
                    .computeIfAbsent(name, k -> new LinkedHashMap<>())
                    .put(relative, doc.getMimeType() == null ? "" : doc.getMimeType());
        }

        // Manifest order wins: designs listed in config.designer.order come
        // first in that sequence, the rest alphabetical. Reading the order
        // here — not in the callers — keeps "what order do designs have"
        // a property of the scan, answered once.
        Map<String, Integer> order = orderIndex(tenantId, projectId, folder);

        List<DesignScan> designs = new ArrayList<>();
        List<IncompleteFolder> incomplete = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : filesByDesign.entrySet()) {
            List<String> files = e.getValue();
            String name = e.getKey();
            if (!files.contains(DesignerPaths.ENTRY_FILE)) {
                // A folder without an entry file is not previewable — not an
                // error, just not a design yet. The catalogue drops it; the
                // validator reports it.
                files.sort(Comparator.naturalOrder());
                incomplete.add(new IncompleteFolder(name, List.copyOf(files)));
                continue;
            }
            files.sort(Comparator.naturalOrder());
            designs.add(new DesignScan(
                    name,
                    titles.get(name),
                    descriptions.get(name),
                    List.copyOf(files),
                    files.size(),
                    Map.copyOf(mimeByDesign.getOrDefault(name, Map.of()))));
        }
        designs.sort(Comparator.comparingInt((DesignScan d) -> order.getOrDefault(d.name(), Integer.MAX_VALUE))
                .thenComparing(DesignScan::name));
        incomplete.sort(Comparator.comparing(IncompleteFolder::name));
        return new FullScan(List.copyOf(designs), List.copyOf(incomplete), List.copyOf(brokenMeta));
    }

    /** Whether the folder carries a {@code _app.yaml} at all. */
    public boolean hasManifest(String tenantId, String projectId, String appFolder) {
        return documentService
                .findByPath(tenantId, projectId, DesignerPaths.manifestPath(DesignerPaths.normaliseFolder(appFolder)))
                .isPresent();
    }

    /**
     * The manifest's design order, as written — including entries the scan
     * does not resolve to a design. The validator needs the raw list to
     * report ghosts; the scan only needs the positions of known names.
     */
    public List<String> manifestOrder(String tenantId, String projectId, String appFolder) {
        String folder = DesignerPaths.normaliseFolder(appFolder);
        try {
            Optional<DocumentDocument> manifest =
                    documentService.findByPath(tenantId, projectId, DesignerPaths.manifestPath(folder));
            if (manifest.isEmpty()) {
                return List.of();
            }
            ApplicationDocument app = ApplicationCodec.parse(
                    documentService.readContent(manifest.get()), manifest.get().getMimeType());
            if (!(app.config().get(APP_CONFIG_KEY) instanceof Map<?, ?> designer)) {
                return List.of();
            }
            if (!(designer.get("order") instanceof List<?> raw)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String s && !s.isBlank() && !names.contains(s)) {
                    names.add(s);
                }
            }
            return names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The sort key per design name from the manifest's
     * {@code config.designer.order} — absent names sort last. Lenient: a
     * broken manifest leaves the order empty, the scan stays alphabetical.
     */
    private Map<String, Integer> orderIndex(String tenantId, String projectId, String folder) {
        List<String> order = manifestOrder(tenantId, projectId, folder);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (String name : order) {
            index.putIfAbsent(name, index.size());
        }
        return index;
    }

    /**
     * Reads title/description of one design. Returns {@code false} when the
     * file is not parseable — lenient for the scan (the design stays), but
     * the fact must not be swallowed for the validator.
     */
    private boolean readMeta(
            DocumentDocument doc, String design, Map<String, String> titles, Map<String, String> descriptions) {
        try {
            Map<String, Object> meta = KindHeaderCodec.parseYamlBody(documentService.readContent(doc));
            Object title = meta.get("title");
            Object description = meta.get("description");
            if (title instanceof String s && !s.isBlank()) {
                titles.put(design, s.trim());
            }
            if (description instanceof String s && !s.isBlank()) {
                descriptions.put(design, s.trim());
            }
            return true;
        } catch (RuntimeException e) {
            // Lenient by design: a broken design.yaml must not hide the
            // design from the catalogue. The author sees the raw file in
            // the editor — and the validator reports it.
            return false;
        }
    }
}
