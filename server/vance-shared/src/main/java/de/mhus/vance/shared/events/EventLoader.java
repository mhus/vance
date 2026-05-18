package de.mhus.vance.shared.events;

import de.mhus.vance.api.events.EventSource;
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
 * Cascade-aware event loader. Mirrors {@code SchedulerLoader}: one YAML
 * per event under {@code _vance/events/<name>.yaml}, resolved through
 * {@link DocumentService#lookupCascade}: {@code project → _vance}.
 *
 * <p>The resource tier is intentionally not supported — events ship
 * tenant-specific secrets, so a classpath layer would be a security
 * footgun.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventLoader {

    /** Path prefix used for event documents in any cascade tier. */
    public static final String EVENT_PATH_PREFIX = "_vance/events/";

    /** File suffix kept on the document path; the event name itself does not carry it. */
    public static final String EVENT_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Resolve a single event by name in the project/_vance cascade.
     * Returns empty if no tier carries it.
     */
    public Optional<ResolvedEvent> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String path = pathFor(name);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId(projectId), path);
        if (hit.isEmpty()) return Optional.empty();
        LookupResult result = hit.get();
        if (result.source() == LookupResult.Source.RESOURCE) {
            log.warn("EventLoader: ignoring resource-layer event at '{}'", result.path());
            return Optional.empty();
        }
        try {
            return Optional.of(parse(normalizedName(name), result));
        } catch (RuntimeException e) {
            throw new EventParseException(
                    "Failed to parse event '" + name + "' from "
                            + result.source() + " at path '" + result.path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Every event visible to the project: project entries override
     * {@code _vance/events/} entries by name. Malformed entries are
     * logged and skipped — the rest of the listing continues.
     */
    public List<ResolvedEvent> listAll(String tenantId, @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, effectiveProjectId(projectId), EVENT_PATH_PREFIX);
        List<ResolvedEvent> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            LookupResult hit = e.getValue();
            if (hit.source() == LookupResult.Source.RESOURCE) continue;
            try {
                out.add(parse(name, hit));
            } catch (RuntimeException ex) {
                log.warn("EventLoader: skipping malformed event path='{}' source={}: {}",
                        path, hit.source(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Validate a YAML body without persisting. Used by the agent tools
     * and the REST controller before writing an event document.
     *
     * @throws EventParseException with a field-level error message
     */
    public ResolvedEvent validateYaml(String name, String yaml) {
        String norm = normalizedName(name);
        try {
            return parse(norm, syntheticHit(norm, yaml));
        } catch (RuntimeException ex) {
            throw new EventParseException("event YAML invalid: " + ex.getMessage(), ex);
        }
    }

    private static String pathFor(String name) {
        return EVENT_PATH_PREFIX + normalizedName(name) + EVENT_PATH_SUFFIX;
    }

    private static String normalizedName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(EVENT_PATH_PREFIX)) return null;
        if (!path.endsWith(EVENT_PATH_SUFFIX)) return null;
        String stem = path.substring(
                EVENT_PATH_PREFIX.length(),
                path.length() - EVENT_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private static LookupResult syntheticHit(String name, String yaml) {
        return new LookupResult(
                EVENT_PATH_PREFIX + name + EVENT_PATH_SUFFIX,
                yaml,
                LookupResult.Source.PROJECT,
                /*document*/ null);
    }

    @SuppressWarnings("unchecked")
    private static ResolvedEvent parse(String name, LookupResult hit) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(hit.content());
        if (parsed == null) {
            throw new IllegalStateException("event YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("event YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String recipe = stringOrNull(spec.get("recipe"));
        String workflow = stringOrNull(spec.get("workflow"));
        de.mhus.vance.shared.scheduler.ResolvedScheduler.ScriptSpec script =
                parseScriptSpec(spec.get("script"));
        int targetCount = (recipe != null ? 1 : 0)
                + (workflow != null ? 1 : 0)
                + (script != null ? 1 : 0);
        if (targetCount > 1) {
            throw new IllegalStateException(
                    "'recipe', 'workflow', 'script' are mutually exclusive — set exactly one");
        }
        if (targetCount == 0) {
            throw new IllegalStateException(
                    "missing trigger target — set 'recipe', 'workflow' or 'script'");
        }
        String initialMessage = stringOrNull(spec.get("initialMessage"));
        String description = stringOrNull(spec.get("description"));
        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;

        Set<String> methods = parseMethods(spec.get("methods"));

        String tokenLiteral = null;
        String tokenSettingKey = null;
        Object rawAuth = spec.get("auth");
        if (rawAuth != null) {
            if (!(rawAuth instanceof Map<?, ?> am)) {
                throw new IllegalStateException("'auth' must be a map");
            }
            Map<String, Object> auth = (Map<String, Object>) am;
            tokenLiteral = stringOrNull(auth.get("token"));
            tokenSettingKey = stringOrNull(auth.get("tokenSetting"));
            if (tokenLiteral != null && tokenSettingKey != null) {
                throw new IllegalStateException(
                        "'auth.token' and 'auth.tokenSetting' are mutually exclusive — set at most one");
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Object rawParams = spec.get("params");
        if (rawParams != null) {
            if (!(rawParams instanceof Map<?, ?> pm)) {
                throw new IllegalStateException("'params' must be a map");
            }
            for (Map.Entry<?, ?> p : pm.entrySet()) {
                params.put(String.valueOf(p.getKey()), p.getValue());
            }
        }

        String runAs = stringOrNull(spec.get("runAs"));
        List<String> tags = stringList(spec.get("tags"), "tags");

        DocumentDocument doc = hit.document();
        return new ResolvedEvent(
                name,
                hit.content(),
                mapSource(hit.source()),
                doc == null ? null : doc.getId(),
                doc == null ? null : doc.getCreatedBy(),
                description,
                recipe,
                workflow,
                script,
                initialMessage,
                enabled,
                Set.copyOf(methods),
                tokenLiteral,
                tokenSettingKey,
                Map.copyOf(params),
                runAs,
                tags);
    }

    /** Parses the {@code script:} block when present; returns {@code null} when absent. */
    private static de.mhus.vance.shared.scheduler.ResolvedScheduler.@Nullable ScriptSpec parseScriptSpec(@Nullable Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> sm)) {
            throw new IllegalStateException(
                    "'script' must be a map with 'source', 'path' [, 'dirName', 'timeoutSeconds']");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : sm.entrySet()) {
            map.put(String.valueOf(e.getKey()), e.getValue());
        }
        String sourceRaw = stringOrNull(map.get("source"));
        if (sourceRaw == null) {
            throw new IllegalStateException("'script.source' is required (document | workspace)");
        }
        de.mhus.vance.api.action.ScriptSource source;
        try {
            source = de.mhus.vance.api.action.ScriptSource.valueOf(
                    sourceRaw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "unknown 'script.source' '" + sourceRaw + "' (expected: document | workspace)");
        }
        String path = stringOrNull(map.get("path"));
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("'script.path' must be non-blank");
        }
        String dirName = stringOrNull(map.get("dirName"));
        if (source == de.mhus.vance.api.action.ScriptSource.WORKSPACE
                && (dirName == null || dirName.isBlank())) {
            throw new IllegalStateException(
                    "'script.dirName' is required when source=workspace");
        }
        if (source == de.mhus.vance.api.action.ScriptSource.DOCUMENT
                && dirName != null && !dirName.isBlank()) {
            throw new IllegalStateException(
                    "'script.dirName' must be omitted when source=document");
        }
        Integer timeoutSeconds = null;
        Object rawTimeout = map.get("timeoutSeconds");
        if (rawTimeout instanceof Number n) {
            timeoutSeconds = n.intValue();
        } else if (rawTimeout instanceof String s) {
            try {
                timeoutSeconds = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "'script.timeoutSeconds' must be an integer, got '" + s + "'");
            }
        }
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            throw new IllegalStateException(
                    "'script.timeoutSeconds' must be > 0, got " + timeoutSeconds);
        }
        return new de.mhus.vance.shared.scheduler.ResolvedScheduler.ScriptSpec(
                source, dirName, path, timeoutSeconds);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> parseMethods(@Nullable Object raw) {
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'methods' must be a list");
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'methods' contains a non-string or blank entry");
            }
            String norm = s.trim().toUpperCase(Locale.ROOT);
            if (!"GET".equals(norm) && !"POST".equals(norm)) {
                throw new IllegalStateException(
                        "'methods' contains unsupported entry '" + s + "' — expected GET or POST");
            }
            out.add(norm);
        }
        return out;
    }

    private static EventSource mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> EventSource.PROJECT;
            case VANCE -> EventSource.TENANT;
            case RESOURCE -> throw new IllegalStateException(
                    "resource layer is not allowed for events");
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
    private static List<String> stringList(@Nullable Object raw, String fieldName) {
        if (raw == null) return List.of();
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
        return List.copyOf(out);
    }
}
