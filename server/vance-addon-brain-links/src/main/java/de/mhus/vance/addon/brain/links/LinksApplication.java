package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: links} — a link manager.
 *
 * <p>The manifest holds an ordered list of external URLs with a group
 * label each; the app renders them as search-result-style cards whose
 * picture and teaser come from the brain's link-preview proxy unless the
 * reader typed their own. Nothing about the linked pages is stored beyond
 * a title snapshot — see {@link LinkEntry} for why that one field is the
 * exception.
 *
 * <p>Unlike workbook/canvasbook the list is not folder-derived, and unlike
 * the binder the targets are not project documents: a links app points
 * outward. The only derived artefact is the {@code _index.md} link list,
 * which exists so the collection is readable from anywhere that renders
 * markdown (chat, a workpage embed, an export) and not only inside the app.
 */
@Service
@Slf4j
public class LinksApplication implements VanceApplication {

    public static final String APP_NAME = LinksConfig.BLOCK;
    private static final String MD_MIME = "text/markdown";
    private static final int STATUS_ITEM_LIMIT = 8;

    private final LinksStore store;
    private final DocumentLinkBuilder linkBuilder;

    public LinksApplication(LinksStore store, DocumentLinkBuilder linkBuilder) {
        this.store = store;
        this.linkBuilder = linkBuilder;
    }

    @Override
    public String appName() {
        return APP_NAME;
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = LinksStore.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() == null ? Map.of() : ctx.params();

        if (store.exists(ctx.tenantId(), ctx.projectName(), folder) && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '"
                    + LinksStore.manifestPath(folder)
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));

        List<String> groups = new ArrayList<>();
        if (params.get("groups") instanceof List<?> list) {
            for (Object o : list) {
                String s = asString(o);
                if (s != null && !groups.contains(s)) groups.add(s);
            }
        }

        LinksConfig config = new LinksConfig(groups, List.of(), LinksConfig.DEFAULT_INDEX);
        DocumentDocument stored = store.writeManifest(ctx.tenantId(), ctx.projectName(), folder,
                title, description, config, ctx.userId());

        RefreshResult refresh = refresh(new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        log.info("LinksApplication.create tenant='{}' folder='{}' groups={}",
                ctx.tenantId(), folder, groups.size());

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("groupCount", groups.size());

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), refresh.artefacts(),
                "Link list ready. Add links with "
                        + "`links_entry_add(folder=\"" + folder + "\", url=\"https://…\")`.",
                stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = LinksStore.normaliseFolder(ctx.folder());
        LinksStore.Loaded loaded = store.load(ctx.tenantId(), ctx.projectName(), folder);
        LinksConfig config = loaded.config();

        String title = loaded.manifestDoc().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        String outputPath = LinksStore.resolveOutputPath(folder, config.indexOutputPath());
        DocumentDocument stored = store.writeArtefact(ctx.tenantId(), ctx.projectName(),
                outputPath, "Index — " + title, MD_MIME, renderIndex(config, title),
                List.of(APP_NAME, "generated", "index"), ctx.userId());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entryCount", config.entries().size());
        stats.put("groupCount", config.orderedGroups().size());
        ArtefactResult index = new ArtefactResult("index", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info("LinksApplication.refresh tenant='{}' folder='{}' entries={}",
                ctx.tenantId(), folder, config.entries().size());
        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🔗", null);
    }

    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        LinksConfig config;
        try {
            config = store.load(ctx.tenantId(), ctx.projectName(), ctx.folder()).config();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<StatusItem> items = new ArrayList<>();
        for (LinkEntry e : config.entries()) {
            if (items.size() >= STATUS_ITEM_LIMIT) break;
            items.add(new StatusItem(e.displayTitle(), LinkUrls.hostLabel(e.url()), null, null));
        }
        List<StatusMetric> metrics = new ArrayList<>();
        metrics.add(new StatusMetric("Links", Integer.toString(config.entries().size())));
        int groups = config.orderedGroups().size();
        if (groups > 0) metrics.add(new StatusMetric("Groups", Integer.toString(groups)));

        int count = config.entries().size();
        String headline = count == 1 ? "1 link" : count + " links";
        return Optional.of(new AppStatus(headline, StatusSeverity.OK, metrics, items, null));
    }

    @Override
    public @Nullable String promptInject(PromptInjectContext ctx) {
        LinksConfig config;
        try {
            config = store.load(ctx.tenantId(), ctx.projectName(),
                    LinksStore.normaliseFolder(ctx.folder())).config();
        } catch (RuntimeException e) {
            return null;
        }
        String folder = LinksStore.normaliseFolder(ctx.folder());
        StringBuilder sb = new StringBuilder();
        sb.append("You are in a link list at `").append(folder)
                .append("` — a curated collection of external URLs (not project documents). ")
                .append(config.entries().size()).append(" link(s)");
        List<String> groups = config.orderedGroups();
        if (!groups.isEmpty()) {
            sb.append(", groups: ").append(String.join(", ", groups));
        }
        sb.append(".\n")
                .append("Add one with `links_entry_add(folder=\"").append(folder)
                .append("\", url=\"https://…\", group?, teaser?, tags?)`; the title is fetched ")
                .append("from the page automatically. Read the list with `links_list`, edit an ")
                .append("entry with `links_entry_update`, drop one with `links_entry_remove`.\n")
                .append("Teaser and picture are resolved live from the page unless somebody ")
                .append("typed their own — only write a teaser when asked for one.\n");
        appendSelection(sb, config, ctx.selection());
        return sb.toString();
    }

