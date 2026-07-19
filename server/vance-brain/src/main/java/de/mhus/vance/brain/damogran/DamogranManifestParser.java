package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.OutputSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses a Damogran compose manifest (YAML text) into a {@link DamogranManifest}.
 *
 * <p>Pure, stateless, fail-fast. Registered as a bean so the runner can inject
 * it, but has no dependencies of its own, so it stays unit-testable via
 * {@code new DamogranManifestParser()}. Malformed input raises
 * {@link DamogranException}. See {@link DamogranManifest} for the YAML shape.
 */
@Component
public class DamogranManifestParser {

    private static final Set<String> ALLOWED_TARGETS = Set.of("CLIENT", "WORK", "DAEMON");

    @SuppressWarnings("unchecked")
    public DamogranManifest parse(@Nullable String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new DamogranException("compose manifest is empty");
        }
        Map<String, Object> root;
        try {
            root = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            throw new DamogranException("compose manifest is not valid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new DamogranException("compose manifest is empty");
        }

        WorkspaceSpec workspace = parseWorkspace(root.get("workspace"));
        List<ImportEntry> imports = parseImports(root.get("import"));
        List<TaskSpec> tasks = parseTasks(root.get("tasks"));
        List<ExportEntry> exports = parseExports(root.get("export"));

        return new DamogranManifest(workspace, imports, tasks, exports,
                readString(root, "title"), readString(root, "description"));
    }

    // ──────────────────── workspace ────────────────────

    @SuppressWarnings("unchecked")
    private WorkspaceSpec parseWorkspace(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new DamogranException("compose manifest: 'workspace' is required and must be a mapping");
        }
        Map<String, Object> w = (Map<String, Object>) map;

        String name = readString(w, "name");
        if (name == null) {
            throw new DamogranException("compose manifest: 'workspace.name' is required");
        }

        String type = readString(w, "type");
        if (type == null) {
            type = WorkspaceSpec.DEFAULT_TYPE;
        }

        boolean clear = Boolean.TRUE.equals(w.get("clear"));

        Object optionsRaw = w.get("options");
        Map<String, Object> options;
        if (optionsRaw == null) {
            options = Map.of();
        } else if (optionsRaw instanceof Map<?, ?> optMap) {
            options = Map.copyOf((Map<String, Object>) optMap);
        } else {
            throw new DamogranException("compose manifest: 'workspace.options' must be a mapping");
        }

        String target = readString(w, "target");
        if (target == null) {
            target = WorkspaceSpec.DEFAULT_TARGET;
        } else {
            target = target.toUpperCase(Locale.ROOT);
            if (!ALLOWED_TARGETS.contains(target)) {
                throw new DamogranException(
                        "compose manifest: 'workspace.target' must be one of CLIENT, WORK, DAEMON (was: " + target + ")");
            }
        }

        return new WorkspaceSpec(name, type, clear, options, target);
    }

    // ──────────────────── import / export ────────────────────

    private List<ImportEntry> parseImports(@Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new DamogranException("compose manifest: 'import' must be a list");
        }
        List<ImportEntry> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = requireMap(item, "import");
            String from = readString(map, "from");
            String to = readString(map, "to");
            if (from == null) {
                throw new DamogranException("compose manifest: import entry requires 'from'");
            }
            if (to == null) {
                throw new DamogranException("compose manifest: import entry requires 'to'");
            }
            result.add(new ImportEntry(from, to, options(map)));
        }
        return List.copyOf(result);
    }

    /** Scheme-specific extra fields (everything except {@code from}/{@code to}). */
    private static Map<String, Object> options(Map<String, Object> entry) {
        Map<String, Object> opts = new LinkedHashMap<>(entry);
        opts.remove("from");
        opts.remove("to");
        return Map.copyOf(opts);
    }

    private List<ExportEntry> parseExports(@Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new DamogranException("compose manifest: 'export' must be a list");
        }
        List<ExportEntry> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = requireMap(item, "export");
            String from = readString(map, "from");
            String to = readString(map, "to");
            if (from == null) {
                throw new DamogranException("compose manifest: export entry requires 'from'");
            }
            if (to == null) {
                throw new DamogranException("compose manifest: export entry requires 'to'");
            }
            result.add(new ExportEntry(from, to, options(map)));
        }
        return List.copyOf(result);
    }

    // ──────────────────── tasks ────────────────────

    private List<TaskSpec> parseTasks(@Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new DamogranException("compose manifest: 'tasks' must be a list");
        }
        List<TaskSpec> result = new ArrayList<>();
        for (Object item : list) {
            result.add(parseTask(item));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private TaskSpec parseTask(Object item) {
        Map<String, Object> map = requireMap(item, "tasks");
        String type = readString(map, "type");
        if (type == null) {
            throw new DamogranException("compose manifest: each task requires a 'type'");
        }

        List<OutputSpec> outputs = parseOutputs(map);

        // params = everything except the reserved keys, verbatim for the bean.
        Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) map);
        params.remove("type");
        params.remove("outputs");

        return new TaskSpec(type, Map.copyOf(params), outputs);
    }

    /**
     * Reads declared outputs from either {@code output:} (a single path string)
     * or {@code outputs:} (a list of path strings or {path, kind/as, title}
     * maps). Both may be present; {@code output} is prepended.
     */
    @SuppressWarnings("unchecked")
    private List<OutputSpec> parseOutputs(Map<String, Object> task) {
        List<OutputSpec> result = new ArrayList<>();

        String single = readString(task, "output");
        if (single != null) {
            result.add(new OutputSpec(single, null, null));
        }

        Object raw = task.get("outputs");
        if (raw != null) {
            if (!(raw instanceof List<?> list)) {
                throw new DamogranException("compose manifest: task 'outputs' must be a list");
            }
            for (Object o : list) {
                if (o instanceof String s) {
                    if (s.isBlank()) {
                        throw new DamogranException("compose manifest: task 'outputs' entries must be non-empty");
                    }
                    result.add(new OutputSpec(s.trim(), null, null));
                } else if (o instanceof Map<?, ?> m) {
                    Map<String, Object> om = (Map<String, Object>) m;
                    String path = readString(om, "path");
                    if (path == null) {
                        throw new DamogranException("compose manifest: task output entry requires 'path'");
                    }
                    String kind = readString(om, "kind");
                    if (kind == null) {
                        kind = readString(om, "as");
                    }
                    result.add(new OutputSpec(path, kind, readString(om, "title")));
                } else {
                    throw new DamogranException(
                            "compose manifest: task 'outputs' entries must be strings or maps");
                }
            }
        }
        return List.copyOf(result);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireMap(@Nullable Object item, String context) {
        if (item instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new DamogranException("compose manifest: '" + context + "' entries must be mappings");
    }

    private static @Nullable String readString(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }
}
