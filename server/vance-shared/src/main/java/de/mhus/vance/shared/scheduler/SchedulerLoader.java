package de.mhus.vance.shared.scheduler;

import de.mhus.vance.api.scheduler.LockMode;
import de.mhus.vance.api.scheduler.OverlapPolicy;
import de.mhus.vance.api.scheduler.SchedulerSource;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware scheduler loader. Reads one YAML per scheduler through
 * {@link DocumentService#listByPrefixCascade} / {@link DocumentService#lookupCascade}:
 * {@code project → _vance/scheduler/<name>.yaml}. Unlike recipes there
 * is intentionally no resource tier — schedulers are always
 * project- or tenant-specific (see {@code specification/scheduler.md} §3).
 *
 * <p>Parse errors on individual entries are surfaced to the caller via
 * {@link SchedulerParseException}; bulk listings ({@link #listAll}) log
 * and skip — a single broken doc must not poison the project bootstrap.
 *
 * <p>No in-memory cache. Bootstrap reads once, the scheduler service then
 * holds the result in its own runtime registry; {@code refresh} re-reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerLoader {

    /** Path prefix used for scheduler documents in any cascade tier. */
    public static final String SCHEDULER_PATH_PREFIX = "_vance/scheduler/";

    /** File suffix kept on the document path; the scheduler name itself does not carry it. */
    public static final String SCHEDULER_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Resolve a single scheduler by name in the project/_vance cascade.
     * Returns empty if no tier carries it.
     */
    public Optional<ResolvedScheduler> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String path = pathFor(name);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId(projectId), path);
        if (hit.isEmpty()) return Optional.empty();
        LookupResult result = hit.get();
        if (result.source() == LookupResult.Source.RESOURCE) {
            // Defensive: classpath shipping isn't part of the v1 scheduler design.
            log.warn("SchedulerLoader: ignoring resource-layer scheduler at '{}'", result.path());
            return Optional.empty();
        }
        try {
            return Optional.of(parse(normalizedName(name), result));
        } catch (RuntimeException e) {
            throw new SchedulerParseException(
                    "Failed to parse scheduler '" + name + "' from "
                            + result.source() + " at path '" + result.path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Every scheduler visible to the project: project entries override
     * {@code _vance/scheduler/} entries by name. Malformed entries are
     * logged and skipped — the rest of the bootstrap continues.
     */
    public List<ResolvedScheduler> listAll(String tenantId, @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, effectiveProjectId(projectId), SCHEDULER_PATH_PREFIX);
        List<ResolvedScheduler> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            LookupResult hit = e.getValue();
            if (hit.source() == LookupResult.Source.RESOURCE) continue;
            try {
                out.add(parse(name, hit));
            } catch (RuntimeException ex) {
                log.warn("SchedulerLoader: skipping malformed scheduler path='{}' source={}: {}",
                        path, hit.source(), ex.getMessage());
            }
        }
        return out;
    }

    private static String pathFor(String name) {
        return SCHEDULER_PATH_PREFIX + normalizedName(name) + SCHEDULER_PATH_SUFFIX;
    }

    private static String normalizedName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(SCHEDULER_PATH_PREFIX)) return null;
        if (!path.endsWith(SCHEDULER_PATH_SUFFIX)) return null;
        String stem = path.substring(
                SCHEDULER_PATH_PREFIX.length(),
                path.length() - SCHEDULER_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    /**
     * Validate a YAML body without persisting. Used by the agent tools
     * and the REST controller before writing a scheduler document, so
     * malformed input never reaches the document layer.
     *
     * @throws SchedulerParseException with a field-level error message
     */
    public ResolvedScheduler validateYaml(String name, String yaml) {
        String norm = normalizedName(name);
        try {
            return parse(norm, syntheticHit(norm, yaml));
        } catch (RuntimeException ex) {
            throw new SchedulerParseException(
                    "scheduler YAML invalid: " + ex.getMessage(), ex);
        }
    }

    private static LookupResult syntheticHit(String name, String yaml) {
        return new LookupResult(
                SCHEDULER_PATH_PREFIX + name + SCHEDULER_PATH_SUFFIX,
                yaml,
                LookupResult.Source.PROJECT,
                /*document*/ null);
    }

    @SuppressWarnings("unchecked")
    private static ResolvedScheduler parse(String name, LookupResult hit) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(hit.content());
        if (parsed == null) {
            throw new IllegalStateException("scheduler YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("scheduler YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String description = stringOrThrow(spec.get("description"), "description");
        String recipe = stringOrNull(spec.get("recipe"));
        String workflow = stringOrNull(spec.get("workflow"));
        ResolvedScheduler.ScriptSpec script = parseScript(spec.get("script"));
        // Exactly one of recipe / workflow / script must be set.
        int targetCount = (recipe != null ? 1 : 0)
                + (workflow != null ? 1 : 0)
                + (script != null ? 1 : 0);
        if (targetCount > 1) {
            throw new IllegalStateException(
                    "'recipe', 'workflow', 'script' are mutually exclusive — set exactly one");
        }
        if (targetCount == 0) {
            throw new IllegalStateException(
                    "missing trigger target — set 'recipe' (spawns a ThinkProcess), "
                            + "'workflow' (spawns a Hactar workflow run), "
                            + "or 'script' (runs a JS script)");
        }
        String timezone = stringOrNull(spec.get("timezone"));

        // Trigger: exactly one of `cron` or `at` must be set. Mutually
        // exclusive — both is ambiguous, neither is meaningless.
        String cron = stringOrNull(spec.get("cron"));
        Instant at = parseAt(spec.get("at"), timezone);
        if (cron != null && at != null) {
            throw new IllegalStateException(
                    "'cron' and 'at' are mutually exclusive — set exactly one");
        }
        if (cron == null && at == null) {
            throw new IllegalStateException(
                    "missing trigger — set either 'cron' (recurring) or 'at' (one-shot)");
        }

        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;

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

        String initialMessage = stringOrNull(spec.get("initialMessage"));
        String runAs = stringOrNull(spec.get("runAs"));
        OverlapPolicy overlap = parseOverlap(spec.get("overlap"));
        LockMode lockMode = parseLockMode(spec.get("lockMode"));
        List<String> tags = stringList(spec.get("tags"), "tags");

        DocumentDocument doc = hit.document();
        return new ResolvedScheduler(
                name,
                hit.content(),
                mapSource(hit.source()),
                doc == null ? null : doc.getId(),
                doc == null ? null : doc.getCreatedBy(),
                description, cron, at, timezone, enabled,
                recipe, workflow, script, Map.copyOf(params),
                initialMessage, runAs, overlap, lockMode, tags);
    }

    /** Parses the {@code script:} block when present; returns {@code null} when absent. */
    private static ResolvedScheduler.@Nullable ScriptSpec parseScript(@Nullable Object raw) {
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
                    sourceRaw.trim().toUpperCase(Locale.ROOT));
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
        return new ResolvedScheduler.ScriptSpec(source, dirName, path, timeoutSeconds);
    }

    private static LockMode parseLockMode(@Nullable Object raw) {
        if (raw == null) return LockMode.FULL;
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("'lockMode' must be a string");
        }
        String norm = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "FULL" -> LockMode.FULL;
            case "PROTECTED" -> LockMode.PROTECTED;
            case "HIDDEN" -> LockMode.HIDDEN;
            default -> throw new IllegalStateException(
                    "unknown lockMode '" + s + "' — expected full | protected | hidden");
        };
    }

    /**
     * Parses the optional {@code at:} field. Accepted forms — see
     * {@code specification/scheduler.md} §10a:
     *
     * <ul>
     *   <li>SnakeYAML-native {@link Date} (unquoted ISO timestamps).</li>
     *   <li>String with explicit zone offset → {@link ZonedDateTime}.</li>
     *   <li>String with trailing {@code Z} → {@link Instant}.</li>
     *   <li>Local string (no zone) → resolved against the scheduler's
     *       {@code timezone:} field, falling back to UTC.</li>
     * </ul>
     */
    private static @Nullable Instant parseAt(@Nullable Object raw, @Nullable String timezone) {
        if (raw == null) return null;
        if (raw instanceof Date d) {
            return d.toInstant();
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "'at' must be an ISO-8601 datetime string");
        }
        String trimmed = s.trim();
        try {
            return ZonedDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // not zoned — try the next form
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // not a Z-instant — fall through to LocalDateTime + zone
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(trimmed);
            ZoneId zone = (timezone == null || timezone.isBlank())
                    ? ZoneOffset.UTC
                    : ZoneId.of(timezone);
            return ldt.atZone(zone).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                    "'at' is not a valid ISO-8601 datetime: '" + trimmed + "'");
        } catch (java.time.DateTimeException ex) {
            throw new IllegalStateException(
                    "'at' carries an unknown timezone '" + timezone + "': " + ex.getMessage());
        }
    }

    private static OverlapPolicy parseOverlap(@Nullable Object raw) {
        if (raw == null) return OverlapPolicy.SKIP;
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("'overlap' must be a string");
        }
        String norm = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        // Tolerate the camelCase form the YAML schema documents (skip / queue / cancelPrevious).
        return switch (norm) {
            case "SKIP" -> OverlapPolicy.SKIP;
            case "QUEUE" -> OverlapPolicy.QUEUE;
            case "CANCEL_PREVIOUS", "CANCELPREVIOUS" -> OverlapPolicy.CANCEL_PREVIOUS;
            default -> throw new IllegalStateException(
                    "unknown overlap policy '" + s + "' — expected skip | queue | cancelPrevious");
        };
    }

    private static SchedulerSource mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> SchedulerSource.PROJECT;
            case VANCE -> SchedulerSource.TENANT;
            case RESOURCE -> throw new IllegalStateException(
                    "resource layer is not allowed for schedulers");
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

    /** Surfacing-friendly wrapper for parse failures. */
    public static class SchedulerParseException extends RuntimeException {
        public SchedulerParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
