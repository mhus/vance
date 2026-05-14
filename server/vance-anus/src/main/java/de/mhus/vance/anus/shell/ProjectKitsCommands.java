package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.api.kit.ProjectKitsScanRequestDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anus commands for the tenant-wide project-kits catalog
 * ({@code _vance/config/project-kits.yaml}). Three operations:
 *
 * <ul>
 *   <li>{@code project-kits show}   — display the current tenant catalog.</li>
 *   <li>{@code project-kits import} — first-time bootstrap from a git
 *       repo; refuses to run if the tenant already has a catalog.</li>
 *   <li>{@code project-kits update} — refresh from a git repo. {@code
 *       --mode merge} (default) upserts by {@code name} and keeps
 *       tenant-only entries; {@code --mode overwrite} replaces the
 *       catalog wholesale. {@code --dry-run} prints the diff without
 *       saving.</li>
 * </ul>
 *
 * <p>Talks to Brain via {@link AnusBrainClient} — clone, diff, save are
 * all carried out by Brain endpoints under
 * {@code /brain/{tenant}/admin/project-kits/...}.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §7.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class ProjectKitsCommands {

    /** Default catalog source repo when {@code --git} is omitted. */
    public static final String DEFAULT_KIT_REPO =
            "https://github.com/mhus/vance-kits.git";

    /** Default branch when {@code --ref} is omitted. */
    public static final String DEFAULT_REF = "main";

    /** Mode token for {@code update}: per-name upsert, keep tenant extras. */
    public static final String MODE_MERGE = "merge";

    /** Mode token for {@code update}: full replace. */
    public static final String MODE_OVERWRITE = "overwrite";

    private static final String BASE_PATH = "/admin/project-kits";

    private final AnusBrainClient brainClient;
    // Anus runs as a Spring Shell app without spring-boot-starter-web, so
    // there's no auto-registered Jackson 3 ObjectMapper bean to inject.
    // Construct one locally — matches the pattern used by other anus/brain
    // code paths (HookHttpClient, AccessController) that need JSON outside
    // a web context.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @ShellMethod(key = "project-kits show", value = "Display the tenant's project-kits catalog.")
    public String show(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        Response response = brainClient.get(tenant, "/brain/" + tenant + BASE_PATH + "/catalog");
        if (!response.isSuccess()) {
            return "Show FAILED — HTTP " + response.statusCode() + "\n" + response.body();
        }
        ProjectKitsCatalogDto catalog = decode(response.body());
        return renderCatalog(tenant, catalog);
    }

    @ShellMethod(key = "project-kits import",
            value = "Bootstrap the tenant's project-kits catalog from a git repo. Fails if one already exists.")
    public String importCatalog(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--git"}, defaultValue = DEFAULT_KIT_REPO) String git,
            @ShellOption(value = {"--ref"}, defaultValue = DEFAULT_REF) String ref,
            @ShellOption(value = {"--token"}, defaultValue = ShellOption.NULL,
                    help = "Optional credential for private repos.")
            @Nullable String token) {
        ProjectKitsCatalogDto existing = loadCatalog(tenant);
        if (existing != null && existing.getKits() != null && !existing.getKits().isEmpty()) {
            return "Import REFUSED — tenant '" + tenant + "' already has "
                    + existing.getKits().size() + " catalog entries. "
                    + "Use 'project-kits update' to refresh.";
        }
        ProjectKitsCatalogDto scanned = scan(tenant, git, ref, token);
        if (scanned == null) {
            return "Import FAILED — scan request did not succeed (see log).";
        }
        Response saved = saveCatalog(tenant, scanned);
        if (!saved.isSuccess()) {
            return "Import FAILED on save — HTTP " + saved.statusCode() + "\n" + saved.body();
        }
        return "Import OK — wrote " + scanned.getKits().size() + " entries to tenant '"
                + tenant + "'.\n" + renderCatalog(tenant, scanned);
    }

    @ShellMethod(key = "project-kits update",
            value = "Refresh the tenant's project-kits catalog from a git repo (merge or overwrite).")
    public String update(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--git"}, defaultValue = DEFAULT_KIT_REPO) String git,
            @ShellOption(value = {"--ref"}, defaultValue = DEFAULT_REF) String ref,
            @ShellOption(value = {"--mode"}, defaultValue = MODE_MERGE,
                    help = "merge (default) | overwrite") String mode,
            @ShellOption(value = {"--dry-run"}, defaultValue = "false") boolean dryRun,
            @ShellOption(value = {"--token"}, defaultValue = ShellOption.NULL,
                    help = "Optional credential for private repos.")
            @Nullable String token) {
        String normalizedMode = mode == null ? MODE_MERGE : mode.toLowerCase(Locale.ROOT);
        if (!MODE_MERGE.equals(normalizedMode) && !MODE_OVERWRITE.equals(normalizedMode)) {
            return "Update FAILED — unknown --mode '" + mode + "', expected '"
                    + MODE_MERGE + "' or '" + MODE_OVERWRITE + "'.";
        }

        ProjectKitsCatalogDto scanned = scan(tenant, git, ref, token);
        if (scanned == null) {
            return "Update FAILED — scan request did not succeed (see log).";
        }
        ProjectKitsCatalogDto existing = loadCatalog(tenant);
        if (existing == null) {
            existing = ProjectKitsCatalogDto.builder().version(1).kits(new ArrayList<>()).build();
        }

        MergeResult merged = MODE_OVERWRITE.equals(normalizedMode)
                ? overwrite(scanned, existing)
                : merge(scanned, existing);

        StringBuilder out = new StringBuilder();
        out.append("Update ").append(dryRun ? "DRY-RUN " : "").append("mode=").append(normalizedMode)
                .append(" tenant='").append(tenant).append("' git='").append(git)
                .append("' ref='").append(ref).append("'\n");
        out.append(renderDiff(merged));

        if (dryRun) {
            return out.toString() + "\n(no changes written)";
        }

        Response saved = saveCatalog(tenant, merged.result);
        if (!saved.isSuccess()) {
            return out + "\nUpdate FAILED on save — HTTP " + saved.statusCode() + "\n"
                    + saved.body();
        }
        return out + "\nUpdate OK — " + merged.result.getKits().size() + " entries persisted.";
    }

    // ──────────────────── helpers: brain calls ────────────────────

    private @Nullable ProjectKitsCatalogDto loadCatalog(String tenant) {
        Response response = brainClient.get(tenant, "/brain/" + tenant + BASE_PATH + "/catalog");
        if (!response.isSuccess()) {
            return null;
        }
        return decode(response.body());
    }

    private @Nullable ProjectKitsCatalogDto scan(
            String tenant, String git, String ref, @Nullable String token) {
        ProjectKitsScanRequestDto body = ProjectKitsScanRequestDto.builder()
                .gitUrl(git)
                .ref(ref)
                .token(token)
                .build();
        String json = encode(body);
        Response response = brainClient.post(tenant, "/brain/" + tenant + BASE_PATH + "/scan", json);
        if (!response.isSuccess()) {
            return null;
        }
        return decode(response.body());
    }

    private Response saveCatalog(String tenant, ProjectKitsCatalogDto catalog) {
        return brainClient.put(tenant, "/brain/" + tenant + BASE_PATH + "/catalog", encode(catalog));
    }

    // ──────────────────── helpers: merge ────────────────────

    static MergeResult merge(ProjectKitsCatalogDto scanned, ProjectKitsCatalogDto existing) {
        Map<String, ProjectKitEntry> byName = new LinkedHashMap<>();
        for (ProjectKitEntry e : existing.getKits()) byName.put(e.getName(), e);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> kept = new ArrayList<>(byName.keySet());

        // Walk scanned in declared order; updates win over kept; new go to the end.
        List<ProjectKitEntry> result = new ArrayList<>();
        for (ProjectKitEntry e : existing.getKits()) {
            result.add(e); // start from existing order
        }
        for (ProjectKitEntry incoming : scanned.getKits()) {
            int idx = indexOfName(result, incoming.getName());
            if (idx >= 0) {
                if (!equalForDiff(result.get(idx), incoming)) {
                    result.set(idx, incoming);
                    updated.add(incoming.getName());
                }
                kept.remove(incoming.getName());
            } else {
                result.add(incoming);
                added.add(incoming.getName());
            }
        }
        // Tenant-only entries that have no scanned counterpart stay where they are.
        List<String> removed = List.of();
        return new MergeResult(
                ProjectKitsCatalogDto.builder()
                        .version(scanned.getVersion())
                        .kits(result)
                        .build(),
                added, updated, kept, removed);
    }

    static MergeResult overwrite(ProjectKitsCatalogDto scanned, ProjectKitsCatalogDto existing) {
        Map<String, ProjectKitEntry> existingByName = new LinkedHashMap<>();
        for (ProjectKitEntry e : existing.getKits()) existingByName.put(e.getName(), e);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (ProjectKitEntry e : scanned.getKits()) {
            ProjectKitEntry prev = existingByName.remove(e.getName());
            if (prev == null) {
                added.add(e.getName());
            } else if (!equalForDiff(prev, e)) {
                updated.add(e.getName());
            } else {
                kept.add(e.getName());
            }
        }
        List<String> removed = new ArrayList<>(existingByName.keySet());
        return new MergeResult(scanned, added, updated, kept, removed);
    }

    private static int indexOfName(List<ProjectKitEntry> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (name.equals(list.get(i).getName())) return i;
        }
        return -1;
    }

    private static boolean equalForDiff(ProjectKitEntry a, ProjectKitEntry b) {
        return strEq(a.getTitle(), b.getTitle())
                && strEq(a.getDescription(), b.getDescription())
                && sourceEq(a.getSource(), b.getSource());
    }

    private static boolean sourceEq(@Nullable KitInheritDto a, @Nullable KitInheritDto b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return strEq(a.getUrl(), b.getUrl())
                && strEq(a.getPath(), b.getPath())
                && strEq(a.getBranch(), b.getBranch())
                && strEq(a.getCommit(), b.getCommit());
    }

    private static boolean strEq(@Nullable String a, @Nullable String b) {
        String na = a == null ? "" : a;
        String nb = b == null ? "" : b;
        return na.equals(nb);
    }

    // ──────────────────── helpers: rendering ────────────────────

    private static String renderCatalog(String tenant, ProjectKitsCatalogDto catalog) {
        if (catalog.getKits() == null || catalog.getKits().isEmpty()) {
            return "(catalog for tenant '" + tenant + "' is empty)";
        }
        StringBuilder out = new StringBuilder();
        out.append("Tenant '").append(tenant).append("' — ")
                .append(catalog.getKits().size()).append(" entries (version ")
                .append(catalog.getVersion()).append("):\n");
        for (ProjectKitEntry e : catalog.getKits()) {
            out.append("  - ").append(e.getName())
                    .append("  [").append(StringUtils.defaultString(e.getTitle())).append("]");
            if (e.getSource() != null) {
                out.append("\n    url    : ").append(StringUtils.defaultString(e.getSource().getUrl()));
                if (!StringUtils.isBlank(e.getSource().getPath())) {
                    out.append("\n    path   : ").append(e.getSource().getPath());
                }
                if (!StringUtils.isBlank(e.getSource().getBranch())) {
                    out.append("\n    branch : ").append(e.getSource().getBranch());
                }
                if (!StringUtils.isBlank(e.getSource().getCommit())) {
                    out.append("\n    commit : ").append(e.getSource().getCommit());
                }
            }
            if (!StringUtils.isBlank(e.getDescription())) {
                out.append("\n    desc   : ").append(e.getDescription());
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static String renderDiff(MergeResult merged) {
        StringBuilder out = new StringBuilder();
        out.append("  added   : ").append(merged.added).append("\n");
        out.append("  updated : ").append(merged.updated).append("\n");
        out.append("  kept    : ").append(merged.kept).append("\n");
        out.append("  removed : ").append(merged.removed).append("\n");
        return out.toString();
    }

    // ──────────────────── helpers: codec ────────────────────

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to encode " + value.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private ProjectKitsCatalogDto decode(String json) {
        try {
            return objectMapper.readValue(json, ProjectKitsCatalogDto.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to decode catalog json: " + e.getMessage(), e);
        }
    }

    /** Result of a {@link #merge}/{@link #overwrite} step. Package-private for tests. */
    record MergeResult(
            ProjectKitsCatalogDto result,
            List<String> added,
            List<String> updated,
            List<String> kept,
            List<String> removed) {}
}
