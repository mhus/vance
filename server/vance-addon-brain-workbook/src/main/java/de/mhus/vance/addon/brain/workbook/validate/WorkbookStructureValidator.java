package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workbook.WorkbookFolderReader.Scan;
import de.mhus.vance.addon.brain.workbook.WorkbookPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Workbook-folder-level checks that are not tied to a single fence: each
 * page's {@code $meta.rebuildScripts} must resolve to an existing {@code .js}
 * document, and the manifest {@code landingPage} (if set) must exist.
 */
@Component
public class WorkbookStructureValidator {

    public List<Finding> validate(Scan scan, DocRefs docs) {
        List<Finding> out = new ArrayList<>();

        String landing = scan.config().landingPage();
        if (landing != null && !landing.isBlank()) {
            String path = landing.startsWith("/")
                    ? landing.substring(1)
                    : scan.folder() + "/" + landing.strip();
            if (!docs.exists(path)) {
                out.add(Finding.error(scan.folder() + "/_app.yaml", "unresolved-landingPage",
                        "manifest landingPage '" + landing + "' does not exist ('"
                                + path + "')."));
            }
        }

        for (WorkbookPage page : scan.pages()) {
            String pagePath = page.doc().getPath();
            for (String raw : page.rebuildScripts()) {
                VanceRef ref = VanceRef.parse(raw, pagePath);
                String loc = pagePath + " ($meta.rebuildScripts)";
                if (ref == null) {
                    out.add(Finding.error(loc, "bad-rebuildScript",
                            "rebuildScript is not a usable reference: '" + raw + "'."));
                    continue;
                }
                if (!ref.path().toLowerCase(Locale.ROOT).endsWith(".js")) {
                    out.add(Finding.error(loc, "not-js-rebuildScript",
                            "rebuildScript must be a .js document: '" + ref.path() + "'."));
                    continue;
                }
                if (!docs.exists(ref.path())) {
                    out.add(Finding.error(loc, "unresolved-rebuildScript",
                            "rebuildScript does not exist: '" + ref.path()
                                    + "' (create it with doc_write, not work_file_write)."));
                }
            }
        }
        return out;
    }
}
