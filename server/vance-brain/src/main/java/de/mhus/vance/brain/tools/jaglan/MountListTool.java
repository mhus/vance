package de.mhus.vance.brain.tools.jaglan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Discover what is mounted, and browse inside a mount.
 *
 * <p>Needed because mounted documents are invisible to the ordinary document
 * search: the default scope of {@code doc_*} is {@code documents/}, and
 * {@code _ext/} deliberately falls outside it — a foreign library must not turn
 * up in every search for a note. So an agent has to be told the namespace
 * exists, and this is the tool that tells it.
 *
 * <p>Without {@code path} it lists the mounts; with one it lists that folder's
 * direct children, refreshing from the source when the cached listing has aged
 * out. {@code refresh=true} forces the refresh — per folder, because a mount
 * can be large.
 */
@Component
@RequiredArgsConstructor
public class MountListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "path", Map.of("type", "string",
                            "description", "Folder to list, e.g. '_ext/library' or "
                                    + "'_ext/library/books'. Omit to list the configured mounts "
                                    + "instead of a folder's contents."),
                    "refresh", Map.of("type", "boolean",
                            "description", "Re-read this folder from the source even if the "
                                    + "cached listing is still valid. Default: false.")),
            "required", List.of());

    private final KindToolSupport support;

    /**
     * For the folder-scoped failure record only.
     *
     * <p>The rows of a folder whose last refresh failed are still returned —
     * that is the point of keeping them — but they are older than they look,
     * and the per-mount {@code statusText} does not cover it: that one comes
     * from the capabilities cache, so a source that describes itself happily
     * and cannot list <i>this</i> folder reports nothing at all.
     */
    private final ObjectProvider<JaglanShellService> shellServiceProvider;

    @Override public String name() { return "mount_list"; }

    @Override public String description() {
        return "List mounted external sources, or browse inside one. Mounted documents live under "
                + "'_ext/<mount>/…' and are NOT found by doc_find, doc_grep, memory_search or "
                + "doc_list_in_folder, which only scan 'documents/' — use this to discover them. "
                + "Omit `path` for the list of mounts; pass a folder path for its contents. Read "
                + "the files themselves with the ordinary doc_read once you know their path.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only", "mount", "documents"); }

    @Override public String searchHint() {
        return "Find files in an external source mounted into this project";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String path = KindToolSupport.paramString(params, "path");
        boolean refresh = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "refresh"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());

        if (path == null || path.isBlank()) {
            List<MountedSource> mounts = support.documentService()
                    .listMounts(ctx.tenantId(), project.getName());
            List<Map<String, Object>> rows = new ArrayList<>(mounts.size());
            for (MountedSource mount : mounts) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("mount", mount.name());
                r.put("path", JaglanPaths.mountRootPath(mount.name()));
                if (mount.displayName() != null) r.put("title", mount.displayName());
                r.put("access", mount.access().name());
                if (mount.itemCount() != null) r.put("itemCount", mount.itemCount());
                // Surfaced rather than swallowed: an agent told "0 results"
                // by an unreachable source would conclude the file is absent.
                if (mount.statusText() != null) r.put("status", mount.statusText());
                rows.add(r);
            }
            out.put("count", rows.size());
            out.put("mounts", rows);
            if (rows.isEmpty()) {
                out.put("hint", "This project has no mounted external sources.");
            }
            return out;
        }

        String mountName;
        String folderInMount;
        try {
            // Checked, not caught downstream: '_ext/' alone is inside the
            // namespace but names no mount, and the document layer answers
            // that with an IllegalArgumentException rather than a tool error.
            mountName = JaglanPaths.mountNameOf(path);
            folderInMount = JaglanPaths.pathInMount(path);
        } catch (IllegalArgumentException e) {
            out.put("error", "path must name a mount, e.g. '"
                    + JaglanPaths.PREFIX + "<mount>/…' — omit `path` to list the mounts");
            return out;
        }

        List<DocumentDocument> entries = support.documentService()
                .listMountedFolder(ctx.tenantId(), project.getName(), path, refresh);
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (DocumentDocument doc : entries) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", doc.getPath());
            r.put("name", doc.getName());
            if (doc.getTitle() != null) r.put("title", doc.getTitle());
            boolean folder = doc.isMountDirectory();
            r.put("folder", folder);
            if (!folder) {
                r.put("size", doc.getSize());
                if (doc.getMimeType() != null) r.put("mimeType", doc.getMimeType());
            }
            if (doc.getMountAccess() != null) r.put("access", doc.getMountAccess().name());
            rows.add(r);
        }
        out.put("path", path);
        out.put("count", rows.size());
        out.put("entries", rows);

        // Say when the listing is older than it looks. Without this a folder
        // whose refresh failed is indistinguishable from a fresh one, and an
        // agent reads a stale — possibly empty — listing as the truth.
        JaglanShellService shellService = shellServiceProvider.getIfAvailable();
        JaglanShellService.FolderFailure failure = shellService == null ? null
                : shellService.folderFailure(
                        ctx.tenantId(), project.getName(), mountName, folderInMount);
        if (failure != null) {
            out.put("status", "last refresh failed: " + failure.message()
                    + " — these entries may be out of date");
            out.put("staleSince", failure.at().toString());
        }
        return out;
    }
}
