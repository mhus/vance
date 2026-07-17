package de.mhus.vance.brain.tools.ursascheduler;

import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.ursascheduler.UrsaSchedulerService;
import de.mhus.vance.brain.ursascheduler.UrsaSchedulerSourceKeys;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.eventlog.EventLogDocument;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared helpers for the {@code scheduler_*} agent tools. Centralises
 * path conventions, name validation and the read-shape used by both
 * the tool layer and the REST controller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class UrsaSchedulerToolSupport {

    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final DocumentService documentService;
    private final UrsaSchedulerLoader loader;
    private final EventLogService eventLogService;
    private final UrsaSchedulerService schedulerService;
    private final RecipeResolver recipeResolver;
    private final de.mhus.vance.shared.settings.TimezoneResolver timezoneResolver;

    /**
     * Pin the scheduler to the author's display timezone when the YAML
     * body omits an explicit {@code timezone:}. Resolves the user →
     * tenant timezone cascade and defers to
     * {@link UrsaSchedulerLoader#applyDefaultTimezone}. When no timezone
     * is configured anywhere the body is returned verbatim (the loader
     * then defaults cron/at interpretation to UTC as before).
     */
    String applyDefaultTimezone(String tenantId, @Nullable String userId, String yaml) {
        String tz = timezoneResolver.findTimezone(tenantId, userId);
        return loader.applyDefaultTimezone(yaml, tz);
    }

    static String normalizeName(String name) {
        if (name == null) {
            throw new ToolException("'name' is required");
        }
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            throw new ToolException("'name' must be a non-empty string");
        }
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ToolException(
                    "'name' must match " + NAME_PATTERN.pattern() + " — got '" + name + "'");
        }
        return trimmed;
    }

    static String pathFor(String name) {
        return UrsaSchedulerLoader.SCHEDULER_PATH_PREFIX + name
                + UrsaSchedulerLoader.SCHEDULER_PATH_SUFFIX;
    }

    /**
     * Refuse the call when an existing scheduler with this name (in the
     * cascade) is locked against LLM mutation. Called by every mutating
     * tool ({@code create}/{@code update}/{@code delete}) before any
     * write. See {@code specification/scheduler.md} §10b.
     *
     * <p>The check intentionally operates on the cascade-resolved
     * scheduler, not on a project-local row, so the LLM cannot bypass a
     * protected tenant-level entry by creating a project-level override
     * with the same name.
     *
     * <p>{@code HIDDEN} entries reveal their existence on this path —
     * the alternative would be silently letting writes through, which
     * defeats the lock. The leak is acceptable: the LLM must guess the
     * exact name first, and the {@code allowedToolsRemove} recipe
     * facility is the right hammer when even-name-knowledge is
     * unacceptable.
     */
    void guardMutation(String tenantId, String projectId, String name) {
        loader.load(tenantId, projectId, name)
                .filter(de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler::isLlmLocked)
                .ifPresent(r -> {
                    throw new ToolException(
                            "scheduler '" + name + "' is locked (lockMode="
                                    + r.lockMode().name().toLowerCase(Locale.ROOT)
                                    + ") and cannot be modified by LLM tools");
                });
    }

    /**
     * Verifies the YAML parses to a valid scheduler before writing.
     * Throws {@link ToolException} with a field-level message on
     * malformed input.
     */
    ResolvedUrsaScheduler parseOrThrow(String name, String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ToolException("'yaml' must be a non-empty string");
        }
        try {
            return loader.validateYaml(name, yaml);
        } catch (UrsaSchedulerLoader.SchedulerParseException ex) {
            throw new ToolException(ex.getMessage());
        }
    }

    /**
     * Cross-reference checks the tool layer surfaces back to the LLM as
     * non-fatal warnings — the scheduler is still saved and registered,
     * but the caller knows about dangling references before the first
     * cron tick. Today: recipe-name existence (the most common
     * hallucination). Workflow / script targets are not checked here —
     * their resolution paths surface failures differently.
     *
     * <p>An empty list means "no warnings"; an LLM seeing this in the
     * tool result should self-correct (typically by creating the
     * referenced recipe, or by setting {@code enabled: false} until the
     * referenced object exists).
     */
    List<String> crossReferenceWarnings(
            String tenantId, String projectId, ResolvedUrsaScheduler r) {
        List<String> warnings = new ArrayList<>();
        if (r.recipe() != null && !r.recipe().isBlank()) {
            boolean exists = recipeResolver.resolve(tenantId, projectId, r.recipe()).isPresent();
            if (!exists) {
                warnings.add("recipe '" + r.recipe()
                        + "' is not defined in this project's cascade — "
                        + "scheduler will fail on fire until the recipe is created "
                        + "(see _vance/recipes/<name>.yaml) or the scheduler's "
                        + "'recipe:' field is corrected");
            }
        }
        return warnings;
    }

    /**
     * Persist the YAML body. If the doc exists at the same path it is
     * updated inline; otherwise a new doc is created. Returns the
     * persisted document.
     */
    DocumentDocument upsert(
            String tenantId, String projectId, String name, String yaml,
            @Nullable String createdBy) {
        String path = pathFor(name);
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    /*newTitle*/ null,
                    /*newTags*/ null,
                    /*newInlineText*/ yaml,
                    /*newPath*/ null);
        }
        return documentService.createText(
                tenantId, projectId, path,
                /*title*/ "Scheduler: " + name,
                /*tags*/ null,
                yaml,
                createdBy);
    }

    void deleteByPath(String tenantId, String projectId, String name) {
        String path = pathFor(name);
        documentService.findByPath(tenantId, projectId, path)
                .ifPresent(doc -> documentService.delete(doc.getId()));
    }

    /** Compact list-shape for the read tools and REST list endpoint. */
    Map<String, Object> shape(
            String tenantId, String projectId, ResolvedUrsaScheduler r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name());
        out.put("description", r.description());
        if (r.cron() != null) out.put("cron", r.cron());
        if (r.at() != null) out.put("at", r.at());
        out.put("recipe", r.recipe());
        out.put("enabled", r.enabled());
        out.put("source", r.source().name());
        if (r.timezone() != null) out.put("timezone", r.timezone());
        if (r.runAs() != null) out.put("runAs", r.runAs());
        if (r.effectiveRunAs() != null) out.put("effectiveRunAs", r.effectiveRunAs());
        if (r.overlap() != null) out.put("overlap", r.overlap().name());
        if (r.lockMode() != null && r.lockMode() != de.mhus.vance.api.ursascheduler.LockMode.FULL) {
            out.put("lockMode", r.lockMode().name().toLowerCase(Locale.ROOT));
            out.put("locked", Boolean.TRUE);
        }
        if (!r.tags().isEmpty()) out.put("tags", r.tags());

        EventType[] activityTypes = {
                EventType.STARTED, EventType.COMPLETED, EventType.FAILED, EventType.SKIPPED};
        Optional<EventLogDocument> last = eventLogService.findLatest(
                tenantId,
                UrsaSchedulerSourceKeys.sourceFor(r.name()),
                List.of(activityTypes));
        last.ifPresent(e -> {
            Map<String, Object> lastRun = new LinkedHashMap<>();
            lastRun.put("at", e.getTimestamp());
            lastRun.put("type", e.getType().name());
            if (e.getCorrelationId() != null) lastRun.put("correlationId", e.getCorrelationId());
            out.put("lastRun", lastRun);
        });
        Instant next = schedulerService.nextFireFor(tenantId, projectId, r.name());
        if (next != null) out.put("nextRunAt", next);
        return out;
    }

    Map<String, Object> shapeFull(
            String tenantId, String projectId, ResolvedUrsaScheduler r) {
        Map<String, Object> out = shape(tenantId, projectId, r);
        out.put("yaml", r.yaml());
        if (r.params() != null && !r.params().isEmpty()) out.put("params", r.params());
        if (r.initialMessage() != null) out.put("initialMessage", r.initialMessage());
        return out;
    }
}
