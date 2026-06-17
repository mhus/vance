package de.mhus.vance.shared.servertool;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware server-tool loader. Reads one YAML per tool through
 * {@link DocumentService#listByPrefixCascade} / {@link DocumentService#lookupCascade}:
 * {@code project → _vance → classpath(vance-defaults/server-tools/<name>.yaml)}.
 *
 * <p>Parse errors on individual entries are surfaced to the caller via
 * {@link ServerToolParseException}; bulk listings ({@link #listAll}) log
 * and skip — a single broken doc must not poison the project bootstrap.
 *
 * <p>No in-memory cache. The registry holds the resolved result; this
 * loader is a pure YAML→DTO layer.
 *
 * <p>Validation is intentionally cheap: YAML must parse, {@code name},
 * {@code type} and {@code description} must be present. The factory's
 * {@code parametersSchema()} is <b>not</b> checked here — a misconfigured
 * tool surfaces on first lookup with a factory-specific error message
 * (lazy-failure, matching the historical behaviour).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerToolLoader {

    /** Path prefix used for server-tool documents in any cascade tier. */
    public static final String SERVER_TOOL_PATH_PREFIX = "_vance/server-tools/";

    /** File suffix kept on the document path; the tool name does not carry it. */
    public static final String SERVER_TOOL_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Resolve a single tool by name in the project/_vance/resource
     * cascade. Returns empty if no tier carries it.
     */
    public Optional<ServerToolConfig> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String normalized = normalizedName(name);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId(projectId), pathFor(normalized));
        if (hit.isEmpty()) return Optional.empty();
        try {
            return Optional.of(parse(normalized, hit.get()));
        } catch (RuntimeException e) {
            throw new ServerToolParseException(
                    "Failed to parse server-tool '" + normalized + "' from "
                            + hit.get().source() + " at path '" + hit.get().path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Tools that live ONLY in {@code projectId}'s document layer — no
     * cascade, no classpath fallback. Used by insights views that need
     * to attribute each entry to its owning layer.
     */
    public List<ServerToolConfig> loadInProject(String tenantId, String projectId) {
        List<DocumentDocument> docs = documentService.listByProject(tenantId, projectId);
        List<ServerToolConfig> out = new ArrayList<>();
        for (DocumentDocument doc : docs) {
            String path = doc.getPath();
            if (path == null) continue;
            String name = nameFromPath(path);
            if (name == null) continue;
            LookupResult.Source source =
                    HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                            ? LookupResult.Source.VANCE
                            : LookupResult.Source.PROJECT;
            LookupResult hit = new LookupResult(path, documentService.readContent(doc), source, doc);
            try {
                out.add(parse(name, hit));
            } catch (RuntimeException ex) {
                log.warn("ServerToolLoader: skipping malformed tool path='{}' source={}: {}",
                        path, source, ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Every tool visible to the project, cascade-merged. Project entries
     * override {@code _vance} entries by name; both override classpath
     * defaults. Malformed entries are logged and skipped — the rest of
     * the bootstrap continues.
     */
    public List<ServerToolConfig> listAll(String tenantId, @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, effectiveProjectId(projectId), SERVER_TOOL_PATH_PREFIX);
        List<ServerToolConfig> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            try {
                out.add(parse(name, e.getValue()));
            } catch (RuntimeException ex) {
                log.warn("ServerToolLoader: skipping malformed tool path='{}' source={}: {}",
                        path, e.getValue().source(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Validate a YAML body without persisting. Used by the admin REST
     * controller before writing a tool document, so malformed input
     * never reaches the document layer.
     *
     * <p>Sanity-check only: YAML must parse, {@code name}/{@code type}/
     * {@code description} must be present. The factory's
     * {@code parametersSchema()} is not consulted here — that check is
     * deferred to first lookup.
     *
     * @throws ServerToolParseException with a field-level error message
     */
    public ServerToolConfig validateYaml(String name, String yaml) {
        String norm = normalizedName(name);
        try {
            return parse(norm, syntheticHit(norm, yaml));
        } catch (RuntimeException ex) {
            throw new ServerToolParseException(
                    "server-tool YAML invalid: " + ex.getMessage(), ex);
        }
    }

    /** Compose the document path for {@code name}. */
    public static String pathFor(String name) {
        return SERVER_TOOL_PATH_PREFIX + normalizedName(name) + SERVER_TOOL_PATH_SUFFIX;
    }

    /** Normalised, lowercase tool name. */
    public static String normalizedName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME : projectId;
    }

    /**
     * Inverse of {@link #pathFor}. Returns the tool-name encoded in a
     * server-tool document path, or {@code null} if the path doesn't match
     * the expected {@code <prefix><name><suffix>} shape.
     */
    public static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(SERVER_TOOL_PATH_PREFIX)) return null;
        if (!path.endsWith(SERVER_TOOL_PATH_SUFFIX)) return null;
        String stem = path.substring(
                SERVER_TOOL_PATH_PREFIX.length(),
                path.length() - SERVER_TOOL_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private static LookupResult syntheticHit(String name, String yaml) {
        return new LookupResult(
                pathFor(name),
                yaml,
                LookupResult.Source.PROJECT,
                /*document*/ null);
    }

    @SuppressWarnings("unchecked")
    private static ServerToolConfig parse(String name, LookupResult hit) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(hit.content());
        if (parsed == null) {
            throw new IllegalStateException("server-tool YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("server-tool YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String yamlName = stringOrNull(spec.get("name"));
        if (yamlName != null && !normalizedName(yamlName).equals(name)) {
            throw new IllegalStateException(
                    "'name' in YAML ('" + yamlName + "') does not match document path ('" + name + "')");
        }

        String type = stringOrThrow(spec.get("type"), "type");
        String description = stringOrThrow(spec.get("description"), "description");

        Map<String, Object> parameters = mapOrEmpty(spec.get("parameters"), "parameters");
        List<String> labels = stringList(spec.get("labels"), "labels");
        Set<String> disabledSubTools = stringSet(spec.get("disabledSubTools"), "disabledSubTools");

        boolean enabled = boolWithDefault(spec.get("enabled"), true, "enabled");
        boolean primary = boolWithDefault(spec.get("primary"), false, "primary");
        boolean defaultDeferred = boolWithDefault(
                spec.get("defaultDeferred"), false, "defaultDeferred");
        // promptHint is optional — empty string when unset. Engines
        // inject it into the system prompt only when this pack is
        // reachable for the turn.
        String promptHint = spec.get("promptHint") instanceof String s ? s : "";

        DocumentDocument doc = hit.document();
        return new ServerToolConfig(
                name,
                type,
                description,
                parameters,
                labels,
                enabled,
                primary,
                disabledSubTools,
                defaultDeferred,
                promptHint,
                mapSource(hit.source()),
                doc == null ? null : doc.getId(),
                doc == null ? null : doc.getCreatedBy(),
                hit.content());
    }

    private static ServerToolConfig.Source mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> ServerToolConfig.Source.PROJECT;
            case VANCE -> ServerToolConfig.Source.VANCE;
            case RESOURCE -> ServerToolConfig.Source.RESOURCE;
        };
    }

    private static String stringOrThrow(@Nullable Object raw, String fieldName) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "missing required field '" + fieldName + "' (must be a non-empty string)");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(@Nullable Object raw, String fieldName) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException("'" + fieldName + "' must be a map");
        }
        Map<String, Object> out = new LinkedHashMap<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static List<String> stringList(@Nullable Object raw, String fieldName) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldName + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return out;
    }

    private static Set<String> stringSet(@Nullable Object raw, String fieldName) {
        if (raw == null) return new LinkedHashSet<>();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldName + "' must be a list");
        }
        Set<String> out = new LinkedHashSet<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldName + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return out;
    }

    private static boolean boolWithDefault(
            @Nullable Object raw, boolean defaultValue, String fieldName) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean b) return b;
        throw new IllegalStateException(
                "'" + fieldName + "' must be a boolean (got " + raw.getClass().getSimpleName() + ")");
    }

    /** Surfacing-friendly wrapper for parse failures. */
    public static class ServerToolParseException extends RuntimeException {
        public ServerToolParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
