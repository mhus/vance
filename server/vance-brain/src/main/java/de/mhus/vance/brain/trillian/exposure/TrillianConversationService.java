package de.mhus.vance.brain.trillian.exposure;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 * <p>Every refusal is a {@link ToolException} with a stable, greppable
 * reason — the shells surface it as MCP {@code isError} text, so an external
 * agent sees "not allowed" as protocol content, not as an HTTP failure
 * (same convention as the project-scoped MCP surface).
 *
 * <p><b>Honest answers at the door.</b> The entry refuses up front what
 * would otherwise rest unheard (§6.7): an inactive Trillian (no live loop)
 * and a reached conversation limit are refused before the thread exists —
 * a thread that lies around with no head is the mechanics' fallback, not
 * the entry's promise.
 *
 * <p><b>Reading outlives writing.</b> An archived conversation stays
 * readable (§3.11) — the counterpart can collect the closing contribution
 * — but every further contribution is refused with a stable reason; a new
 * concern is a new conversation.
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
    private static final long POLL_INTERVAL_MS = 750;

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

    private final MaximegalonService threads;
    private final TrillianExposureService exposureService;
    private final TrillianOwnSessions ownSessions;
    private final TrillianNatureRegistry natureRegistry;
    private final TrillianHeadService headService;

    /** The trillian-side proof that the caller passed the gate. */
    public record Opened(String conversationId) {}

    /**
     * Opens a conversation. The contribution becomes the thread's body — the
     * shape the thread listener treats as an opener, so the head wakes.
     */
    public Opened open(String tenantId, String caller, String exposedName, @Nullable String title, String text) {
        TrillianExposureService.Exposed exposed = exposureService
                .findByExposedName(tenantId, exposedName)
                .orElseThrow(() -> new ToolException("agent not exposed: '" + exposedName + "'"));
        if (!exposed.config().doors().mcp()) {
            throw new ToolException("door closed: '" + exposedName + "' does not open the agents door");
        }
        if (!exposed.config().allows(caller)) {
            throw new ToolException(
                    "not allowed: '" + caller + "' is not an allowed counterpart of '" + exposedName + "'");
        }
        ThinkProcessDocument loop = ownSessions
                .liveLoopOf(tenantId, exposed.account())
                .orElseThrow(() -> new ToolException("agent inactive: '" + exposedName + "' has no live loop"));
        if (!headService.hasHeadCapacity(loop, natureRegistry.resolve(readNature(loop)))) {
            throw new ToolException("conversation limit reached: '" + exposedName
                    + "' already leads the maximum number of conversations");
        }

        MaximegalonDocument created = threads.create(MaximegalonDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(caller)
                .assignedToUserId(exposed.account())
                .type(MaximegalonType.OUTPUT_TEXT)
                .criticality(Criticality.NORMAL)
                .status(MaximegalonStatus.PENDING)
                .requiresAction(false)
                .title(titleBlank(title) ? deriveTitle(text) : title.trim())
                .body(text)
                .tags(new ArrayList<>(List.of("discussion")))
                .build());
        return new Opened(created.getId());
    }

    /** Contributes to a conversation the caller opened. The head wakes on the message event. */
    public Contribution send(String tenantId, String caller, String conversationId, String text) {
        conversationOf(tenantId, caller, conversationId, /* forWrite */ true);
        Optional<MaximegalonDocument> updated = threads.postMessage(tenantId, conversationId, caller, text, null);
        if (updated.isEmpty()) {
            throw new ToolException("conversation not found: " + conversationId);
        }
        List<MaximegalonMessage> updatedMessages = messagesOf(updated.get());
        MaximegalonMessage sent = updatedMessages.get(updatedMessages.size() - 1);
        // The caller's own message never waits for itself; what follows it is the head's answer.
        return Contribution.of(sent);
    }

    /**
     * Reads the conversation, optionally long-polling for something new: the
     * counterpart has no push channel, waiting is the protocol (§3.7).
     * Positional cursor by message id; an unknown cursor reads from the start.
     */
    public Reply read(
            String tenantId, String caller, String conversationId, @Nullable String afterMessageId, long waitMs) {
        MaximegalonDocument item = conversationOf(tenantId, caller, conversationId, /* forWrite */ false);
        int cursor = positionAfter(messagesOf(item), afterMessageId);
        long deadline = System.currentTimeMillis() + clampWait(waitMs);
        while (true) {
            List<Contribution> fresh =
                    messagesOf(item).stream().skip(cursor).map(Contribution::of).toList();
            if (!fresh.isEmpty()) {
                return new Reply(conversationId, fresh, true);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return Reply.empty(conversationId);
            }
            sleep(Math.min(POLL_INTERVAL_MS, remaining));
            Optional<MaximegalonDocument> reloaded = threads.findById(tenantId, conversationId);
            if (reloaded.isEmpty()) {
                return Reply.empty(conversationId);
            }
            item = reloaded.get();
        }
    }

    // ──────────────────── gate helpers ────────────────────

    /**
     * The conversation as this door sees it: exists, is a conversation, and
     * belongs to the caller — anything else is "not found", not an
     * explanation, because the thread's very existence is not this caller's
     * to learn here.
     */
    /**
     * The conversation as this door sees it: exists, is a conversation, and
     * belongs to the caller — anything else is "not found", not an
     * explanation, because the thread's very existence is not this caller's
     * to learn here. An archived conversation answers reads and refuses
     * writes: reading outlives writing (§3.11).
     */
    private MaximegalonDocument conversationOf(
            String tenantId, String caller, String conversationId, boolean forWrite) {
        MaximegalonDocument item = threads.findById(tenantId, conversationId)
                .orElseThrow(() -> new ToolException("conversation not found: " + conversationId));
        String account = item.getAssignedToUserId();
        if (account == null
                || !account.startsWith(TrillianSessionBootstrapper.ACCOUNT_PREFIX)
                // An ask never was a conversation, an effect is not one either —
                // but an archived conversation still is, because reading
                // outlives writing (the status is handled below).
                || item.isRequiresAction()
                || item.getEffectType() != null) {
            throw new ToolException("conversation not found: " + conversationId);
        }
        if (!caller.equals(item.getOriginatorUserId())) {
            throw new ToolException("conversation is not yours: " + conversationId);
        }
        if (forWrite
                && (item.getStatus() == MaximegalonStatus.ARCHIVED
                        || item.getStatus() == MaximegalonStatus.DISMISSED)) {
            throw new ToolException("conversation archived: it is read-only now, a new concern is a new conversation");
        }
        return item;
    }

    private static List<MaximegalonMessage> messagesOf(MaximegalonDocument item) {
        return item.getMessages() == null ? List.of() : item.getMessages();
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

    private static String readNature(ThinkProcessDocument loop) {
        return TrillianSessionBootstrapper.readNature(loop);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while waiting for a reply", e);
        }
    }
}
