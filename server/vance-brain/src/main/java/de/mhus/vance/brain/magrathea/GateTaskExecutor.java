package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /** Outcome the timeout-timer publishes when the user doesn't answer in time. */
    public static final String OUTCOME_TIMEOUT = "timeout";

    private static final String SPEC_INBOX = "inbox";
    private static final String SYSTEM_USER = "@system";

    private final InboxItemService inboxItemService;
    private final MagratheaTaskService taskService;
    private final MagratheaTimerService timerService;

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
        InboxItemType kind = parseKind(inboxSpec.get("kind"));
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

        InboxItemDocument toCreate = InboxItemDocument.builder()
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

        InboxItemDocument created;
        try {
            created = inboxItemService.create(toCreate);
        } catch (RuntimeException ex) {
            log.warn("Magrathea gate_task '{}' inbox create failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Inbox create failed: " + ex.getMessage()));
        }

        taskService.linkInboxItem(context.taskId(), created.getId());
        log.info("Magrathea gate_task '{}' inbox item created id='{}' assignedTo='{}'",
                state.name(), created.getId(), assignedTo);

        scheduleTimeoutTimer(context, state);

        return Optional.empty();
    }

    private void scheduleTimeoutTimer(MagratheaTaskContext context, MagratheaStateSpec state) {
        Integer timeoutSeconds = state.timeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) return;

        MagratheaTimerDocument timer = MagratheaTimerDocument.builder()
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .workflowRunId(context.workflowRunId())
                .linkedTaskId(context.taskId())
                .firedOutcome(OUTCOME_TIMEOUT)
                .fireAt(Instant.now().plusSeconds(timeoutSeconds))
                .build();
        try {
            timerService.insert(timer);
            log.debug("Magrathea gate_task '{}' timeout timer scheduled fireAt={}",
                    state.name(), timer.getFireAt());
        } catch (RuntimeException ex) {
            // A timeout timer is best-effort. The gate still functions
            // without it — the user can answer in their own time.
            log.warn("Magrathea gate_task '{}' timeout timer insert failed: {} — gate continues without timeout",
                    state.name(), ex.getMessage());
        }
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

    private static @org.jspecify.annotations.Nullable InboxItemType parseKind(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        String norm = s.trim().toUpperCase(Locale.ROOT);
        try {
            InboxItemType type = InboxItemType.valueOf(norm);
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
