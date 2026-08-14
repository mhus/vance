package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.trillian.TrillianAttributeStore;
import de.mhus.vance.brain.trillian.TrillianJournalStore;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature-A, {@code adam} — the first persistent Trillian.
 *
 * <p>Adds to {@link TrillianNatureBase}, not to Nature void: the attribute
 * map and how it renders into both prompts are shared mechanics, not
 * generation-zero behaviour to inherit. What distinguishes adam is
 * that its attributes <b>outlive the process rows</b>.
 *
 * <p>Under Nature void an attribute lives in {@code engineParams} and dies
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

    /**
     * How often a worker may be surfaced as blocked before the finding
     * says to stop resuming it. Three rounds of twenty minutes is an
     * hour of a loop going nowhere — enough rope, and not more.
     */
    static final int MAX_BLOCKED_RESUMES = 3;

    /** engineParamOverrides key on the *worker*: blocked-surfacing count. */
    static final String PARAM_BLOCKED_SEEN = "trillianBlockedSeen";

    /** A RUNNING worker quieter than this is worth a look. */
    private static final java.time.Duration SILENT_AFTER = java.time.Duration.ofMinutes(45);

    /** LightLlm config profile for the reflexion pass. */
    static final String REFLECT_RECIPE = "trillian-adam-reflect";

    private static final Map<String, Object> REFLECT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "keep", Map.of("type", "boolean"),
                    "entry", Map.of("type", "string"),
                    // Positions in the numbered journal handed to the
                    // pass. Optional: most reflexions prune nothing.
                    "remove", Map.of(
                            "type", "array",
                            "items", Map.of("type", "integer"))),
            "required", java.util.List.of("keep", "entry"));

    /** Only picks a name and a trait — nothing here needs to be unguessable. */
    private final java.util.Random random = new java.util.Random();

    private final TrillianAttributeStore attributeStore;
    private final TrillianJournalStore journalStore;
    private final LightLlmService lightLlm;
    private final TrillianCharacterCatalog characterCatalog;

    public TrillianNatureAdam(
            ThinkProcessService thinkProcessService,
            TrillianAttributeStore attributeStore,
            TrillianJournalStore journalStore,
            LightLlmService lightLlm,
            TrillianCharacterCatalog characterCatalog) {
        super(thinkProcessService);
        this.attributeStore = attributeStore;
        this.journalStore = journalStore;
        this.lightLlm = lightLlm;
        this.characterCatalog = characterCatalog;
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
    public String callName(Map<String, Object> attributes) {
        Object given = attributes.get(TrillianCharacterCatalog.ATTR_NAME);
        return given instanceof String name && !name.isBlank()
                ? name.strip()
                : super.callName(attributes);
    }

    @Override
    public Map<String, Object> initialAttributes(
            String tenantId, String projectId, String account) {
        Map<String, Object> stored = attributeStore.load(tenantId, projectId, account);
        if (!stored.isEmpty()) {
            log.info("Trillian adam: seeded {} attribute(s) for '{}' from {}",
                    stored.size(), account, TrillianAttributeStore.pathFor(account));
            return stored;
        }
        // Nothing stored: this account has never run. Give it a
        // character — and write it down immediately, because an identity
        // regenerated on the next boot is not an identity.
        Map<String, Object> character = characterCatalog.generate(tenantId, projectId, random);
        attributeStore.save(tenantId, projectId, account, character);
        log.info("Trillian adam: '{}' starts as '{}' ({})", account,
                character.get(TrillianCharacterCatalog.ATTR_NAME),
                character.get(TrillianCharacterCatalog.ATTR_GENDER));
        return character;
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
            // Numbered, because the pass may point at entries to drop —
            // by position, not by quoting them back: a model asked to
            // repeat a line verbatim in order to delete it will sooner or
            // later delete something it mistyped.
            List<String> existing = journalStore.entries(tenantId, projectId, account);
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
                            "journal", numbered(existing)))
                    .schema(REFLECT_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .build());

            // Prune before appending, so a position never refers to the
            // line this same reflexion just added.
            List<Integer> obsolete = positions(reply.get("remove"), existing.size());
            if (!obsolete.isEmpty()) {
                journalStore.removeEntries(tenantId, projectId, account, obsolete);
            }
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
                + journal + "\n\n"
                + "Each note carries the date you wrote it. A note about a "
                + "**state** — something locked, missing, unavailable — was "
                + "true then and may not be now. Before refusing a task on "
                + "the strength of one, check it cheaply (a single read or "
                + "info call); being wrong here is worse than rediscovering, "
                + "because a refusal produces no failure for you to learn "
                + "from. A note about how something **works** needs no such "
                + "check.\n";
    }

    /** The journal as a numbered list, or empty when there is none. */
    private static String numbered(List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            String text = entries.get(i);
            sb.append(i + 1).append(". ")
                    .append(text.startsWith("- ") ? text.substring(2) : text)
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * The {@code remove} array as valid 1-based positions. Anything that
     * is not an in-range integer is dropped silently — a stray index
     * should cost one skipped prune, not the whole reflexion.
     */
    private static List<Integer> positions(@Nullable Object raw, int size) {
        if (!(raw instanceof java.util.Collection<?> values) || size == 0) {
            return List.of();
        }
        List<Integer> out = new java.util.ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number n) {
                int pos = n.intValue();
                if (pos >= 1 && pos <= size && !out.contains(pos)) {
                    out.add(pos);
                }
            }
        }
        return out;
    }

    /**
     * Looks over the workers this loop owns and reports what will not
     * resolve itself.
     *
     * <p>Derived, not remembered: every finding here comes from a process
     * status, so nothing depends on the Trillian having written something
     * down at the right moment. What it cannot see — a promise made in
     * conversation, "I'll come back to this on Friday" — needs a written
     * list, which is a separate matter.
     */
    @Override
    public List<SelfCheckFinding> selfCheckFindings(ThinkProcessDocument loop) {
        List<SelfCheckFinding> findings = new java.util.ArrayList<>();
        Instant now = Instant.now();
        for (ThinkProcessDocument worker : thinkProcessService.findByParentProcessId(loop.getId())) {
            switch (worker.getStatus()) {
                case IDLE -> findings.add(new SelfCheckFinding(
                        SelfCheckFinding.Kind.WORKER_WAITING,
                        nameOf(worker), worker.getId(),
                        "parked since " + since(worker.getUpdatedAt(), now)
                                + " — it asked something and nothing will reach it "
                                + "until someone answers"));
                case BLOCKED -> findings.add(blockedFinding(worker, now));
                case RUNNING -> {
                    if (silentFor(worker, now).compareTo(SILENT_AFTER) > 0) {
                        findings.add(new SelfCheckFinding(
                                SelfCheckFinding.Kind.WORKER_SILENT,
                                nameOf(worker), worker.getId(),
                                "running but silent for " + since(worker.getUpdatedAt(), now)
                                        + " — check whether it is still making progress"));
                    }
                }
                default -> {
                    // Terminal states resolved themselves; nothing to say.
                }
            }
        }
        return findings;
    }

    /**
     * A blocked worker, with the one judgement the model must not make
     * freshly each round.
     *
     * <p>Blocked means a safety net tripped — its context is intact and
     * {@code process_steer} would resume it with a fresh budget. Whether
     * that is right depends on something only a reader of the transcript
     * knows: was it working, or going in circles? The counter is there
     * because a model asked that question four times in a row will say
     * "one more try" four times.
     */
    private SelfCheckFinding blockedFinding(ThinkProcessDocument worker, Instant now) {
        int seen = incrementBlockedSeen(worker);
        String detail = seen >= MAX_BLOCKED_RESUMES
                ? "blocked for the " + seen + ". time — it is looping, not working. "
                        + "Do NOT resume it. Report to Control what it managed and stop it."
                : "blocked after hitting a safety net (" + since(worker.getUpdatedAt(), now)
                        + " ago), context intact. Read its transcript: if it was making "
                        + "progress, process_steer it to continue; if it was repeating "
                        + "itself, report that to Control instead.";
        return new SelfCheckFinding(
                SelfCheckFinding.Kind.WORKER_BLOCKED, nameOf(worker), worker.getId(), detail);
    }

    /**
     * Counts how often this worker has been surfaced as blocked. Written
     * here rather than at the resume, because a resume is a model
     * decision and this must not depend on the model reporting it.
     */
    private int incrementBlockedSeen(ThinkProcessDocument worker) {
        Map<String, Object> overrides = worker.getEngineParamOverrides();
        Object raw = overrides == null ? null : overrides.get(PARAM_BLOCKED_SEEN);
        int next = (raw instanceof Number n ? n.intValue() : 0) + 1;
        try {
            thinkProcessService.setEngineParamOverride(worker.getId(), PARAM_BLOCKED_SEEN, next);
        } catch (RuntimeException e) {
            log.warn("Trillian adam: could not count blocked worker '{}': {}",
                    worker.getId(), e.toString());
        }
        return next;
    }

    private static String nameOf(ThinkProcessDocument worker) {
        return worker.getName() == null || worker.getName().isBlank()
                ? worker.getId() : worker.getName();
    }

    private static java.time.Duration silentFor(ThinkProcessDocument worker, Instant now) {
        return worker.getUpdatedAt() == null
                ? java.time.Duration.ZERO
                : java.time.Duration.between(worker.getUpdatedAt(), now);
    }

    private static String since(@Nullable Instant at, Instant now) {
        if (at == null) {
            return "an unknown time";
        }
        long minutes = java.time.Duration.between(at, now).toMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h" : (hours / 24) + " d";
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
