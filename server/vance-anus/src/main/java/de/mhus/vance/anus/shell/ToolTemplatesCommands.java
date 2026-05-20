package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.api.kit.ToolTemplatesScanRequestDto;
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
 * Anus commands for the tenant-wide tool-templates catalog
 * ({@code _tenant/config/tool-templates.yaml}). Three operations,
 * mirroring {@link ProjectKitsCommands}:
 *
 * <ul>
 *   <li>{@code tool-templates show}   — display the current tenant catalog.</li>
 *   <li>{@code tool-templates import} — first-time bootstrap from a git
 *       repo's {@code tools/} subdir; refuses to run if the tenant
 *       already has a catalog.</li>
 *   <li>{@code tool-templates update} — refresh from a git repo.
 *       {@code --mode merge} (default) upserts by {@code name} and keeps
 *       tenant-only entries; {@code --mode overwrite} replaces the
 *       catalog wholesale. {@code --dry-run} prints the diff without
 *       saving.</li>
 * </ul>
 *
 * <p>Talks to Brain via {@link AnusBrainClient} — clone, diff, save are
 * carried out by endpoints under
 * {@code /brain/{tenant}/admin/tool-templates/...}.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class ToolTemplatesCommands {

    /** Default tool-templates source repo when {@code --git} is omitted. */
    public static final String DEFAULT_REPO =
            "https://github.com/mhus/vance-kits.git";

    /** Default branch when {@code --ref} is omitted. */
    public static final String DEFAULT_REF = "main";

    /** Mode token for {@code update}: per-name upsert, keep tenant extras. */
    public static final String MODE_MERGE = "merge";

    /** Mode token for {@code update}: full replace. */
    public static final String MODE_OVERWRITE = "overwrite";

    private static final String BASE_PATH = "/admin/tool-templates";

    private final AnusBrainClient brainClient;
    // Anus runs as a Spring Shell app without spring-boot-starter-web, so
    // there's no auto-registered Jackson 3 ObjectMapper bean to inject.
    // Construct one locally — matches the pattern used by ProjectKitsCommands.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @ShellMethod(key = "tool-templates show",
            value = "Display the tenant's tool-templates catalog.")
    public String show(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        Response response = brainClient.get(tenant, "/brain/" + tenant + BASE_PATH + "/catalog");
        if (!response.isSuccess()) {
            return "Show FAILED — HTTP " + response.statusCode() + "\n" + response.body();
        }
        ToolTemplateCatalogDto catalog = decode(response.body());
        return renderCatalog(tenant, catalog);
    }

    @ShellMethod(key = "tool-templates import",
            value = "Bootstrap the tenant's tool-templates catalog from a git repo. Fails if one already exists.")
    public String importCatalog(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--git"}, defaultValue = DEFAULT_REPO) String git,
            @ShellOption(value = {"--ref"}, defaultValue = DEFAULT_REF) String ref,
            @ShellOption(value = {"--token"}, defaultValue = ShellOption.NULL,
                    help = "Optional credential for private repos.")
            @Nullable String token) {
        ToolTemplateCatalogDto existing = loadCatalog(tenant);
        if (existing != null && existing.getTemplates() != null && !existing.getTemplates().isEmpty()) {
            return "Import REFUSED — tenant '" + tenant + "' already has "
                    + existing.getTemplates().size() + " catalog entries. "
                    + "Use 'tool-templates update' to refresh.";
        }
        ToolTemplateCatalogDto scanned = scan(tenant, git, ref, token);
        if (scanned == null) {
            return "Import FAILED — scan request did not succeed (see log).";
        }
        Response saved = saveCatalog(tenant, scanned);
        if (!saved.isSuccess()) {
            return "Import FAILED on save — HTTP " + saved.statusCode() + "\n" + saved.body();
        }
        return "Import OK — wrote " + scanned.getTemplates().size() + " entries to tenant '"
                + tenant + "'.\n" + renderCatalog(tenant, scanned);
    }

    @ShellMethod(key = "tool-templates update",
            value = "Refresh the tenant's tool-templates catalog from a git repo (merge or overwrite).")
    public String update(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--git"}, defaultValue = DEFAULT_REPO) String git,
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

        ToolTemplateCatalogDto scanned = scan(tenant, git, ref, token);
        if (scanned == null) {
            return "Update FAILED — scan request did not succeed (see log).";
        }
        ToolTemplateCatalogDto existing = loadCatalog(tenant);
        if (existing == null) {
            existing = ToolTemplateCatalogDto.builder().version(1).templates(new ArrayList<>()).build();
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
        return out + "\nUpdate OK — " + merged.result.getTemplates().size() + " entries persisted.";
    }

    // ──────────────────── helpers: brain calls ────────────────────

    private @Nullable ToolTemplateCatalogDto loadCatalog(String tenant) {
        Response response = brainClient.get(tenant, "/brain/" + tenant + BASE_PATH + "/catalog");
        if (!response.isSuccess()) {
            return null;
        }
        return decode(response.body());
    }

    private @Nullable ToolTemplateCatalogDto scan(
            String tenant, String git, String ref, @Nullable String token) {
        ToolTemplatesScanRequestDto body = ToolTemplatesScanRequestDto.builder()
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

    private Response saveCatalog(String tenant, ToolTemplateCatalogDto catalog) {
        return brainClient.put(tenant, "/brain/" + tenant + BASE_PATH + "/catalog", encode(catalog));
    }

    // ──────────────────── helpers: merge ────────────────────

    static MergeResult merge(ToolTemplateCatalogDto scanned, ToolTemplateCatalogDto existing) {
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (ToolTemplateCatalogEntry e : existing.getTemplates()) kept.add(e.getName());

        List<ToolTemplateCatalogEntry> result = new ArrayList<>(existing.getTemplates());
        for (ToolTemplateCatalogEntry incoming : scanned.getTemplates()) {
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
        List<String> removed = List.of();
        return new MergeResult(
                ToolTemplateCatalogDto.builder()
                        .version(scanned.getVersion())
                        .templates(result)
                        .build(),
                added, updated, kept, removed);
    }

    static MergeResult overwrite(ToolTemplateCatalogDto scanned, ToolTemplateCatalogDto existing) {
        Map<String, ToolTemplateCatalogEntry> existingByName = new LinkedHashMap<>();
        for (ToolTemplateCatalogEntry e : existing.getTemplates()) existingByName.put(e.getName(), e);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (ToolTemplateCatalogEntry e : scanned.getTemplates()) {
            ToolTemplateCatalogEntry prev = existingByName.remove(e.getName());
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

    private static int indexOfName(List<ToolTemplateCatalogEntry> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (name.equals(list.get(i).getName())) return i;
        }
        return -1;
    }

    private static boolean equalForDiff(ToolTemplateCatalogEntry a, ToolTemplateCatalogEntry b) {
        return strEq(a.getTitle(), b.getTitle())
                && strEq(a.getDescription(), b.getDescription())
                && strEq(a.getCategory(), b.getCategory())
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

    private static String renderCatalog(String tenant, ToolTemplateCatalogDto catalog) {
        if (catalog.getTemplates() == null || catalog.getTemplates().isEmpty()) {
            return "(tool-templates catalog for tenant '" + tenant + "' is empty)";
        }
        StringBuilder out = new StringBuilder();
        out.append("Tenant '").append(tenant).append("' — ")
                .append(catalog.getTemplates().size()).append(" entries (version ")
                .append(catalog.getVersion()).append("):\n");
        for (ToolTemplateCatalogEntry e : catalog.getTemplates()) {
            out.append("  - ").append(e.getName())
                    .append("  [").append(StringUtils.defaultString(e.getTitle())).append("]");
            if (!StringUtils.isBlank(e.getCategory())) {
                out.append("  category=").append(e.getCategory());
            }
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

    private ToolTemplateCatalogDto decode(String json) {
        try {
            return objectMapper.readValue(json, ToolTemplateCatalogDto.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to decode catalog json: " + e.getMessage(), e);
        }
    }

    /** Result of a {@link #merge}/{@link #overwrite} step. Package-private for tests. */
    record MergeResult(
            ToolTemplateCatalogDto result,
            List<String> added,
            List<String> updated,
            List<String> kept,
            List<String> removed) {}
}
