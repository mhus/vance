package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only static validator for a designer-app folder. Its whole job is
 * to surface what the catalogue silently drops — the scan must stay
 * lenient (a broken {@code design.yaml} must not hide a design), and this
 * is the place that says it out loud:
 *
 * <ul>
 *   <li><b>no manifest</b> — the folder is not a designer app (error);</li>
 *   <li><b>ghost order entries</b> — names in {@code config.designer.order}
 *   that are not designs; harmless for sorting, but stale (warning);</li>
 *   <li><b>broken {@code design.yaml}</b> — unparseable metadata (error);
 *   the design still shows, with the folder name as title;</li>
 *   <li><b>incomplete folders</b> — files but no {@code index.html}: the
 *   classic authoring miss, invisible in the app (warning).</li>
 * </ul>
 *
 * <p>Checks structure only — never renders anything, never guesses at
 * HTML semantics. The entry-file spelling is the one contract a silent
 * catalogue would hide, so the finding names it explicitly.
 */
@Service
@RequiredArgsConstructor
public class DesignerValidationService {

    private final DesignerFolderReader folderReader;

    /** Result shape mirrors {@code workbook_validate}: { ok, errors, warnings, findings[] }. */
    public record Result(boolean ok, List<Finding> findings) {

        public Map<String, Object> toMap() {
            List<Map<String, Object>> list = new ArrayList<>();
            long errors = 0;
            long warnings = 0;
            for (Finding f : findings) {
                if (f.level() == Finding.Level.ERROR) {
                    errors++;
                } else {
                    warnings++;
                }
                list.add(f.toMap());
            }
            return Map.of(
                    "ok", errors == 0,
                    "errors", errors,
                    "warnings", warnings,
                    "findings", list);
        }
    }

    public Result validate(String tenantId, String projectId, String folder) {
        List<Finding> out = new ArrayList<>();
        String manifestPath = DesignerPaths.manifestPath(folder);

        DesignerFolderReader.FullScan scan = folderReader.scanFull(tenantId, projectId, folder);

        // A folder without a manifest is not an app at all — the one
        // structural error the scan cannot even see.
        if (!folderReader.hasManifest(tenantId, projectId, folder)) {
            out.add(Finding.error(
                    manifestPath,
                    "missing-manifest",
                    "No _app.yaml — this folder is not a designer app. "
                            + "Create it with designer_app_create(folder=\"" + folder + "\")."));
        }

        Set<String> known = scan.designs().stream()
                .map(DesignerFolderReader.DesignScan::name)
                .collect(Collectors.toSet());

        for (String ghost : folderReader.manifestOrder(tenantId, projectId, folder)) {
            if (!known.contains(ghost)) {
                out.add(Finding.warning(
                        manifestPath + " (designer.order)",
                        "ghost-order-entry",
                        "Order entry '" + ghost + "' is not a design — remove it or fix the name."));
            }
        }

        for (String name : scan.brokenMetaDesigns()) {
            out.add(Finding.error(
                    folder + "/" + name + "/" + DesignerPaths.DESIGN_META_FILE,
                    "broken-design-meta",
                    "design.yaml of '" + name + "' is not valid YAML — the design shows "
                            + "with its folder name as title until this is fixed."));
        }

        // A stored mime that contradicts the file's extension breaks the
        // preview: the content route serves the stored mime, and nosniff
        // turns text/markdown-on-index.html into raw source in the iframe.
        // The entry file is the design's render contract — error; the
        // rest (css/js/assets) degrades more quietly — warning.
        for (DesignerFolderReader.DesignScan design : scan.designs()) {
            for (String file : design.files()) {
                String expected = de.mhus.vance.shared.document.DocumentService.mimeFromPath(file);
                if ("text/plain".equals(expected)) continue;
                String stored = design.mimeByFile().getOrDefault(file, "");
                if (!stored.isBlank() && !stored.equals(expected)) {
                    String location = folder + "/" + design.name() + "/" + file;
                    boolean entry = DesignerPaths.ENTRY_FILE.equals(file);
                    out.add(
                            entry
                                    ? Finding.error(
                                            location,
                                            "mime-mismatch",
                                            "Entry file is stored as '" + stored + "' — the preview shows raw "
                                                    + "source instead of the design. Re-save it with "
                                                    + "doc_write(path=\"" + location + "\", content=…, "
                                                    + "mimeType=\"" + expected + "\").")
                                    : Finding.warning(
                                            location,
                                            "mime-mismatch",
                                            "File is stored as '" + stored + "' but its extension says '"
                                                    + expected + "' — sub-resource loading may misbehave. "
                                                    + "Re-save with mimeType=\"" + expected + "\"."));
                }
            }
        }

        for (DesignerFolderReader.IncompleteFolder f : scan.incomplete()) {
            out.add(Finding.warning(
                    folder + "/" + f.name() + "/",
                    "missing-entry-file",
                    "Folder '" + f.name() + "' has " + f.files().size() + " file(s) but no "
                            + DesignerPaths.ENTRY_FILE + " — it does not show up as a design. "
                            + "Create '" + folder + "/" + f.name() + "/" + DesignerPaths.ENTRY_FILE
                            + "' (exact name, lowercase)."));
        }

        return new Result(out.stream().noneMatch(f -> f.level() == Finding.Level.ERROR), out);
    }
}
