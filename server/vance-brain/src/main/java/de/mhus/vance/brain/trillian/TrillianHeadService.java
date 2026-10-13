package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lifecycle and dispatch for the conversation heads of a multi-headed
 * Trillian: one head process per discussion thread, woken per contribution,
 * concluded with the thread.
 *
 * <h2>What a head is</h2>
 * A {@link ThinkProcessDocument} in the Trillian's own loop session (so it
 * dies with the loop session's cascades and lives in the hub, on the home
 * pod), running the control engine on the Nature's
 * {@link TrillianNature#headRecipe()}. The head answers in its thread via
 * {@code thread_message_add} and hands real work to the loop via
 * {@code task_enqueue} — structured tasks are the only thing the loop ever
 * sees of a conversation, never the foreign text. The thread is the record;
 * the head's chat log is working memory.
 *
 * <h2>One closing path</h2>
 * Every head ends the same way: the sweep finds its thread archived (or
 * gone) and closes the process. There is deliberately no
 * archive-event→close shortcut — the head that archives its own thread does
 * so <em>mid-turn</em>, and a synchronous close from inside the tool call
 * would rip the process status out from under the running turn. The sweep
 * also carries the mechanical ends: inactivity close, the message-limit
 * close, the unanswered fallback. All four causes funnel through
 * {@link TrillianNature#headConcluded}, exactly once per conversation.
 *
 * <p>See {@code planning/trillian-exposure.md} §3.2, §3.6, §3.11.
 */
@Service
@Slf4j
public class TrillianHeadService {

    /** engineParams marker: the thread this process is the head of. */
    public static final String PARAM_HEAD_THREAD = "trillianHeadThread";

    /** Process-name prefix of a head; the thread id is appended. */
    public static final String HEAD_NAME_PREFIX = "trillian-head-";

    /** The command a woken head finds when a thread received a contribution. */
    public static final String COMMAND_THREAD_MESSAGE = "trillian_thread_message";

    /** The command for "conclude this conversation now" (limit nudge). */
    public static final String COMMAND_THREAD_CONCLUDE = "trillian_thread_conclude";

    /** ExternalCommand params. */
    public static final String PARAM_THREAD_ID = "threadId";

    public static final String PARAM_FROM = "from";
    public static final String PARAM_PREVIEW = "preview";
    public static final String PARAM_REMINDER = "reminder";

    /** Sweep markers on the head's engineParamOverrides. */
    private static final String OVERRIDE_NUDGED_FOR = "headNudgedFor";

    private static final String OVERRIDE_CONCLUDE_NUDGED = "headConcludeNudged";

    /**
     * The message-count thresholds for the limit close. The head is nudged at
     * {@link #LIMIT_NUDGE} and the sweep closes mechanically at
     * {@link #LIMIT_CLOSE}; the hard wall remains
     * {@link MaximegalonService#MAX_MESSAGES}, which the doors surface as a
     * clear refusal. The gap is wide enough that a chatty head gets its
     * nudge in, and narrow enough that the wall is unreachable in practice.
     */
    static final int LIMIT_NUDGE = 480;

    static final int LIMIT_CLOSE = 495;

    private static final int PREVIEW_LIMIT = 240;

    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final EngineMessageRouter messageRouter;
    private final MaximegalonService threads;
    private final TrillianNatureRegistry natureRegistry;
    private final TrillianModelGate modelGate;

    private final Duration reminderAfter;
    private final Duration fallbackAfter;
    private final Duration inactivityAfter;

    public TrillianHeadService(
            ThinkProcessService thinkProcessService,
            ThinkEngineService thinkEngineService,
            RecipeResolver recipeResolver,
            EngineMessageRouter messageRouter,
            MaximegalonService threads,
            TrillianNatureRegistry natureRegistry,
            TrillianModelGate modelGate,
            @Value("${vance.trillian.head.reminder-after-ms:300000}") long reminderAfterMs,
            @Value("${vance.trillian.head.fallback-after-ms:3600000}") long fallbackAfterMs,
            @Value("${vance.trillian.head.inactivity-ms:604800000}") long inactivityMs) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineService = thinkEngineService;
        this.recipeResolver = recipeResolver;
        this.messageRouter = messageRouter;
        this.threads = threads;
        this.natureRegistry = natureRegistry;
        this.modelGate = modelGate;
        this.reminderAfter = Duration.ofMillis(reminderAfterMs);
        this.fallbackAfter = Duration.ofMillis(fallbackAfterMs);
        this.inactivityAfter = Duration.ofMillis(inactivityMs);
    }

    // ──────────────────── Dispatch: a contribution landed ────────────────────

    /**
     * Wakes the head of {@code item}'s thread about {@code message}: finds or
     * spawns the head, then routes one {@link #COMMAND_THREAD_MESSAGE}
     * ExternalCommand to it through the engine-message router — which puts
     * the turn on the hub's home pod, whatever pod the contribution arrived
     * on.
     *
     * <p>Refuses silently (one WARN) when no head could be had — the limit
     * being reached is the only in-mechanics reason. The contribution stays
     * in the thread; a head freeing up does not replay it, the sweep's
     * unanswered-fallback eventually answers it mechanically.
     */
    public void dispatchContribution(
            ThinkProcessDocument loop, TrillianNature nature, MaximegalonDocument item, MaximegalonMessage message) {
        Optional<ThinkProcessDocument> head = ensureHead(loop, nature, item.getId());
        if (head.isEmpty()) {
            return;
        }
        SteerMessage.ExternalCommand command = new SteerMessage.ExternalCommand(
                Instant.now(),
                /*idempotencyKey*/ "thread-message-" + message.getId(),
                COMMAND_THREAD_MESSAGE,
                Map.of(
                        PARAM_THREAD_ID, item.getId(),
                        PARAM_FROM, authorOf(message),
                        PARAM_PREVIEW, preview(message.getBody()),
                        PARAM_REMINDER, false));
        boolean delivered =
                messageRouter.dispatch(/*sender*/ null, head.get().getId(), SteerMessageCodec.toDocument(command));
        if (!delivered) {
            log.warn(
                    "Trillian head: contribution for thread '{}' could not be delivered to head '{}'",
                    item.getId(),
                    head.get().getId());
        }
    }

    // ──────────────────── Head lifecycle ────────────────────

    /**
     * The live head for {@code threadId}, if this loop's session holds one.
     * The name is the join: {@link #HEAD_NAME_PREFIX}{@code + threadId}, so a
     * head survives being looked up from anywhere and cannot exist twice.
     */
    public Optional<ThinkProcessDocument> headOf(ThinkProcessDocument loop, String threadId) {
        return liveHeads(loop).stream()
                .filter(h -> threadId.equals(headThreadOf(h)))
                .findFirst();
    }

    /** The live heads of this loop, oldest first — the limit population. */
    public List<ThinkProcessDocument> liveHeads(ThinkProcessDocument loop) {
        return thinkProcessService.findBySession(loop.getTenantId(), loop.getSessionId()).stream()
                .filter(p -> p.getName() != null && p.getName().startsWith(HEAD_NAME_PREFIX))
                .filter(p -> p.getStatus() != ThinkProcessStatus.CLOSED)
                .sorted(java.util.Comparator.comparing(
                        ThinkProcessDocument::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Finds or spawns the head for {@code threadId}:
     *
     * <ol>
     *   <li>a live head exists → it (a crashed-and-rebuilt head re-reads the
     *       thread — the thread is the record, so nothing is lost);</li>
     *   <li>the head limit is reached → empty (the conversation waits for
     *       the sweep's mechanical answer — the entry gate that would refuse
     *       the conversation outright comes with exposure, Phase 2);</li>
     *   <li>the Nature's head recipe resolves, the model gate passes → a
     *       fresh head, IDLE, in the loop's session and the hub project,
     *       wired to the loop as its peer.</li>
     * </ol>
     *
     * <p>Creation is a plain Mongo write from whatever pod the contribution
     * arrived on; the dispatch routes the first turn to the home pod. Two
     * pods racing the same fresh thread collide on the process name, and the
     * loser adopts the winner's head on the next contribution.
     */
    public Optional<ThinkProcessDocument> ensureHead(
            ThinkProcessDocument loop, TrillianNature nature, String threadId) {
        Optional<ThinkProcessDocument> existing = headOf(loop, threadId);
        if (existing.isPresent()) {
            return existing;
        }
        if (liveHeads(loop).size() >= nature.headLimit()) {
            log.warn(
                    "Trillian head: '{}' is at its head limit ({}) — conversation '{}' waits",
                    accountOf(loop),
                    nature.headLimit(),
                    threadId);
            return Optional.empty();
        }
        String account = accountOf(loop);
        String headName = HEAD_NAME_PREFIX + threadId;
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                loop.getTenantId(),
                loop.getProjectId(),
                nature.headRecipe(),
                TrillianSessionBootstrapper.HEADLESS_PROFILE,
                /*callerParams*/ null);
        try {
            modelGate.checkLoopModel(
                    loop.getTenantId(),
                    account,
                    loop.getProjectId(),
                    loop.getProjectId(),
                    de.mhus.vance.brain.ai.AiModelResolver.parseModelSpec(applied.params()));
        } catch (IllegalStateException denied) {
            log.warn("Trillian head: refusing head for thread '{} — {}'", threadId, denied.getMessage());
            return Optional.empty();
        }
        ThinkEngine engine = thinkEngineService
                .resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException("Head recipe '" + nature.headRecipe()
                        + "' references unknown engine '" + applied.engine() + "'"));
        Map<String, Object> params = new LinkedHashMap<>();
        if (applied.params() != null) {
            params.putAll(applied.params());
        }
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, nature.id());
        params.put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, loop.getId());
        params.put(TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID, loop.getSessionId());
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, account);
        params.put(PARAM_HEAD_THREAD, threadId);
        ThinkProcessDocument head;
        try {
            head = thinkProcessService.create(
                    loop.getTenantId(),
                    loop.getProjectId(),
                    loop.getSessionId(),
                    headName,
                    engine.name(),
                    engine.version(),
                    /*title*/ "Conversation head " + threadId,
                    /*goal*/ null,
                    /*parentProcessId*/ null,
                    params,
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null ? null : java.util.Set.copyOf(applied.allowedSkills()));
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException race) {
            log.debug("Trillian head: '{}' exists already — adopting it on the next contribution", headName);
            return Optional.empty();
        }
        // IDLE, not started: a head has no greeting turn — its first turn is
        // the contribution that spawned it, and the dispatch schedules it.
        thinkProcessService.updateStatus(head.getId(), ThinkProcessStatus.IDLE);
        log.info(
                "Trillian head: spawned '{}' for thread '{}' of '{}' (recipe '{}')",
                head.getId(),
                threadId,
                account,
                applied.name());
        return Optional.of(head);
    }

    // ──────────────────── Closing ────────────────────

    /**
     * Closes the head of {@code threadId} if one lives — the one funnel for a
     * concluded conversation. Idempotent by status check, so the sweep can
     * call it every round without double-firing
     * {@link TrillianNature#headConcluded}.
     */
    public void closeHeadFor(ThinkProcessDocument loop, String threadId, TrillianNature.HeadConclusion cause) {
        Optional<ThinkProcessDocument> head = headOf(loop, threadId);
        if (head.isEmpty()) {
            return;
        }
        closeHead(loop, head.get(), threadId, cause);
    }

    private void closeHead(
            ThinkProcessDocument loop,
            ThinkProcessDocument head,
            String threadId,
            TrillianNature.HeadConclusion cause) {
        if (head.getStatus() == ThinkProcessStatus.CLOSED) {
            return;
        }
        thinkProcessService.closeProcess(head.getId(), CloseReason.DONE);
        log.info("Trillian head: closed '{}' (thread '{}', cause {})", head.getId(), threadId, cause);
        try {
            natureRegistry
                    .resolve(TrillianSessionBootstrapper.readNature(loop))
                    .headConcluded(loop.getTenantId(), accountOf(loop), threadId, cause);
        } catch (RuntimeException e) {
            log.warn("Trillian head: headConcluded hook for thread '{}' failed: {}", threadId, e.toString());
        }
    }

    /**
     * Posts the closing contribution as the Trillian account and archives the
     * thread — the mechanical end for limit, inactivity and account-end. The
     * post is best-effort: a thread already at the message wall gets no
     * closing line, only the archive.
     */
    public void concludeThread(String tenantId, String account, String threadId, String closingText) {
        try {
            threads.postMessage(tenantId, threadId, account, closingText, /*parentId*/ null);
        } catch (RuntimeException e) {
            log.warn("Trillian head: closing contribution for thread '{}' failed: {}", threadId, e.toString());
        }
        threads.archive(tenantId, threadId, account);
    }

    /**
     * Every open conversation of {@code account}: posted over, archived,
     * head closed. The account-end path (the Trillian's control session is
     * closing, the loop session's cascade has already taken the heads down).
     * Counterparts must not write into a thread whose account no longer
     * exists — the closing line tells them where they stand.
     *
     * @return the threads concluded by this call
     */
    public List<String> concludeOpenThreads(String tenantId, String account) {
        return openDiscussionsOf(tenantId, account).stream()
                .map(item -> {
                    concludeThread(
                            tenantId,
                            account,
                            item.getId(),
                            "(automated) This agent has been shut down. The conversation is closed.");
                    return item.getId();
                })
                .toList();
    }

    // ──────────────────── Sweep ────────────────────

    /**
     * One round of bookkeeping for one multi-headed Trillian: heads whose
     * thread ended, unanswered contributions (reminder, then mechanical
     * fallback), the message-limit close, the inactivity close.
     *
     * <p>Safe to run concurrently on several pods — every action is either
     * an idempotent write guarded by a status/marker check or a post the
     * idempotency key dedupes. Only the hub's home pod calls it in practice
     * (see {@code TrillianHeadSweepTick}).
     */
    public void sweepLoop(ThinkProcessDocument loop, Instant now) {
        String account = accountOf(loop);
        String tenantId = loop.getTenantId();

        // Heads first: a thread that ended takes its head down, wherever the
        // end came from — the head's own archive call, a person, another pod.
        for (ThinkProcessDocument head : liveHeads(loop)) {
            String threadId = headThreadOf(head);
            if (threadId == null) {
                continue;
            }
            Optional<MaximegalonDocument> item = threads.findById(tenantId, threadId);
            if (item.isEmpty() || item.get().getStatus() == de.mhus.vance.api.inbox.MaximegalonStatus.ARCHIVED) {
                closeHead(loop, head, threadId, TrillianNature.HeadConclusion.COMPLETED);
            }
        }

        // Threads second — headless ones are swept too: a crashed head and no
        // further contribution is exactly the case nothing else would close.
        for (MaximegalonDocument item : openDiscussionsOf(tenantId, account)) {
            sweepThread(loop, item, now);
        }
    }

    private void sweepThread(ThinkProcessDocument loop, MaximegalonDocument item, Instant now) {
        List<MaximegalonMessage> messages = messagesOf(item);
        // Message limit: nudge the head near the wall, close mechanically
        // before the wall makes every further contribution a 500→refusal.
        if (messages.size() >= LIMIT_CLOSE) {
            concludeThread(
                    item.getTenantId(),
                    accountOf(loop),
                    item.getId(),
                    "(automated) This conversation reached its message limit and is closed. "
                            + "Please open a new conversation to continue.");
            return;
        }
        Optional<ThinkProcessDocument> head = headOf(loop, item.getId());
        if (messages.size() >= LIMIT_NUDGE) {
            if (head.isPresent() && !Boolean.TRUE.equals(overrideOf(head.get(), OVERRIDE_CONCLUDE_NUDGED))) {
                thinkProcessService.setEngineParamOverride(head.get().getId(), OVERRIDE_CONCLUDE_NUDGED, true);
                SteerMessage.ExternalCommand conclude = new SteerMessage.ExternalCommand(
                        Instant.now(),
                        /*idempotencyKey*/ "thread-conclude-" + item.getId(),
                        COMMAND_THREAD_CONCLUDE,
                        Map.of(PARAM_THREAD_ID, item.getId()));
                messageRouter.dispatch(/*sender*/ null, head.get().getId(), SteerMessageCodec.toDocument(conclude));
            }
            return; // near the limit, answering again is what the nudge covers
        }
        // Inactivity: no contribution from anyone for the configured window
        // — close, with the head (the sweep's closeHead pass on the next
        // round, or this round's heads-first pass once archive has landed).
        Instant lastActivity = messages.isEmpty() ? item.getCreatedAt() : createdAtOf(messages, item);
        if (lastActivity != null && Duration.between(lastActivity, now).compareTo(inactivityAfter) > 0) {
            concludeThread(
                    item.getTenantId(),
                    accountOf(loop),
                    item.getId(),
                    "(automated) This conversation has been inactive and is now closed. "
                            + "Please open a new conversation to continue.");
            return;
        }
        if (head.isEmpty()) {
            return;
        }
        // Unanswered contribution: reminder, then fallback. The marker is the
        // message id, so a *new* contribution resets the ladder — the head
        // gets a fresh chance per contribution, not per thread.
        MaximegalonMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last == null || accountOf(loop).equals(authorOf(last))) {
            return; // answered — nothing owed
        }
        String nudgedFor = java.util.Objects.toString(overrideOf(head.get(), OVERRIDE_NUDGED_FOR), null);
        java.time.Duration age = last.getCreatedAt() == null ? null : Duration.between(last.getCreatedAt(), now);
        if (age == null) {
            return;
        }
        if (!last.getId().equals(nudgedFor) && age.compareTo(reminderAfter) > 0) {
            thinkProcessService.setEngineParamOverride(head.get().getId(), OVERRIDE_NUDGED_FOR, last.getId());
            SteerMessage.ExternalCommand reminder = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ "thread-reminder-" + last.getId(),
                    COMMAND_THREAD_MESSAGE,
                    Map.of(
                            PARAM_THREAD_ID, item.getId(),
                            PARAM_FROM, authorOf(last),
                            PARAM_PREVIEW, preview(last.getBody()),
                            PARAM_REMINDER, true));
            messageRouter.dispatch(/*sender*/ null, head.get().getId(), SteerMessageCodec.toDocument(reminder));
            return;
        }
        if (last.getId().equals(nudgedFor) && age.compareTo(fallbackAfter) > 0) {
            log.warn(
                    "Trillian head: no answer for '{}' in thread '{}' — posting the mechanical fallback",
                    last.getId(),
                    item.getId());
            try {
                threads.postMessage(
                        item.getTenantId(),
                        item.getId(),
                        accountOf(loop),
                        "(automated) No answer was produced. The conversation stays open — "
                                + "write again and it will be picked up.",
                        /*parentId*/ null);
            } catch (RuntimeException e) {
                log.warn("Trillian head: fallback contribution for thread '{}' failed: {}", item.getId(), e.toString());
            }
            thinkProcessService.setEngineParamOverride(head.get().getId(), OVERRIDE_NUDGED_FOR, null);
        }
    }

    // ──────────────────── Helpers ────────────────────

    /** The open discussion threads assigned to {@code account} — the sweep population. */
    private List<MaximegalonDocument> openDiscussionsOf(String tenantId, String account) {
        return threads.listForUser(tenantId, account, null).stream()
                .filter(item -> isConversation(item))
                .toList();
    }

    /**
     * Whether a thread is a conversation — the only kind a head leads.
     * Asks carry rights (maximegalon §8) and never reach a head; outputs and
     * other effects are not a conversation either.
     */
    public static boolean isConversation(MaximegalonDocument item) {
        return !item.isRequiresAction()
                && item.getEffectType() == null
                && item.getStatus() != de.mhus.vance.api.inbox.MaximegalonStatus.ARCHIVED
                && item.getStatus() != de.mhus.vance.api.inbox.MaximegalonStatus.DISMISSED;
    }

    /** The thread a head process belongs to, from its engineParams marker. */
    public static @Nullable String headThreadOf(ThinkProcessDocument head) {
        Object raw =
                head.getEngineParams() == null ? null : head.getEngineParams().get(PARAM_HEAD_THREAD);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * One sweep marker off a head's overrides — null-safe, because a
     * freshly loaded process row carries a null map until someone sets a
     * marker. The sweep reads heads it never wrote to.
     */
    private static @Nullable Object overrideOf(ThinkProcessDocument head, String key) {
        return head.getEngineParamOverrides() == null
                ? null
                : head.getEngineParamOverrides().get(key);
    }

    private static String accountOf(ThinkProcessDocument loop) {
        Object raw = loop.getEngineParams() == null
                ? null
                : loop.getEngineParams().get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return raw == null ? "" : raw.toString();
    }

    private static List<MaximegalonMessage> messagesOf(MaximegalonDocument item) {
        return item.getMessages() == null ? List.of() : item.getMessages();
    }

    private static Instant createdAtOf(List<MaximegalonMessage> messages, MaximegalonDocument item) {
        MaximegalonMessage last = messages.get(messages.size() - 1);
        return last.getCreatedAt() == null ? item.getCreatedAt() : last.getCreatedAt();
    }

    private static String authorOf(MaximegalonMessage message) {
        return message.getAuthorUserId() == null ? "" : message.getAuthorUserId();
    }

    private static String preview(@Nullable String body) {
        String flat = body == null ? "" : body.strip();
        int nl = flat.indexOf('\n');
        String line = nl < 0 ? flat : flat.substring(0, nl);
        return line.length() <= PREVIEW_LIMIT ? line : line.substring(0, PREVIEW_LIMIT) + "…";
    }
}
