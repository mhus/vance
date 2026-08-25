package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.prompt.ForeignPromptText;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.trillian.TrillianAttributeStore;
import de.mhus.vance.brain.trillian.TrillianJournalStore;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.tools.TrillianAskTool;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
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

    /**
     * How often a state-blocked worker is offered a re-check before the
     * obstacle is treated as a decision. Three rounds on a decelerating
     * cadence is over an hour of the state not changing — long enough to
     * stop asking the world and start asking the human.
     */
    static final int MAX_ASK_PROBES = 3;

    /**
     * The two breaker keys live with {@link TrillianAskTool}, which opens
     * the question the budget belongs to and resets them when a different
     * one is raised. Adam only reads and advances them.
     */
    static final String PARAM_ASK_PROBES = TrillianAskTool.PARAM_ASK_PROBES;
    static final String PARAM_ASK_OPENED_AT = TrillianAskTool.PARAM_ASK_OPENED_AT;

    /**
     * How long the breaker stays shut before allowing one further probe.
     * Long enough that it is not polling, short enough that a lock lifted
     * over lunch is noticed the same afternoon.
     */
    static final java.time.Duration ASK_PROBE_COOLDOWN = java.time.Duration.ofHours(2);

    /**
     * How many unread threads one self-check reports. A backlog is one
     * reason to wake, not forty; and since reported threads are marked
     * read, the next round picks up where this one stopped.
     */
    static final int MAX_UNREAD_PER_CHECK = 5;

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
    private final MaximegalonService maximegalonService;

    public TrillianNatureAdam(
            ThinkProcessService thinkProcessService,
            TrillianAttributeStore attributeStore,
            TrillianJournalStore journalStore,
            LightLlmService lightLlm,
            TrillianCharacterCatalog characterCatalog,
            MaximegalonService maximegalonService) {
        super(thinkProcessService);
        this.attributeStore = attributeStore;
        this.journalStore = journalStore;
        this.lightLlm = lightLlm;
        this.characterCatalog = characterCatalog;
        this.maximegalonService = maximegalonService;
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

    /**
     * The journal as a numbered list, or empty when there is none.
     *
     * <p>An entry may run over several lines. Continuations are indented
     * so the numbering stays readable as numbering — the model answers
     * with indices into this list, and a second line starting at column
     * zero looks like the next item.
     */
    private static String numbered(List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            String text = entries.get(i);
            String body = text.startsWith("- ") ? text.substring(2) : text;
            sb.append(i + 1).append(". ")
                    .append(body.replace("\n", "\n   "))
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
     * status or from a row somebody else wrote, so nothing depends on the
     * Trillian having noted something down at the right moment. What it
     * cannot see — a promise made in conversation, "I'll come back to this
     * on Friday" — needs a written list, which is a separate matter.
     */
    @Override
    public List<SelfCheckFinding> selfCheckFindings(ThinkProcessDocument loop) {
        List<SelfCheckFinding> findings = new java.util.ArrayList<>(unreadInboxFindings(loop));
        Instant now = Instant.now();
        for (ThinkProcessDocument worker : thinkProcessService.findByParentProcessId(loop.getId())) {
            switch (worker.getStatus()) {
                case IDLE -> findings.add(waitingFinding(worker, now));
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
     * Inbox threads the Trillian has not seen yet.
     *
     * <p>The first finding whose subject is not a process. It is also the
     * only one somebody writes <em>at</em> the Trillian: a worker parks
     * itself, but a thread lands because a person or an agent put it there,
     * and nothing else in this loop would ever notice. Without it a
     * Trillian is reachable only through Control's chat — which is exactly
     * the channel a human uses when they are sitting in front of it, and
     * not the one they use when they are not.
     *
     * <p><b>Capped.</b> A backlog of forty threads is not forty reasons to
     * wake — it is one, reported {@value #MAX_UNREAD_PER_CHECK} at a time.
     * The cap does not lose anything: what is reported is marked read at
     * delivery, so the next round surfaces the next batch, oldest first.
     *
     * <p>Read-state failures leave the finding out rather than the whole
     * self-check: a Trillian whose inbox lookup fails should still hear
     * about its stuck workers.
     */
    private List<SelfCheckFinding> unreadInboxFindings(ThinkProcessDocument loop) {
        String account = accountOf(loop);
        if (account == null) return List.of();
        List<SelfCheckFinding> findings = new java.util.ArrayList<>();
        try {
            for (MaximegalonDocument thread : maximegalonService.listUnreadForUser(
                    loop.getTenantId(), account, MAX_UNREAD_PER_CHECK)) {
                findings.add(new SelfCheckFinding(
                        SelfCheckFinding.Kind.INBOX_UNREAD,
                        String.valueOf(thread.getId()),
                        String.valueOf(thread.getId()),
                        unreadDetail(thread)));
            }
        } catch (RuntimeException e) {
            log.warn("Trillian adam: unread inbox lookup for '{}' failed: {}",
                    account, e.toString());
            return List.of();
        }
        return findings;
    }

    /**
     * The one line the loop gets about a thread: enough to decide whether
     * to open it, not enough to answer from.
     *
     * <p>Deliberately not the body. The listing projects the discussion out
     * — the excerpt of an excerpt would be the worst of both, long enough
     * to cost tokens on every quiet round and short enough that the loop
     * would answer from a fragment. It gets the handle and reads the thread
     * if it cares.
     *
     * <p>Title and originator are somebody else's words, and this line is
     * delivered as an {@code ExternalCommand} the loop reads as its reason to
     * act — so they go through {@link ForeignPromptText#quoted}, like every
     * other borrowed string on a prompt path. Anyone who may write to this
     * Trillian's inbox ({@code thread_invite}, {@code inbox_post}) picks the
     * title; unquoted, a multi-line one would break out of the bullet and read
     * as another statement by the system that wrote the block.
     */
    private static String unreadDetail(MaximegalonDocument thread) {
        return "unread " + thread.getType()
                + " from " + ForeignPromptText.quoted(thread.getOriginatorUserId(), 80)
                + ": " + ForeignPromptText.quoted(thread.getTitle())
                + (thread.isRequiresAction() ? " — waits on an answer" : "")
                + " (read it with thread_get)";
    }

    /**
     * The loop has been handed these findings — now write down what
     * reporting them costs.
     *
     * <p>Every effect that used to sit inside the gathering lives here:
     * the probe budget, the blocked round, the close of a worker that has
     * been going in circles. The decisions are re-derived rather than
     * carried over, which works because each is a function of persisted
     * state that nothing else touches between the two calls — and it
     * keeps the gathering something that can be run twice without
     * consequence.
     *
     * <p>Per finding, and each guarded: a worker that has since gone away,
     * or a write that fails, must not cost the loop the rest of its
     * self-check.
     *
     * <p><b>For {@link SelfCheckFinding.Kind#INBOX_UNREAD} the effect is
     * not bookkeeping, it is the termination condition.</b> An unread
     * thread stays unread until somebody marks it, so a self-check that
     * only reported it would produce the identical finding on the next
     * round, and the one after that — a Trillian woken forever by the same
     * message. Marking it here, in code, is what makes the wakeup
     * one-shot; leaving it to the model would mean a forgotten tool call
     * turns into an endless loop.
     *
     * <p>It sits here and not in the gathering for the reason the whole
     * hook exists: a due tick that ends in no wakeup must not have marked
     * anything read. The thread would then be silently swallowed — the one
     * failure mode worse than repeating.
     */
    @Override
    public void selfCheckDelivered(ThinkProcessDocument loop, List<SelfCheckFinding> findings) {
        Instant now = Instant.now();
        for (SelfCheckFinding finding : findings) {
            if (finding.kind() == SelfCheckFinding.Kind.INBOX_UNREAD) {
                markThreadRead(loop, finding.subjectId());
                continue;
            }
            ThinkProcessDocument worker =
                    thinkProcessService.findById(finding.subjectId()).orElse(null);
            if (worker == null) continue;
            try {
                switch (finding.kind()) {
                    case WORKER_WAITING -> {
                        if (probeWorthwhile(worker)) {
                            applyProbeDecision(worker, probeDecision(worker, now));
                        }
                    }
                    case WORKER_BLOCKED -> {
                        int seen = blockedSeenAfterThisRound(worker);
                        setOverride(worker, PARAM_BLOCKED_SEEN, seen);
                        if (seen >= MAX_BLOCKED_RESUMES) {
                            closeLoopingWorker(worker);
                        }
                    }
                    case WORKER_SILENT -> {
                        // Nothing is spent on saying "this is quiet".
                    }
                    case INBOX_UNREAD -> {
                        // Handled above — its subject is not a process.
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Trillian adam: could not record self-check on '{}': {}",
                        finding.subjectId(), e.toString());
            }
        }
    }

    /**
     * Takes the thread out of the Trillian's unread index, so the wakeup it
     * caused happens once.
     *
     * <p>{@code markRead} touches read state only — the thread's status is
     * untouched, so an ask that was handed over is still open and still
     * waiting for whoever has to decide it. Seeing something is not
     * answering it.
     *
     * <p>A failure here is logged and dropped rather than rethrown: the
     * price is the same thread being reported again next round, which is
     * annoying and self-correcting. Letting it escape would cost the loop
     * the bookkeeping of every finding after it.
     */
    private void markThreadRead(ThinkProcessDocument loop, String threadId) {
        String account = accountOf(loop);
        if (account == null) return;
        try {
            maximegalonService.markRead(loop.getTenantId(), threadId, account);
            log.debug("Trillian adam: thread '{}' marked read for '{}'", threadId, account);
        } catch (RuntimeException e) {
            log.warn("Trillian adam: could not mark thread '{}' read for '{}': {}",
                    threadId, account, e.toString());
        }
    }

    /**
     * A parked worker, and whether looking again could mean anything.
     *
     * <p>The worker said at asking time what stood in its way. A
     * <em>state</em> — a locked file, a missing document — can clear
     * without anyone answering, so one cheap re-check beats disturbing a
     * human who may already have fixed it. A <em>decision</em> stays open
     * however long anyone waits; re-checking it only confirms what is
     * already known.
     *
     * <p>Re-checking is not free forever. After
     * {@value #MAX_ASK_PROBES} rounds that changed nothing, the state has
     * behaved like a decision for long enough to be treated as one — the
     * circuit opens, and the human is asked instead of the world.
     */
    private SelfCheckFinding waitingFinding(ThinkProcessDocument worker, Instant now) {
        String waited = since(worker.getUpdatedAt(), now);
        String detail = probeWorthwhile(worker) && probeDecision(worker, now).granted()
                ? "parked since " + waited + ", blocked by a state that may have "
                        + "changed since. Before asking the human again, process_steer it "
                        + "once with a short nudge to re-check its obstacle and carry on if "
                        + "it cleared. If it comes back with the same question, then ask."
                : "parked since " + waited + " — nothing will reach it until someone "
                        + "answers. Ask Control again, briefly, saying how long it has "
                        + "waited.";
        return new SelfCheckFinding(
                SelfCheckFinding.Kind.WORKER_WAITING, nameOf(worker), worker.getId(), detail);
    }

    /**
     * Whether nudging this worker to look again could mean anything.
     *
     * <p>Both halves are needed. The blocker says the obstacle was a
     * state; the pending marker says a question is still open at all. A
     * worker that asked once, was answered, carried on and then parked on
     * a natural stop still carries the blocker of the question it long
     * since resolved — nudging that one to "re-check its obstacle" is
     * advice about nothing, and it spends a probe doing it.
     */
    private boolean probeWorthwhile(ThinkProcessDocument worker) {
        return awaitsAnswer(worker) && isStateBlocker(worker);
    }

    /** Whether an unanswered {@code trillian_ask} question is open. */
    private boolean awaitsAnswer(ThinkProcessDocument worker) {
        Map<String, Object> overrides = worker.getEngineParamOverrides();
        Object raw = overrides == null ? null
                : overrides.get(de.mhus.vance.brain.trillian.TrillianWorkerEngine.PARAM_ASK_PENDING);
        return Boolean.TRUE.equals(raw);
    }

    private boolean isStateBlocker(ThinkProcessDocument worker) {
        Map<String, Object> overrides = worker.getEngineParamOverrides();
        Object raw = overrides == null ? null : overrides.get(TrillianAskTool.PARAM_ASK_BLOCKER);
        return TrillianAskTool.BLOCKER_STATE.equals(raw);
    }

    /**
     * Whether this parked worker gets another look at its obstacle.
     *
     * <p>Three rounds on a decelerating cadence, then the circuit opens
     * and the human is asked instead of the world — a state that has not
     * moved in over an hour is behaving like a decision.
     *
     * <p>But open is not forever. After {@link #ASK_PROBE_COOLDOWN} the
     * breaker goes <b>half-open</b>: exactly one further probe, then shut
     * again for the same span. A lock that survived three rounds can
     * still be gone by the afternoon, and never looking again would make
     * "give up" mean "give up permanently" — for the price of one worker
     * turn every two hours.
     */
    private ProbeDecision probeDecision(ThinkProcessDocument worker, Instant now) {
        Map<String, Object> overrides = worker.getEngineParamOverrides();
        int probes = intOverride(overrides, PARAM_ASK_PROBES);
        if (probes < MAX_ASK_PROBES) {
            return new ProbeDecision(true, probes + 1, null);
        }
        long openedAt = intOverrideLong(overrides, PARAM_ASK_OPENED_AT);
        if (openedAt == 0L) {
            // The circuit opens now; the cool-down starts running.
            return new ProbeDecision(false, null, now.toEpochMilli());
        }
        if (now.toEpochMilli() - openedAt < ASK_PROBE_COOLDOWN.toMillis()) {
            return new ProbeDecision(false, null, null);
        }
        // Half-open: one trial, and the cool-down restarts whatever it
        // finds. Success ends the episode on its own — the worker carries
        // on, the pending marker goes with its next turn, and a later,
        // different question resets the budget in TrillianAskTool.
        return new ProbeDecision(true, null, now.toEpochMilli());
    }

    /**
     * What the breaker says, and what has to be written down if the
     * finding built from it is actually delivered.
     *
     * <p>Split from the writing so
     * {@link #selfCheckFindings(ThinkProcessDocument)} stays free of side
     * effects: a due tick that ends in no wakeup must not have spent a
     * probe. The decision is derived from persisted state alone, so
     * re-deriving it at delivery time takes the same branch.
     */
    private record ProbeDecision(
            boolean granted, @Nullable Integer nextProbes, @Nullable Long nextOpenedAt) {
    }

    private void applyProbeDecision(ThinkProcessDocument worker, ProbeDecision decision) {
        if (decision.nextProbes() != null) {
            setOverride(worker, PARAM_ASK_PROBES, decision.nextProbes());
        }
        if (decision.nextOpenedAt() != null) {
            setOverride(worker, PARAM_ASK_OPENED_AT, decision.nextOpenedAt());
            if (decision.granted()) {
                log.info("Trillian adam: worker '{}' got a half-open re-check after {}",
                        worker.getId(), ASK_PROBE_COOLDOWN);
            }
        }
    }

    /**
     * Ends the episode of a worker that has been blocked too often.
     *
     * <p>Closing it here rather than telling the loop to do it: stopping
     * is not a judgement, it follows from a count, and a cleanup that
     * depends on the model remembering to perform it is a cleanup that
     * eventually does not happen. The close also reaches the loop as a
     * terminal event, so it learns the episode is over even if it ignores
     * the finding.
     */
    private void closeLoopingWorker(ThinkProcessDocument worker) {
        try {
            thinkProcessService.closeProcess(
                    worker.getId(), de.mhus.vance.api.thinkprocess.CloseReason.STOPPED);
            log.info("Trillian adam: stopped looping worker '{}' after {} safety-net rounds",
                    worker.getId(), MAX_BLOCKED_RESUMES);
        } catch (RuntimeException e) {
            log.warn("Trillian adam: could not stop looping worker '{}': {}",
                    worker.getId(), e.toString());
        }
    }

    private void setOverride(ThinkProcessDocument worker, String key, Object value) {
        try {
            thinkProcessService.setEngineParamOverride(worker.getId(), key, value);
        } catch (RuntimeException e) {
            log.warn("Trillian adam: could not write '{}' on '{}': {}",
                    key, worker.getId(), e.toString());
        }
    }

    private static int intOverride(@Nullable Map<String, Object> overrides, String key) {
        Object raw = overrides == null ? null : overrides.get(key);
        return raw instanceof Number n ? n.intValue() : 0;
    }

    private static long intOverrideLong(@Nullable Map<String, Object> overrides, String key) {
        Object raw = overrides == null ? null : overrides.get(key);
        return raw instanceof Number n ? n.longValue() : 0L;
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
        int seen = blockedSeenAfterThisRound(worker);
        String detail;
        if (seen >= MAX_BLOCKED_RESUMES) {
            // No half-open state here, deliberately. Frankie's two safety
            // nets — wallclock and idle-stuck — both mean "it is going in
            // circles", and circles do not heal by waiting; a trial every
            // two hours would revive a worker that runs into the same net
            // for the same reason. What was missing is the opposite: the
            // episode never ended. The finding said "do not resume" and
            // nobody closed it, so it was reported again on every round.
            //
            // The close itself happens at delivery — see
            // selfCheckDelivered. Written in the past tense here because
            // by the time the loop reads this line, it has.
            detail = "was stopped after " + seen + " rounds in a safety net — it was "
                    + "looping, not working, and waiting would not have changed that. "
                    + "Read its transcript for whatever it did manage and report that "
                    + "to Control. Do not spawn a replacement for the same approach.";
        } else {
            detail = "blocked after hitting a safety net (" + since(worker.getUpdatedAt(), now)
                    + " ago), context intact. Read its transcript: if it was making "
                    + "progress, process_steer it to continue; if it was repeating "
                    + "itself, report that to Control instead.";
        }
        return new SelfCheckFinding(
                SelfCheckFinding.Kind.WORKER_BLOCKED, nameOf(worker), worker.getId(), detail);
    }

    /**
     * How often this worker will have been surfaced as blocked once this
     * round is reported — the count the finding speaks about.
     *
     * <p>Counted here rather than at the resume, because a resume is a
     * model decision and this must not depend on the model reporting it.
     * Persisted at delivery, so a round nobody was told about does not
     * consume one.
     */
    private static int blockedSeenAfterThisRound(ThinkProcessDocument worker) {
        Map<String, Object> overrides = worker.getEngineParamOverrides();
        Object raw = overrides == null ? null : overrides.get(PARAM_BLOCKED_SEEN);
        return (raw instanceof Number n ? n.intValue() : 0) + 1;
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
