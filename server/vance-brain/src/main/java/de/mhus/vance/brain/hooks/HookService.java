package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Lifecycle owner of the hook subsystem. Mirrors
 * {@code SchedulerService} on the responsibility axis but does not
 * need the {@code TaskScheduler} thread pool — hooks fire on
 * Spring-event ingress, never on a clock.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Project-bootstrap: load every {@code _vance/hooks/<event>/}
 *       document and populate the {@link HookRegistry}.</li>
 *   <li>Refresh: re-read after a CRUD call edits a document.</li>
 *   <li>CRUD: create / update / delete by event + name, writing the
 *       underlying {@code DocumentDocument} via
 *       {@link DocumentService}.</li>
 *   <li>Validation-error reporting via inbox-item dispatch — the
 *       bootstrap never fails on a single bad document.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HookService {

    private static final String YAML_MIME = "application/yaml";

    private final HookLoader loader;
    private final HookRegistry registry;
    private final HookYamlParser parser;
    private final DocumentService documentService;
    private final InboxItemService inboxService;

    // ───────────────────────── Lifecycle ─────────────────────────

    /** Register every hook visible to the project. */
    public int bootstrapProject(String tenantId, String projectId) {
        Map<HookEventName, List<HookDef>> grouped =
                loader.groupByEvent(tenantId, projectId);
        registry.replace(tenantId, projectId, grouped);
        int total = grouped.values().stream().mapToInt(List::size).sum();
        log.info("Hooks bootstrap project='{}/{}' registered {} hooks across {} events",
                tenantId, projectId, total, grouped.size());
        return total;
    }

    /** Drop all hooks for this project (project-suspend / pod-handoff). */
    public void unloadProject(String tenantId, String projectId) {
        registry.clear(tenantId, projectId);
        log.info("Hooks unload project='{}/{}'", tenantId, projectId);
    }

    /** Full project refresh — re-runs {@link #bootstrapProject}. */
    public int refresh(String tenantId, String projectId) {
        return bootstrapProject(tenantId, projectId);
    }

    /**
     * Refresh exactly one hook. Re-reads from the document layer and
     * replaces the entry in the registry; if the document is gone,
     * the entry is removed.
     *
     * @return {@code true} on live re-registration; {@code false}
     *         when the document is absent
     */
    public boolean refreshOne(
            String tenantId, String projectId,
            HookEventName event, String name) {
        Optional<HookDef> reloaded = loader.load(tenantId, projectId, event, name);
        // Re-read every hook of that event so the registry stays
        // consistent with the cascade — a single doc going away may
        // unmask a {@code _vance}-tier hook of the same name.
        List<HookDef> remaining = loader.listForEvent(tenantId, projectId, event);
        registry.replaceEvent(tenantId, projectId, event, remaining);
        log.info("Hooks refreshOne '{}/{}/{}/{}' → present={} totalForEvent={}",
                tenantId, projectId, event.wireName(), name,
                reloaded.isPresent(), remaining.size());
        return reloaded.isPresent();
    }

    // ───────────────────────── Read side ─────────────────────────

    public List<HookDef> listAll(String tenantId, String projectId) {
        return registry.allFor(tenantId, projectId);
    }

    public List<HookDef> listForEvent(
            String tenantId, String projectId, HookEventName event) {
        return registry.hooksFor(tenantId, projectId, event);
    }

    public Optional<HookDef> findOne(
            String tenantId, String projectId,
            HookEventName event, String name) {
        for (HookDef def : registry.hooksFor(tenantId, projectId, event)) {
            if (name.equals(def.name())) return Optional.of(def);
        }
        return Optional.empty();
    }

    // ───────────────────────── CRUD ─────────────────────────

    /**
     * Create or update a hook document. Validates the YAML before
     * touching the document store — invalid input throws
     * {@link HookParseException} and nothing is persisted.
     *
     * @return the freshly parsed {@link HookDef}
     */
    public HookDef save(
            String tenantId, String projectId,
            HookEventName event, String name,
            String yaml, @Nullable String createdBy) {
        // Parse first so a bad YAML never lands on disk.
        HookDef parsed = parser.parse(yaml, event,
                de.mhus.vance.api.hooks.HookSource.PROJECT, name, createdBy);

        String path = HookLoader.HOOK_PATH_ROOT + event.wireName() + "/"
                + name + HookLoader.HOOK_PATH_SUFFIX;
        Optional<de.mhus.vance.shared.document.DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    /*newTitle*/ existing.get().getTitle(),
                    /*newTags*/ parsed.tags(),
                    /*newInlineText*/ yaml,
                    /*newPath*/ null);
        } else {
            documentService.create(
                    tenantId, projectId, path,
                    /*title*/ "Hook: " + event.wireName() + "/" + name,
                    parsed.tags(),
                    YAML_MIME,
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    createdBy);
        }
        refreshOne(tenantId, projectId, event, name);
        return parsed;
    }

    /**
     * Move a hook into the project trash and drop it from the
     * registry. Returns {@code true} when a document was actually
     * trashed.
     */
    public boolean delete(
            String tenantId, String projectId,
            HookEventName event, String name) {
        String path = HookLoader.HOOK_PATH_ROOT + event.wireName() + "/"
                + name + HookLoader.HOOK_PATH_SUFFIX;
        Optional<de.mhus.vance.shared.document.DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            return false;
        }
        documentService.trash(existing.get().getId());
        refreshOne(tenantId, projectId, event, name);
        return true;
    }

    // ───────────────────────── Diagnostics ─────────────────────────

    /** Surface every parse failure as an inbox item to {@code createdBy}. */
    public void reportParseErrors(
            String tenantId, String projectId,
            List<HookParseException> errors,
            @Nullable String recipientUserId) {
        if (errors.isEmpty() || recipientUserId == null || recipientUserId.isBlank()) {
            return;
        }
        for (HookParseException ex : errors) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("category", "hook-parse-error");
            inboxService.create(InboxItemDocument.builder()
                    .tenantId(tenantId)
                    .originatorUserId("system:hooks")
                    .assignedToUserId(recipientUserId)
                    .type(InboxItemType.OUTPUT_TEXT)
                    .criticality(Criticality.NORMAL)
                    .title("Hook parse error")
                    .body(ex.getMessage())
                    .tags(new ArrayList<>(List.of("hooks", "error")))
                    .requiresAction(false)
                    .payload(payload)
                    .build());
        }
    }
}
