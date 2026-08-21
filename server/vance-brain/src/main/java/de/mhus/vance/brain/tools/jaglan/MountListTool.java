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
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
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

    @Override public String name() { return "mount_list"; }

    @Override public String description() {
        return "List mounted external sources, or browse inside one. Mounted documents live under "
                + "'_ext/<mount>/…' and are NOT found by doc_search or doc_list_in_folder, which "
                + "only scan 'documents/' — use this to discover them. Omit `path` for the list of "
                + "mounts; pass a folder path for its contents. Read the files themselves with the "
                + "ordinary doc_read once you know their path.";
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

        if (!JaglanPaths.isMounted(path)) {
            out.put("error", "path must be inside the mount namespace, e.g. '"
                    + JaglanPaths.PREFIX + "<mount>/…'");
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
            // A directory shell row carries neither mime nor size, the same
            // shape MountedStat enforces — so their absence is the marker.
            boolean folder = doc.getMimeType() == null && doc.getSize() == 0;
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
        return out;
    }
}
