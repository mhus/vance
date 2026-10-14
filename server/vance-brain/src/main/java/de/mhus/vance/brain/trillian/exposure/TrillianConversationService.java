package de.mhus.vance.brain.trillian.exposure;

import de.mhus.vance.api.inbox.InboxMessagePostRequest;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonArchivedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonMessageEvent;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The protocol entry of the exposure door — the one place that opens
 * conversations with an exposed Trillian, carries contributions and waits
 * ({@code planning/trillian-exposure.md} §3.5/§3.8). Every shell (the MCP
 * door today, A2A later) goes through here; the gates live in the entry,
 * never in the shell.
 *
 * <p><b>The gate replaces R5, it does not relax it.</b> Nobody writes into
 * another account's inbox through here: the entry checks the caller against
 * the exposure configuration ({@code allowedCounterparts}) and then creates
 * the thread in its own name, the same exception-free path a human with
 * WRITE would take. {@code Resource.InboxItem} and R5 stay untouched.
 *
 * <p><b>The gate holds on every call.</b> Exposed, door open, caller
 * allowed — checked when a conversation opens and again on every send and
 * read of a running one, so a revoked counterpart or a closed door takes
 * effect immediately, not only for the next conversation.
 *
 * <p>Every refusal is a {@link ToolException} with a stable, greppable
 * reason — the shells surface it as MCP {@code isError} text, so an external
 * agent sees a refusal as protocol content, not as an HTTP failure
 * (same convention as the project-scoped MCP surface).
 *
 * <p><b>Refusals do not enumerate.</b> A caller the gate refuses learns
 * nothing it could not learn from {@code agent_list}: an agent that is not
 * exposed, whose door is closed or that does not allow this caller all
 * answer {@code agent not exposed}; a conversation that does not exist,
 * is not a conversation of this door or belongs to someone else all answer
 * {@code conversation not found}. The distinction is in the log.
 *
 * <p><b>Honest answers at the door.</b> The entry refuses up front what
 * would otherwise rest unheard (§6.7): an inactive Trillian (no live loop)
 * and a reached conversation limit are refused before the thread exists — a
 * thread that lies around with no head is the mechanics' fallback, not the
 * entry's promise.
 *
 * <p><b>Bounds.</b> A contribution is capped like every inbox message
 * ({@link InboxMessagePostRequest#MAX_BODY_CHARS}), a title like a
 * discussion title ({@link #MAX_TITLE_CHARS}) — the door is the cheapest way
 * into a model's context and must not be the largest. One caller holds at
 * most as many open conversations with one Trillian as the Trillian has
 * heads (multi-headed), or {@link #SINGLE_HEAD_CONVERSATION_LIMIT}
 * (single-headed, whose one loop answers them all). The count and the create
 * are serialised per caller and Trillian on this pod, so a burst of parallel
 * opens cannot slip past the count; across pods the bound can be exceeded by
 * at most one conversation per pod.
 *
 * <p><b>Reading outlives writing.</b> An archived conversation stays
 * readable (§3.11) — the counterpart can collect the closing contribution
 * — but every further contribution is refused with a stable reason; a new
 * concern is a new conversation.
 *
 * <p><b>Waiting.</b> The counterpart has no push channel, so a read waits
 * (§3.7). A waiting read is woken by the thread's message event on this pod
 * — the common case, an answer from a head on the same pod, arrives at
 * once — and re-reads the thread every {@link #FALLBACK_POLL_MS} for
 * answers written on another pod. One caller holds at most
 * {@link #MAX_WAITING_PER_CALLER} waiting reads; beyond that a read answers
 * at once instead of parking yet another request thread. A conversation that
 * ends while a read waits ends the wait: nothing will arrive any more, and
 * the reply says {@code closed}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianConversationService {

    /**
     * Long-poll defaults: sized below the gateway and MCP-client timeouts.
     * Public because the MCP shell (another package) reads them.
     */
    public static final long DEFAULT_WAIT_MS = 20_000;

    public static final long MAX_WAIT_MS = 55_000;

    /** Re-read interval of a waiting read — the bound on an answer written on another pod. */
    static final long FALLBACK_POLL_MS = 2_000;

    /** Waiting reads one caller may hold at the same time. */
    static final int MAX_WAITING_PER_CALLER = 4;

    /** Title cap — the same as a discussion opened through the inbox REST surface. */
    public static final int MAX_TITLE_CHARS = 200;

    /**
     * Open conversations one caller may hold with one single-headed
     * Trillian. There is no head limit to borrow from — the one loop answers
     * every thread — so the bound is a fixed one, sized like a Nature's
     * default head limit.
     */
    static final int SINGLE_HEAD_CONVERSATION_LIMIT = 5;

    /** Lock stripes serialising count-then-create per caller and Trillian. */
    private static final int OPEN_LOCK_STRIPES = 64;

    /**
     * How the shell sees a message. The Trillian's own contributions carry
     * its exposed name, not its internal service account — the account is
     * the server's business, the exposed name is what the counterpart talks to.
     */
    public record Contribution(
            String id, String author, String body, @Nullable Instant at) {

        static Contribution of(MaximegalonMessage message, TrillianExposureService.Exposed exposed) {
            String author = exposed.account().equals(message.getAuthorUserId())
                    ? exposed.config().exposedName()
                    : message.getAuthorUserId();
            return new Contribution(message.getId(), author, message.getBody(), message.getCreatedAt());
        }
    }

    /**
     * The long-poll answer: what arrived, whether anything arrived at all,
     * and whether the conversation has ended — an ended conversation answers
     * reads but takes no contribution, and nothing new will arrive.
     */
    public record Reply(String conversationId, List<Contribution> messages, boolean arrived, boolean closed) {

        static Reply of(String conversationId, List<Contribution> messages, boolean closed) {
            return new Reply(conversationId, messages, !messages.isEmpty(), closed);
        }
    }

    /** The trillian-side proof that the caller passed the gate. */
    public record Opened(String conversationId) {}

    private final MaximegalonService threads;
    private final TrillianExposureService exposureService;
    private final TrillianOwnSessions ownSessions;
    private final TrillianNatureRegistry natureRegistry;
    private final TrillianHeadService headService;

    /** Waiting reads per conversation id, woken by the thread's message event. */
    private final ConcurrentHashMap<String, Set<CompletableFuture<Void>>> waiters = new ConcurrentHashMap<>();

    /** Waiting reads per {@code tenant\0caller} — the {@link #MAX_WAITING_PER_CALLER} bound. */
    private final ConcurrentHashMap<String, Integer> waitingPerCaller = new ConcurrentHashMap<>();

    /** Fixed stripes, so the lock set does not grow with the callers. */
    private final Object[] openLocks = newLocks();

    /**
     * Opens a conversation. The contribution becomes the thread's body — the
     * shape the thread listener treats as an opener, so the head wakes.
     */
    public Opened open(String tenantId, String caller, String exposedName, @Nullable String title, String text) {
        requireText(text);
        if (!titleBlank(title) && title.trim().length() > MAX_TITLE_CHARS) {
            throw new ToolException("title too long: at most " + MAX_TITLE_CHARS + " characters");
        }
        TrillianExposureService.Exposed exposed =
                exposureService.findByExposedName(tenantId, exposedName).orElseThrow(() -> notExposed(exposedName));
        passGate(exposed, caller);
        ThinkProcessDocument loop = ownSessions
                .liveLoopOf(tenantId, exposed.account())
                .orElseThrow(() -> new ToolException("agent inactive: '" + exposedName + "' has no live loop"));
        // A single-headed Trillian answers its threads on its own loop
        // (inbox_unread) — no heads, no head limit. A multi-headed one gets
        // a head per conversation, so the limit is the honest "full".
        TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
        if (nature.multiHead() && !headService.hasHeadCapacity(loop, nature)) {
            throw new ToolException("conversation limit reached: '" + exposedName
                    + "' already leads the maximum number of conversations");
        }
        int perCaller = conversationLimitOf(tenantId, exposed.account(), nature);
        synchronized (openLockOf(tenantId, caller, exposed.account())) {
            if (threads.countOpenDiscussions(tenantId, caller, exposed.account()) >= perCaller) {
                throw new ToolException("conversation limit reached: you already hold " + perCaller
                        + " open conversations with '" + exposedName + "' — close one first (agent_close)");
            }
            MaximegalonDocument created = threads.create(MaximegalonService.discussion(
                            tenantId,
                            caller,
                            exposed.account(),
                            titleBlank(title) ? deriveTitle(text) : title.trim(),
                            text)
                    .build());
            return new Opened(created.getId());
        }
    }

    /** Contributes to a conversation the caller opened. The head wakes on the message event. */
    public Contribution send(String tenantId, String caller, String conversationId, String text) {
        requireText(text);
        Conversation conversation = conversationOf(tenantId, caller, conversationId, /* forWrite */ true);
        Optional<MaximegalonService.Posted> posted;
        try {
            posted = threads.post(tenantId, conversationId, caller, text, null);
        } catch (MaximegalonRuleException e) {
            if (MaximegalonRuleException.MESSAGE_LIMIT_REACHED.equals(e.getReason())) {
                throw new ToolException(
                        "conversation full: it reached its message limit, a new concern is a new conversation", e);
            }
            throw new ToolException("contribution refused: " + e.getMessage(), e);
        }
        if (posted.isEmpty()) {
            throw notFound(conversationId);
        }
        // The message this call wrote is the cursor — not the last one, and
        // not the caller's last one: the head may answer an earlier
        // contribution, or a parallel send of the same caller may land,
        // between the write and the re-read; either would skip an answer.
        return Contribution.of(posted.get().message(), conversation.exposed());
    }

    /**
     * Ends a conversation the caller opened — the explicit counterpart of a
     * goodbye in words, for a counterpart (usually an agent) that is done and
     * would otherwise just go quiet. The thread is archived as the caller; the
     * head closes with it on the next sweep round. Already archived is fine:
     * ending twice is not an error.
     */
    public void close(String tenantId, String caller, String conversationId) {
        MaximegalonDocument item = conversationOf(tenantId, caller, conversationId, /* forWrite */ false)
                .item();
        if (isEnded(item)) {
            return;
        }
        threads.archive(tenantId, conversationId, caller);
    }

    /**
     * Reads the conversation, optionally waiting for something new: the
     * counterpart has no push channel, waiting is the protocol (§3.7).
     * Positional cursor by message id; an unknown cursor reads from the start.
     */
    public Reply read(
            String tenantId, String caller, String conversationId, @Nullable String afterMessageId, long waitMs) {
        Conversation conversation = conversationOf(tenantId, caller, conversationId, /* forWrite */ false);
        TrillianExposureService.Exposed exposed = conversation.exposed();
        MaximegalonDocument item = conversation.item();
        int cursor = positionAfter(messagesOf(item), afterMessageId);
        List<Contribution> fresh = freshOf(item, cursor, exposed);
        // Something new, or nothing will come any more: answer at once.
        if (!fresh.isEmpty() || isEnded(item)) {
            return Reply.of(conversationId, fresh, isEnded(item));
        }
        long wait = clampWait(waitMs);
        if (wait == 0 || !acquireWait(tenantId, caller)) {
            return Reply.of(conversationId, List.of(), false);
        }
        try {
            long deadline = System.currentTimeMillis() + wait;
            while (true) {
                // Armed before the re-read: a message that lands in between
                // completes this future, so no wake-up is lost.
                CompletableFuture<Void> wake = arm(conversationId);
                try {
                    Optional<MaximegalonDocument> reloaded = threads.findById(tenantId, conversationId);
                    if (reloaded.isEmpty()) {
                        return Reply.of(conversationId, List.of(), true);
                    }
                    fresh = freshOf(reloaded.get(), cursor, exposed);
                    boolean ended = isEnded(reloaded.get());
                    if (!fresh.isEmpty() || ended) {
                        return Reply.of(conversationId, fresh, ended);
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return Reply.of(conversationId, List.of(), false);
                    }
                    await(wake, Math.min(FALLBACK_POLL_MS, remaining));
                } finally {
                    disarm(conversationId, wake);
                }
            }
        } finally {
            releaseWait(tenantId, caller);
        }
    }

    /** Wakes the reads waiting on the thread that just got a message. */
    @EventListener
    public void onThreadMessage(MaximegalonMessageEvent event) {
        wake(event.item().getId());
    }

    /**
     * Wakes the reads waiting on a thread that was just archived — an end
     * without a last word (idle sweep, {@code agent_close}) carries no
     * message event, and the waiting read must learn it is over.
     */
    @EventListener
    public void onThreadArchived(MaximegalonArchivedEvent event) {
        wake(event.item().getId());
    }

    private void wake(@Nullable String conversationId) {
        if (conversationId == null) return;
        Set<CompletableFuture<Void>> waiting = waiters.get(conversationId);
        if (waiting != null) {
            waiting.forEach(future -> future.complete(null));
        }
    }

    // ──────────────────── gate helpers ────────────────────

    /** A conversation of this door: the thread and the exposure that admits it. */
    private record Conversation(MaximegalonDocument item, TrillianExposureService.Exposed exposed) {}

    /**
     * Door open and caller allowed — the per-call part of the gate. Refused
     * as "not exposed": to this caller, an agent that will not talk to them
     * through this door is not there (the same view {@code agent_list} gives).
     */
    private static void passGate(TrillianExposureService.Exposed exposed, String caller) {
        String name = exposed.config().exposedName();
        if (!exposed.config().doors().mcp()) {
            log.debug("agents door: '{}' refused — '{}' does not open the agents door", caller, name);
            throw notExposed(name);
        }
        if (!exposed.config().allows(caller)) {
            log.debug("agents door: '{}' refused — not an allowed counterpart of '{}'", caller, name);
            throw notExposed(name);
        }
    }

    private static ToolException notExposed(String exposedName) {
        return new ToolException("agent not exposed: '" + exposedName + "'");
    }

    private static ToolException notFound(String conversationId) {
        return new ToolException("conversation not found: " + conversationId);
    }

    private static void requireText(String text) {
        if (text.length() > InboxMessagePostRequest.MAX_BODY_CHARS) {
            throw new ToolException(
                    "contribution too long: at most " + InboxMessagePostRequest.MAX_BODY_CHARS + " characters");
        }
    }

    /**
     * Open conversations one caller may hold with this Trillian: its head
     * limit when multi-headed (the config may only lower the Nature's), the
     * fixed single-head bound otherwise.
     */
    private int conversationLimitOf(String tenantId, String account, TrillianNature nature) {
        if (!nature.multiHead()) {
            return SINGLE_HEAD_CONVERSATION_LIMIT;
        }
        int configured = exposureService.headLimitOf(tenantId, account);
        return configured > 0 ? Math.min(configured, nature.headLimit()) : nature.headLimit();
    }

    private Object openLockOf(String tenantId, String caller, String account) {
        int hash = (tenantId + '\0' + caller + '\0' + account).hashCode();
        return openLocks[Math.floorMod(hash, OPEN_LOCK_STRIPES)];
    }

    private static Object[] newLocks() {
        Object[] locks = new Object[OPEN_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static boolean isEnded(MaximegalonDocument item) {
        return item.getStatus() == MaximegalonStatus.ARCHIVED || item.getStatus() == MaximegalonStatus.DISMISSED;
    }

    /**
     * The conversation as this door sees it: exists, is a conversation with
     * a Trillian, and belongs to the caller — anything else is "not found",
     * not an explanation, because the thread's very existence is not this
     * caller's to learn here. Then the gate, as at the opening: a Trillian
     * that is no longer exposed, a closed door or a revoked counterpart end
     * the conversation for this caller. An archived conversation answers
     * reads and refuses writes: reading outlives writing (§3.11).
     */
    private Conversation conversationOf(String tenantId, String caller, String conversationId, boolean forWrite) {
        MaximegalonDocument item =
                threads.findById(tenantId, conversationId).orElseThrow(() -> notFound(conversationId));
        String account = item.getAssignedToUserId();
        if (account == null
                || !account.startsWith(TrillianSessionBootstrapper.ACCOUNT_PREFIX)
                // The shape, not the status: an archived conversation still is one.
                || !TrillianHeadService.isConversationThread(item)
                || !caller.equals(item.getOriginatorUserId())) {
            throw notFound(conversationId);
        }
        TrillianExposureService.Exposed exposed = exposureService
                .findByAccount(tenantId, account)
                .orElseThrow(() ->
                        new ToolException("agent not exposed: the agent of this conversation is no longer exposed"));
        passGate(exposed, caller);
        if (forWrite && isEnded(item)) {
            throw new ToolException("conversation archived: it is read-only now, a new concern is a new conversation");
        }
        return new Conversation(item, exposed);
    }

    // ──────────────────── waiting ────────────────────

    private boolean acquireWait(String tenantId, String caller) {
        boolean[] acquired = {false};
        waitingPerCaller.compute(waitKey(tenantId, caller), (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_WAITING_PER_CALLER) {
                return count;
            }
            acquired[0] = true;
            return current + 1;
        });
        if (!acquired[0]) {
            log.debug(
                    "agents door: '{}' already holds {} waiting reads — answering at once",
                    caller,
                    MAX_WAITING_PER_CALLER);
        }
        return acquired[0];
    }

    private void releaseWait(String tenantId, String caller) {
        waitingPerCaller.computeIfPresent(waitKey(tenantId, caller), (key, count) -> count <= 1 ? null : count - 1);
    }

    /** Waiting reads a caller holds right now — test seam for the bound. */
    int waitingReadsOf(String tenantId, String caller) {
        return waitingPerCaller.getOrDefault(waitKey(tenantId, caller), 0);
    }

    /** Usernames are unique per tenant only — the bound is per tenant and caller. */
    private static String waitKey(String tenantId, String caller) {
        return tenantId + '\0' + caller;
    }

    private CompletableFuture<Void> arm(String conversationId) {
        CompletableFuture<Void> wake = new CompletableFuture<>();
        waiters.compute(conversationId, (key, set) -> {
            Set<CompletableFuture<Void>> waiting = set == null ? ConcurrentHashMap.newKeySet() : set;
            waiting.add(wake);
            return waiting;
        });
        return wake;
    }

    private void disarm(String conversationId, CompletableFuture<Void> wake) {
        waiters.computeIfPresent(conversationId, (key, set) -> {
            set.remove(wake);
            return set.isEmpty() ? null : set;
        });
    }

    private static void await(CompletableFuture<Void> wake, long millis) {
        try {
            wake.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The fallback re-read is due — an answer may have come from another pod.
        } catch (ExecutionException e) {
            // Never completed exceptionally; the re-read decides.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while waiting for a reply", e);
        }
    }

    // ──────────────────── message helpers ────────────────────

    private static List<MaximegalonMessage> messagesOf(MaximegalonDocument item) {
        return item.getMessages() == null ? List.of() : item.getMessages();
    }

    private static List<Contribution> freshOf(
            MaximegalonDocument item, int cursor, TrillianExposureService.Exposed exposed) {
        return messagesOf(item).stream()
                .skip(cursor)
                .map(message -> Contribution.of(message, exposed))
                .toList();
    }

    private static int positionAfter(List<MaximegalonMessage> messages, @Nullable String afterMessageId) {
        if (afterMessageId == null || afterMessageId.isBlank()) return 0;
        for (int i = 0; i < messages.size(); i++) {
            if (afterMessageId.equals(messages.get(i).getId())) {
                return i + 1;
            }
        }
        return 0;
    }

    public static long clampWait(long waitMs) {
        return Math.max(0, Math.min(waitMs, MAX_WAIT_MS));
    }

    private static boolean titleBlank(@Nullable String title) {
        return title == null || title.isBlank();
    }

    private static String deriveTitle(String text) {
        String flat = text.strip();
        int nl = flat.indexOf('\n');
        String firstLine = nl >= 0 ? flat.substring(0, nl) : flat;
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "…" : firstLine;
    }
}
