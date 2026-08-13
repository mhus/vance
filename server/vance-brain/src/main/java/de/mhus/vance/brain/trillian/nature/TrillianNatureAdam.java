package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.trillian.TrillianAttributeStore;
import de.mhus.vance.brain.trillian.TrillianJournalStore;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature-A, {@code adam} — the first persistent Trillian.
 *
 * <p>Adds to {@link TrillianNatureBase}, not to Nature-0: the attribute
 * map and how it renders into both prompts are shared mechanics, not
 * generation-zero behaviour to inherit. What distinguishes adam is
 * that its attributes <b>outlive the process rows</b>.
 *
 * <p>Under Nature-0 an attribute lives in {@code engineParams} and dies
 * with the worker process. Everything that keeps it alive across an
 * archive is carrying code written for that one transition, and the
 * human cannot see the value at all except by asking the agent. adam
 * mirrors the map into {@code _vance/trillian/<account>.yaml} in the
 * pair's project on every change, and seeds a fresh worker loop from it.
 * The Trillian is then persistent in the plain sense: what it was told
 * to be is a document, editable by hand, gone only when deleted.
 *
 * <p>Nature-A's remaining promises from
 * {@code specification/public/trillian-engine.md} §13 — reflexion after
 * task completion, personality as a typed schema, mode switching, token
 * budgets — are not here yet. They overlay onto the same hooks and,
 * notably, onto the same document: a Nature that reflects needs
 * somewhere durable to write its conclusion, and that is now in place.
 */
@Component
@Slf4j
public class TrillianNatureAdam extends TrillianNatureBase {

    public static final String ID = "adam";

    /** LightLlm config profile for the reflexion pass. */
    static final String REFLECT_RECIPE = "trillian-adam-reflect";

    private static final Map<String, Object> REFLECT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "keep", Map.of("type", "boolean"),
                    "entry", Map.of("type", "string")),
            "required", java.util.List.of("keep", "entry"));

    private final TrillianAttributeStore attributeStore;
    private final TrillianJournalStore journalStore;
    private final LightLlmService lightLlm;

    public TrillianNatureAdam(
            ThinkProcessService thinkProcessService,
            TrillianAttributeStore attributeStore,
            TrillianJournalStore journalStore,
            LightLlmService lightLlm) {
        super(thinkProcessService);
        this.attributeStore = attributeStore;
        this.journalStore = journalStore;
        this.lightLlm = lightLlm;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature-A 'adam' (persistent attributes)";
    }

    @Override
    public Map<String, Object> initialAttributes(
            String tenantId, String projectId, String account) {
        Map<String, Object> stored = attributeStore.load(tenantId, projectId, account);
        if (!stored.isEmpty()) {
            log.info("Trillian adam: seeded {} attribute(s) for '{}' from {}",
                    stored.size(), account, TrillianAttributeStore.pathFor(account));
        }
        return stored;
    }

    @Override
    public void attributesChanged(
            ThinkProcessDocument worker, Map<String, Object> attributes) {
        String account = accountOf(worker);
        if (account == null) {
            // No account means no key to file this under. Only reachable
            // with broken wiring, where the attribute write itself was
            // already questionable — say so, don't fail the write.
            log.warn("Trillian adam: worker process '{}' carries no account name — "
                    + "attributes stay ephemeral", worker.getId());
            return;
        }
        attributeStore.save(
                worker.getTenantId(), worker.getProjectId(), account, attributes);
    }

    @Override
    public void accountDiscarded(String tenantId, String projectId, String account) {
        attributeStore.discard(tenantId, projectId, account);
        journalStore.discard(tenantId, projectId, account);
    }

    /**
     * Reflects on a concluded task and, when there is something worth
     * keeping, adds one line to the journal.
     *
     * <p><b>Off the calling thread.</b> The hook fires from inside the
     * worker's reporting tool call, which holds that process's lane; a
     * full model round-trip plus two document accesses there would stall
     * the worker's next turn behind a reflexion nobody is waiting for.
     * The task result reached Control before this was called, so nothing
     * downstream depends on the answer — which is exactly what makes it
     * safe to detach.
     *
     * <p>Fail-open throughout: a reflexion that errors, times out or
     * produces nothing leaves a Trillian that simply did not learn
     * anything from this task — never one whose result went missing.
     */
    @Async
    @Override
    public void taskConcluded(
            ThinkProcessDocument worker, String taskId,
            TaskOutcome outcome, String summary) {
        String account = accountOf(worker);
        if (account == null) {
            return;
        }
        String tenantId = worker.getTenantId();
        String projectId = worker.getProjectId();
        try {
            Map<String, Object> reply = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(REFLECT_RECIPE)
                    .userPrompt(summary)
                    .pebbleVars(Map.of(
                            "taskId", taskId,
                            "outcome", outcome.name(),
                            "summary", summary,
                            // The existing notes are what makes "already
                            // known" answerable — without them the pass
                            // rewrites the same lesson every time.
                            "journal", nullToEmpty(
                                    journalStore.tail(tenantId, projectId, account))))
                    .schema(REFLECT_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .build());
            if (!Boolean.TRUE.equals(reply.get("keep"))) {
                return;
            }
            String entry = String.valueOf(reply.getOrDefault("entry", "")).strip();
            if (entry.isEmpty()) {
                return;
            }
            journalStore.append(tenantId, projectId, account, entry);
            log.info("Trillian adam: journalled a lesson from task '{}' ({})", taskId, outcome);
        } catch (RuntimeException e) {
            log.warn("Trillian adam: reflexion on task '{}' failed: {}", taskId, e.toString());
        }
    }

    /**
     * Attributes (from the shared base) plus what this Trillian has
     * learned. Reflexion that never reaches a prompt is writing without a
     * reader.
     */
    @Override
    public String userPromptAddendum(ThinkProcessDocument process) {
        String base = super.userPromptAddendum(process);
        String account = accountOf(process);
        if (account == null) {
            return base;
        }
        String journal = journalStore.tail(
                process.getTenantId(), process.getProjectId(), account);
        if (journal == null) {
            return base;
        }
        return base + "\n## What you learned earlier\n\n"
                + "Notes you wrote after finishing earlier tasks. They are "
                + "yours and they are about this project — use them before "
                + "rediscovering the same thing.\n\n"
                + journal + "\n";
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** The service account off the worker's own wiring. */
    private static @Nullable String accountOf(ThinkProcessDocument worker) {
        if (worker.getEngineParams() == null) {
            return null;
        }
        Object raw = worker.getEngineParams()
                .get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
