package de.mhus.vance.addon.brain.wiki;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Render the {@code _index.md} body for one wiki space. Output is a
 * {@code kind: workpage} Markdown document — when opened in the wiki host
 * its {@code [[…]]} links render as navigable wikilinks. Idempotent:
 * same scan → same output.
 *
 * <ul>
 *   <li><b>Root index</b> ({@code space == ""}): the recently-modified
 *       pages (wiki-global) followed by the full page list grouped by
 *       space.</li>
 *   <li><b>Space index</b>: that space's pages, recursive over
 *       sub-spaces.</li>
 * </ul>
 */
@Component
public class WikiIndexRenderer {

    private final WikiService wikiService;

    public WikiIndexRenderer(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    public String render(WikiFolderReader.Scan scan, String space, String wikiTitle) {
        boolean root = space == null || space.isEmpty();
        String heading = root ? wikiTitle : WikiFolderReader.humanise(leaf(space));

        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(heading)).append(" — Index\"\n");
        sb.append("description: \"Auto-generated wiki index.\"\n");
        sb.append("---\n");
        sb.append("# ").append(heading).append("\n\n");
        sb.append("```vance-callout\n")
                .append("severity: note\n")
                .append("title: Auto-generated\n")
                .append("body: This page is rewritten on every `app_rebuild` — edits here are lost.\n")
                .append("```\n\n");

        // Curated home of this space.
        for (WikiPage p : wikiService.pagesInSpace(scan, space)) {
            if (p.main() && p.space().equals(normalise(space))) {
                sb.append("🏠 Home: ").append(wikiLink(p, true)).append("\n\n");
                break;
            }
        }

        if (root) {
            renderRecent(scan, sb);
            renderGroupedBySpace(scan, sb);
        } else {
            renderSpacePages(scan, space, sb);
        }
        return sb.toString();
    }

    private void renderRecent(WikiFolderReader.Scan scan, StringBuilder sb) {
        int limit = scan.config().recentLimit();
        List<WikiPage> recent = wikiService.recentlyModified(scan, limit);
        // Only meaningful when there's more than a handful of pages.
        if (recent.isEmpty()) return;
        sb.append("## Recently Modified\n\n");
        for (WikiPage p : recent) {
            sb.append("- ").append(wikiLink(p, true)).append("\n");
        }
        sb.append("\n");
    }

    private void renderGroupedBySpace(WikiFolderReader.Scan scan, StringBuilder sb) {
        Map<String, List<WikiPage>> bySpace = new LinkedHashMap<>();
        for (WikiPage p : scan.pages()) {
            if (p.main()) continue; // home shown separately
            bySpace.computeIfAbsent(p.space(), k -> new java.util.ArrayList<>()).add(p);
        }
        List<WikiPage> topLevel = bySpace.remove("");
        boolean showDesc = scan.config().index().showDescriptions();
        if (topLevel != null && !topLevel.isEmpty()) {
            sb.append("## Pages\n\n");
            renderList(topLevel, sb, showDesc);
            sb.append("\n");
        }
        List<String> spaceKeys = new java.util.ArrayList<>(bySpace.keySet());
        java.util.Collections.sort(spaceKeys);
        for (String space : spaceKeys) {
            sb.append("## ").append(WikiFolderReader.humanise(space.replace('/', ' '))).append("\n\n");
            renderList(bySpace.get(space), sb, showDesc);
            sb.append("\n");
        }
        if ((topLevel == null || topLevel.isEmpty()) && spaceKeys.isEmpty()) {
            sb.append("No pages in this wiki yet.\n");
        }
    }

    private void renderSpacePages(WikiFolderReader.Scan scan, String space, StringBuilder sb) {
        List<WikiPage> pages = new java.util.ArrayList<>();
        String s = normalise(space);
        for (WikiPage p : wikiService.pagesInSpace(scan, space)) {
            if (p.main() && p.space().equals(s)) continue; // home shown separately
            pages.add(p);
        }
        sb.append("## Pages\n\n");
        if (pages.isEmpty()) {
            sb.append("No pages in this space yet.\n");
            return;
        }
        renderList(pages, sb, scan.config().index().showDescriptions());
    }

    private void renderList(List<WikiPage> pages, StringBuilder sb, boolean showDescriptions) {
        for (WikiPage p : pages) {
            sb.append("- ").append(wikiLink(p, true)).append("\n");
        }
    }

    /**
     * Build a {@code [[…]]} wikilink for a page. Root pages use the bare
     * slug; pages inside a space use the explicit {@code space/slug} form
     * to disambiguate. Label is the page title.
     */
    private static String wikiLink(WikiPage p, boolean withLabel) {
        String target = p.space().isEmpty() ? p.slug() : p.space() + "/" + p.slug();
        if (withLabel && !p.title().equalsIgnoreCase(p.slug())) {
            return "[[" + target + "|" + p.title() + "]]";
        }
        return "[[" + target + "]]";
    }

    private static String normalise(String space) {
        return space == null ? "" : space;
    }

    private static String leaf(String space) {
        int slash = space.lastIndexOf('/');
        return slash < 0 ? space : space.substring(slash + 1);
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