    /**
     * What the reader has clicked, so "this link" is a sentence the model can
     * act on without guessing.
     *
     * <p>The client sends only the URL. Everything shown here is read off the
     * manifest, because the manifest is what the tools will edit — a selection
     * that carried its own copy of the title could describe a row that no
     * longer says that. A URL that is no longer in the list is reported as
     * exactly that rather than silently dropped: the reader is still looking at
     * something, and "I see nothing selected" would be the wrong answer.
     *
     * <p><b>The wording avoids the word "selection" on purpose.</b> To a chat
     * engine that word means a character range in a document, and the first
     * version of this block said "the reader has this link selected" — the model
     * duly answered "I cannot read your selection, nothing was marked when you
     * sent", then recited the entry in scare quotes as if it were hearsay. The
     * search app never had that problem because it says a hit is "open". So:
     * name the act (clicked a card), say what it is not, and forbid the hedge.
     */
    private static void appendSelection(StringBuilder sb, LinksConfig config,
                                        @Nullable String selection) {
        if (selection == null || selection.isBlank()) return;
        String url = selection.trim();
        LinkEntry selected = null;
        for (LinkEntry e : config.entries()) {
            if (e.url().equals(url)) {
                selected = e;
                break;
            }
        }
        if (selected == null) {
            // Foreign text in a prompt: collapse it and cap it. It came from
            // our own client, but that is a reason to keep it tidy, not a
            // reason to trust its length.
            String shown = url.replaceAll("\\s+", " ");
            if (shown.length() > 300) shown = shown.substring(0, 300) + "…";
            sb.append("The reader has clicked a card whose link is no longer in this list: ")
                    .append(shown)
                    .append(" — say that plainly instead of listing the other entries.\n");
            return;
        }
        sb.append("The reader has clicked one card in this list. This IS what they mean by ")
                .append("\"this link\", \"the selected link\" or \"the entry I marked\" — it is the ")
                .append("app's own pick, NOT a text selection inside a document. Never answer ")
                .append("that no selection arrived, and never ask them to mark it again:\n")
                .append("- url: ").append(selected.url()).append('\n')
                .append("- title: ").append(selected.displayTitle()).append('\n');
        if (selected.group() != null) {
            sb.append("- group: ").append(selected.group()).append('\n');
        }
        if (!selected.tags().isEmpty()) {
            sb.append("- tags: ").append(String.join(", ", selected.tags())).append('\n');
        }
        if (selected.note() != null) {
            sb.append("- note (theirs): ").append(selected.note()).append('\n');
        }
        if (selected.teaser() != null) {
            sb.append("- teaser (theirs): ").append(selected.teaser()).append('\n');
        } else {
            sb.append("- teaser: none stored — the page's own description is shown live.\n");
        }
        sb.append("To read the page itself use `web_fetch` on that URL; the list stores no ")
                .append("page content.\n");
    }

    // ── the generated index ───────────────────────────────────────

    static String renderIndex(LinksConfig config, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(title)).append(" — Index\"\n");
        sb.append("description: \"Generated from the link list.\"\n");
        sb.append("---\n");
        sb.append("# ").append(title).append("\n\n");
        sb.append("```vance-callout\nseverity: note\ntitle: Auto-generated\n")
                .append("body: This page is rewritten on every `app_rebuild` — edits here are lost.\n")
                .append("```\n\n");

        if (config.entries().isEmpty()) {
            sb.append("No links yet.\n");
            return sb.toString();
        }

        List<String> sections = new ArrayList<>();
        sections.add("");                       // the ungrouped lead group
        sections.addAll(config.orderedGroups());
        for (String group : sections) {
            List<LinkEntry> rows = config.entriesOf(group);
            if (rows.isEmpty()) continue;
            if (!group.isEmpty()) sb.append("## ").append(group).append("\n\n");
            for (LinkEntry e : rows) {
                sb.append("- [").append(mdText(e.displayTitle())).append("](")
                        .append(e.url()).append(")");
                // Teaser then note, in that order and visibly different: the
                // teaser is what the page says about itself, the note is why
                // this list keeps it. The note is the part a reader wrote by
                // hand, so leaving it out of the one artefact that travels
                // (chat, embed, export) loses exactly the half that cannot be
                // recovered from the page.
                List<String> parts = new ArrayList<>(2);
                String teaser = e.teaser();
                if (teaser != null && !teaser.isBlank()) {
                    parts.add(mdText(oneLine(teaser)));
                }
                String note = e.note();
                if (note != null && !note.isBlank()) {
                    parts.add("*" + mdText(oneLine(note)) + "*");
                }
                if (!parts.isEmpty()) {
                    sb.append(" — ").append(String.join(" · ", parts));
                }
                if (!e.tags().isEmpty()) {
                    sb.append(" `").append(String.join("` `", e.tags())).append('`');
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Escape the characters that would turn borrowed text into markdown
     * structure. Most of it comes from somebody else's {@code og:title} or
     * {@code og:description}, so a {@code ]} in it would otherwise end the link
     * label early and leave the rest as loose text next to a broken URL.
     *
     * <p>{@code *} and {@code _} are escaped too, and that matters more since
     * the note is wrapped in {@code *…*}: a single asterisk inside would close
     * the emphasis and italicise the rest of the line.
     */
    private static String mdText(String s) {
        return s.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("<", "&lt;");
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
