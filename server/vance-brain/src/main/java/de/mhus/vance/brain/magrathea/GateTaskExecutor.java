package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gate-task executor (plan §4.4). Creates an Inbox item for the user
 * to answer, links it to the calling {@code magrathea_tasks} row, and
 * returns {@link Optional#empty()} — completion arrives asynchronously
 * via {@link MagratheaInboxCompletionListener} when the user replies.
 *
 * <h3>YAML</h3>
 * <pre>
 * review:
 *   type: gate_task
 *   inbox:
 *     kind: APPROVAL                # APPROVAL | DECISION | FEEDBACK
 *     title: "PR ${params.pr_url} reviewen?"
 *     body: "${state.review_summary}"
 *     assignedTo: "@maintainers"
 *     criticality: NORMAL
 *     tags: [pr-review]
 *     options: [approve, reject, defer]   # required for DECISION
 *   on:
 *     approved: merge
 *     rejected: plan
 * </pre>
 *
 * <p>Timeouts ({@code timeoutSeconds}/{@code onTimeout}/{@code default})
 * are recognized at parse time but driven by the timer-scanner — that
 * landing is W8.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class GateTaskExecutor implements MagratheaTypeExecutor {

    /** Payload kind that {@link MagratheaInboxCompletionListener} keys on. */
    public static final String PAYLOAD_KIND = "workflow.gate";

    // The timeout outcome moved to MagratheaTimeoutScheduler#OUTCOME_TIMEOUT
    // when arming became shared with agent_task and workflow_task. The
    // forwarding constant that stood here had no callers left — one name for
    // one thing beats a second one that only says where the first went.

    private static final String SPEC_INBOX = "inbox";
    private static final String SYSTEM_USER = "@system";

    private final MaximegalonService inboxItemService;
    private final MagratheaTaskService taskService;
    private final MagratheaTimeoutScheduler timeoutScheduler;
    private final MagratheaOwnerNotifier ownerNotifier;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.GATE_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        Map<String, Object> inboxSpec = readInboxSpec(state);
        if (inboxSpec == null) {
            return Optional.of(TaskOutcome.failure(
                    "gate_task '" + state.name() + "' is missing required 'inbox:' block"));
        }

        String title = stringOrNull(inboxSpec.get("title"));
        if (title == null) {
            return Optional.of(TaskOutcome.failure(
                    "gate_task '" + state.name() + "' inbox is missing required 'title'"));
        }
        MaximegalonType kind = parseKind(inboxSpec.get("kind"));
        if (kind == null) {
            return Optional.of(TaskOutcome.failure(
                    "gate_task '" + state.name()
                            + "' inbox.kind must be one of APPROVAL/DECISION/FEEDBACK"));
        }
        String body = stringOrNull(inboxSpec.get("body"));
        String assignedTo = firstNonBlank(
                stringOrNull(inboxSpec.get("assignedTo")),
                context.startedBy(),
                SYSTEM_USER);
        Criticality criticality = parseCriticality(inboxSpec.get("criticality"));
        List<String> tags = readStringList(inboxSpec.get("tags"));
        List<String> options = readStringList(inboxSpec.get("options"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", PAYLOAD_KIND);
        payload.put("workflowRunId", context.workflowRunId());
        payload.put("workflowName", context.workflow().name());
        payload.put("workflowState", state.name());
        if (!options.isEmpty()) payload.put("options", options);

        // The id is minted here, not by the insert, so the task can carry the
        // link *before* the item exists to be answered. Linking afterwards
        // left a window between "row is findable" and "task knows about it":
        // an answer arriving inside it finds no task
        // (MagratheaInboxCompletionListener logs "has no linked task"), the
        // completion is dropped and the run stands at a gate that can never
        // be answered again — the item is already ANSWERED. Milliseconds
        // wide, but a client that reads the item from the change feed rather
        // than from a listing is inside it, and so is the E2E test that polls
        // Mongo directly. The auto-default path closes it altogether:
        // MaximegalonService.create publishes the answered-event *inside*
        // create(), so for a LOW item with a `default:` the old order could
        // never have worked.
        String itemId = new ObjectId().toHexString();

        MaximegalonDocument toCreate = MaximegalonDocument.builder()
                .id(itemId)
                .tenantId(context.tenantId())
                .originatorUserId(firstNonBlank(context.startedBy(), SYSTEM_USER))
                .assignedToUserId(assignedTo)
                .type(kind)
                .criticality(criticality)
                .tags(tags)
                .title(title)
                .body(body)
                .payload(payload)
                .requiresAction(true)
                .build();

        // Link first. A link to an item that does not exist yet resolves
        // nothing and is therefore harmless; a missing link to an item that
        // already does loses the answer. If the create below fails the task
        // completes as a failure right away, which unsets `runStatus`, and
        // the id it points at was never written — nothing can ever match it.
        taskService.linkInboxItem(context.taskId(), itemId);

        try {
            inboxItemService.create(toCreate);
        } catch (RuntimeException ex) {
            log.warn("Magrathea gate_task '{}' inbox create failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Inbox create failed: " + ex.getMessage()));
        }

        log.info("Magrathea gate_task '{}' inbox item created id='{}' assignedTo='{}'",
                state.name(), itemId, assignedTo);

        timeoutScheduler.arm(context, state);

        // If this run belongs to a process, tell it that it is now waiting.
        // The inbox item above is the wait itself and works without anyone;
        // this only lets an owner raise the question where the conversation
        // is, instead of the person having to go looking for it.
        ownerNotifier.runBlocked(
                context.ownerProcessId(), context.workflowRunId(), state.name(), title);

        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static @org.jspecify.annotations.Nullable Map<String, Object> readInboxSpec(
            MagratheaStateSpec state) {
        Object raw = state.specField(SPEC_INBOX);
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(
                "gate_task '" + state.name() + "' inbox must be a map");
    }

    private static @org.jspecify.annotations.Nullable MaximegalonType parseKind(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        String norm = s.trim().toUpperCase(Locale.ROOT);
        try {
            MaximegalonType type = MaximegalonType.valueOf(norm);
            // Only the interactive kinds make sense as gates.
            return switch (type) {
                case APPROVAL, DECISION, FEEDBACK -> type;
                default -> null;
            };
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Criticality parseCriticality(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return Criticality.NORMAL;
        try {
            return Criticality.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Criticality.NORMAL;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "";
    }
}
