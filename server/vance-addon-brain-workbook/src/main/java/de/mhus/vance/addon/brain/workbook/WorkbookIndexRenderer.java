package de.mhus.vance.addon.brain.workbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Render the {@code _index.md} body for a workbook scan. Output is a
 * {@code kind: workpage} document — when opened in the editor it shows up
 * with the same Tiptap rendering as a hand-written workpage. Idempotent
 * by construction: same scan → same output.
 */
@Component
public class WorkbookIndexRenderer {

    public String render(WorkbookFolderReader.Scan scan, String workbookTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(workbookTitle)).append(" — Index\"\n");
        sb.append("description: \"Automatisch generiert aus Workbook-Pages.\"\n");
        sb.append("---\n");
        sb.append("# ").append(workbookTitle).append("\n\n");
        sb.append("```vance-callout\n")
                .append("severity: note\n")
                .append("title: Auto-generiert\n")
                .append("body: Diese Page wird bei jedem `app_rebuild` neu geschrieben — Edits hier gehen verloren.\n")
                .append("```\n\n");

        if (scan.pages().isEmpty()) {
            sb.append("Keine Pages in diesem Workbook.\n");
            return sb.toString();
        }

        if (!scan.config().index().groupBySection()) {
            renderFlat(scan.pages(), scan.folder(), sb, scan.config().index().showDescriptions());
            return sb.toString();
        }

        // Group by section. Top-level first ('' section), then sub-sections
        // alphabetically.
        Map<String, List<WorkbookPage>> bySection = new LinkedHashMap<>();
        for (WorkbookPage p : scan.pages()) {
            bySection.computeIfAbsent(p.section(), k -> new java.util.ArrayList<>()).add(p);
        }
        List<WorkbookPage> topLevel = bySection.remove("");
        if (topLevel != null && !topLevel.isEmpty()) {
            sb.append("## Pages\n\n");
            renderList(topLevel, scan.folder(), sb, scan.config().index().showDescriptions());
            sb.append("\n");
        }
        List<String> sectionKeys = new java.util.ArrayList<>(bySection.keySet());
        java.util.Collections.sort(sectionKeys);
        for (String section : sectionKeys) {
            sb.append("## ").append(humanise(section)).append("\n\n");
            renderList(bySection.get(section), scan.folder(), sb, scan.config().index().showDescriptions());
            sb.append("\n");
        }
        return sb.toString();
    }

    private void renderFlat(List<WorkbookPage> pages, String folder, StringBuilder sb,
                            boolean showDescriptions) {
        sb.append("## Pages\n\n");
        renderList(pages, folder, sb, showDescriptions);
    }

    private void renderList(List<WorkbookPage> pages, String folder, StringBuilder sb,
                            boolean showDescriptions) {
        for (WorkbookPage p : pages) {
            sb.append("- [").append(escape(p.title())).append("](")
                    .append(p.relativePath()).append(")");
            if (showDescriptions && p.description() != null && !p.description().isBlank()) {
                sb.append(" — ").append(p.description());
            }
            sb.append("\n");
        }
    }

    private static String humanise(String section) {
        if (section.isEmpty()) return "Pages";
        String s = section.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
