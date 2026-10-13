package de.mhus.vance.brain.trillian.exposure;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
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
 * agent sees "not allowed" as protocol content, not as an HTTP failure
 * (same convention as the project-scoped MCP surface).
 *
 * <p><b>Honest answers at the door.</b> The entry refuses up front what
 * would otherwise rest unheard (§6.7): an inactive Trillian (no live loop),
 * a single-headed one (it leads no conversations) and a reached conversation
 * limit are refused before the thread exists — a thread that lies around
 * with no head is the mechanics' fallback, not the entry's promise.
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
 * at once instead of parking yet another request thread.
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

    /** How the shell sees a message. */
    public record Contribution(
            String id, String author, String body, @Nullable Instant at) {

        static Contribution of(MaximegalonMessage message) {
            return new Contribution(
                    message.getId(), message.getAuthorUserId(), message.getBody(), message.getCreatedAt());
        }
    }

    /** The long-poll answer: what arrived, and whether anything arrived at all. */
    public record Reply(String conversationId, List<Contribution> messages, boolean arrived) {

        static Reply empty(String conversationId) {
            return new Reply(conversationId, List.of(), false);
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

    /** Waiting reads per caller — the {@link #MAX_WAITING_PER_CALLER} bound. */
    private final ConcurrentHashMap<String, Integer> waitingPerCaller = new ConcurrentHashMap<>();

    /**
     * Opens a conversation. The contribution becomes the thread's body — the
     * shape the thread listener treats as an opener, so the head wakes.
     */
    public Opened open(String tenantId, String caller, String exposedName, @Nullable String title, String text) {
        TrillianExposureService.Exposed exposed = exposureService
                .findByExposedName(tenantId, exposedName)
                .orElseThrow(() -> new ToolException("agent not exposed: '" + exposedName + "'"));
        passGate(exposed, caller);
        ThinkProcessDocument loop = ownSessions
                .liveLoopOf(tenantId, exposed.account())
                .orElseThrow(() -> new ToolException("agent inactive: '" + exposedName + "' has no live loop"));
        TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
        if (!nature.multiHead()) {
            throw new ToolException(
                    "agent not conversational: '" + exposedName + "' leads no conversations through this door");
        }
        if (!headService.hasHeadCapacity(loop, nature)) {
            throw new ToolException("conversation limit reached: '" + exposedName
                    + "' already leads the maximum number of conversations");
        }

        MaximegalonDocument created = threads.create(MaximegalonService.discussion(
                        tenantId, caller, exposed.account(), titleBlank(title) ? deriveTitle(text) : title.trim(), text)
                .build());
        return new Opened(created.getId());
    }

    /** Contributes to a conversation the caller opened. The head wakes on the message event. */
    public Contribution send(String tenantId, String caller, String conversationId, String text) {
        conversationOf(tenantId, caller, conversationId, /* forWrite */ true);
        Optional<MaximegalonDocument> updated;
        try {
            updated = threads.postMessage(tenantId, conversationId, caller, text, null);
        } catch (MaximegalonRuleException e) {
            if (MaximegalonRuleException.MESSAGE_LIMIT_REACHED.equals(e.getReason())) {
                throw new ToolException(
                        "conversation full: it reached its message limit, a new concern is a new conversation", e);
            }
            throw new ToolException("contribution refused: " + e.getMessage(), e);
        }
        if (updated.isEmpty()) {
            throw new ToolException("conversation not found: " + conversationId);
        }
        // The caller's own message is the cursor — searched by author, not
        // taken as the last one: the head may answer an earlier contribution
        // between the write and the re-read, and taking its answer as the
        // cursor would skip it.
        MaximegalonMessage sent = lastBy(messagesOf(updated.get()), caller)
                .orElseThrow(() -> new ToolException("conversation not found: " + conversationId));
        return Contribution.of(sent);
    }

    /**
     * Reads the conversation, optionally waiting for something new: the
     * counterpart has no push channel, waiting is the protocol (§3.7).
     * Positional cursor by message id; an unknown cursor reads from the start.
     */
    public Reply read(
            String tenantId, String caller, String conversationId, @Nullable String afterMessageId, long waitMs) {
        MaximegalonDocument item = conversationOf(tenantId, caller, conversationId, /* forWrite */ false);
        int cursor = positionAfter(messagesOf(item), afterMessageId);
        List<Contribution> fresh = freshOf(item, cursor);
        if (!fresh.isEmpty()) {
            return new Reply(conversationId, fresh, true);
        }
        long wait = clampWait(waitMs);
        if (wait == 0 || !acquireWait(caller)) {
            return Reply.empty(conversationId);
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
                        return Reply.empty(conversationId);
                    }
                    fresh = freshOf(reloaded.get(), cursor);
                    if (!fresh.isEmpty()) {
                        return new Reply(conversationId, fresh, true);
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return Reply.empty(conversationId);
                    }
                    await(wake, Math.min(FALLBACK_POLL_MS, remaining));
                } finally {
                    disarm(conversationId, wake);
                }
            }
        } finally {
            releaseWait(caller);
        }
    }

    /** Wakes the reads waiting on the thread that just got a message. */
    @EventListener
    public void onThreadMessage(MaximegalonMessageEvent event) {
        String id = event.item().getId();
        if (id == null) return;
        Set<CompletableFuture<Void>> waiting = waiters.get(id);
        if (waiting != null) {
            waiting.forEach(future -> future.complete(null));
        }
    }

    // ──────────────────── gate helpers ────────────────────

    /** Door open and caller allowed — the per-call part of the gate. */
    private static void passGate(TrillianExposureService.Exposed exposed, String caller) {
        String name = exposed.config().exposedName();
        if (!exposed.config().doors().mcp()) {
            throw new ToolException("door closed: '" + name + "' does not open the agents door");
        }
        if (!exposed.config().allows(caller)) {
            throw new ToolException("not allowed: '" + caller + "' is not an allowed counterpart of '" + name + "'");
        }
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
    private MaximegalonDocument conversationOf(
            String tenantId, String caller, String conversationId, boolean forWrite) {
        MaximegalonDocument item = threads.findById(tenantId, conversationId)
                .orElseThrow(() -> new ToolException("conversation not found: " + conversationId));
        String account = item.getAssignedToUserId();
        if (account == null
                || !account.startsWith(TrillianSessionBootstrapper.ACCOUNT_PREFIX)
                // The shape, not the status: an archived conversation still is one.
                || !TrillianHeadService.isConversationThread(item)) {
            throw new ToolException("conversation not found: " + conversationId);
        }
        if (!caller.equals(item.getOriginatorUserId())) {
            throw new ToolException("conversation is not yours: " + conversationId);
        }
        TrillianExposureService.Exposed exposed = exposureService
                .findByAccount(tenantId, account)
                .orElseThrow(() -> new ToolException(
                        "agent not exposed: the agent of this conversation is no" + " longer exposed"));
        passGate(exposed, caller);
        if (forWrite
                && (item.getStatus() == MaximegalonStatus.ARCHIVED
                        || item.getStatus() == MaximegalonStatus.DISMISSED)) {
            throw new ToolException("conversation archived: it is read-only now, a new concern is a new conversation");
        }
        return item;
    }

    // ──────────────────── waiting ────────────────────

    private boolean acquireWait(String caller) {
        boolean[] acquired = {false};
        waitingPerCaller.compute(caller, (key, count) -> {
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

    private void releaseWait(String caller) {
        waitingPerCaller.computeIfPresent(caller, (key, count) -> count <= 1 ? null : count - 1);
    }

    /** Waiting reads a caller holds right now — test seam for the bound. */
    int waitingReadsOf(String caller) {
        return waitingPerCaller.getOrDefault(caller, 0);
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

    private static List<Contribution> freshOf(MaximegalonDocument item, int cursor) {
        return messagesOf(item).stream().skip(cursor).map(Contribution::of).toList();
    }

    private static Optional<MaximegalonMessage> lastBy(List<MaximegalonMessage> messages, String author) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (author.equals(messages.get(i).getAuthorUserId())) {
                return Optional.of(messages.get(i));
            }
        }
        return Optional.empty();
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
