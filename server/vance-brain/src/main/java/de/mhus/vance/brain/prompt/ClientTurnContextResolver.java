package de.mhus.vance.brain.prompt;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.ActiveInboxContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import de.mhus.vance.brain.applications.ActiveAppPromptResolver;
import de.mhus.vance.brain.chat.CollabContextResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.tools.client.CortexBoundDocumentResolver;
import de.mhus.vance.brain.tools.client.CortexPromptResolver;
import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves everything the <em>client</em> told us about the current turn
 * into one structure, and applies it to a {@link PromptContextBuilder}.
 *
 * <p>These are the per-turn signals that describe <b>what the person is
 * looking at while they type</b>: voice mode, the app they have open, the
 * document bound to the chat and the selection inside it, the inbox
 * thread beside the chat, and who else is in the session. They ride in on
 * {@link SteerMessage.UserChatInput}, are never persisted on the process,
 * and are gone next turn.
 *
 * <p><b>Why this is a service and not four calls in each engine.</b> It
 * used to be exactly that: Arthur and Eddie carried the same twenty
 * lines, Ford carried the bound-document half of them, and Frankie
 * carried none — so a chat running on Frankie could not see which app the
 * reader had open. That failed the way a missing prompt input always
 * fails: not with an error, but with an agent confidently working in the
 * wrong folder. The set of signals grows (voice, then app, then
 * selection, then inbox), and every addition to a copied block is another
 * chance for one engine to be left behind. Now an engine either has the
 * whole client context or visibly does not call this.
 *
 * <p><b>A template that ignores a variable costs nothing.</b> Setting all
 * of them for every engine is deliberate: unset and {@code false} are
 * both falsy in Pebble's lenient mode, so an engine whose prompt has no
 * {@code {% if activeApp %}} block renders exactly as before — while a
 * recipe {@code promptPrefix} on that same engine can read the variable
 * the day someone needs it.
 *
 * @see PromptContextBuilder
 */
@Service
@RequiredArgsConstructor
public class ClientTurnContextResolver {

    private final ActiveAppPromptResolver activeAppPromptResolver;
    private final CortexPromptResolver cortexPromptResolver;
    private final CortexBoundDocumentResolver cortexBoundDocumentResolver;
    private final CortexTurnSelectionHolder cortexTurnSelectionHolder;
    private final CollabContextResolver collabContextResolver;

    /**
     * Scan one drain batch and resolve its client context.
     *
     * <p>{@code batch} is the whole drained inbox, not just the user
     * inputs — the scan picks the {@link SteerMessage.UserChatInput}
     * items out of it itself, and <b>the last one wins</b>. A batch may
     * hold several (two messages typed while the lane was busy), and the
     * newest is the one that describes where the reader is now.
     *
     * <p>A batch with no user input at all — an autonomous wake, a tool
     * result, a sibling {@code ProcessEvent} — yields the empty context:
     * nobody is looking at anything, so nothing is claimed. Note this is
     * <em>not</em> the same as carrying the previous turn's hints
     * forward; that would tell the model the reader is in a folder they
     * may have left minutes ago.
     *
     * <p><b>Side effect:</b> this also stashes the turn's selection in
     * {@link CortexTurnSelectionHolder} so the no-arg
     * {@code doc_get_selection()} can find it. That stash is per-turn
     * state rather than prompt content, but it is derived from the same
     * scan, and splitting it out only created a second thing for an
     * engine to forget. Idempotent — engines that rebuild the prompt
     * after compaction call this two or three times per turn with the
     * same batch.
     */
    public ClientTurnContext resolve(
            ThinkProcessDocument process, @Nullable List<SteerMessage> batch) {
        boolean voiceMode = false;
        String mentionedByDisplayName = null;
        ActiveAppContext activeApp = null;
        String boundDocumentId = null;
        BoundDocSelection boundDocSelection = null;
        ActiveInboxContext activeInbox = null;
        if (batch != null) {
            for (SteerMessage m : batch) {
                if (m instanceof SteerMessage.UserChatInput uci) {
                    voiceMode = uci.voiceMode();
                    mentionedByDisplayName = uci.fromUserDisplayName();
                    activeApp = uci.activeApp();
                    boundDocumentId = uci.boundDocumentId();
                    boundDocSelection = uci.boundDocSelection();
                    activeInbox = uci.activeInbox();
                }
            }
        }

        String appInstructions = activeAppPromptResolver.resolve(process, activeApp);
        // Strict mode: when the resolver couldn't produce inject text
        // (unknown app, SPI returned null, threw) drop the activeApp hint
        // too, so the {% if activeApp %} block falls away cleanly instead
        // of rendering a header with an empty body.
        if (appInstructions == null) activeApp = null;

        // "In Cortex" is live-checked against the client-tool registry
        // rather than read off the session: a persisted flag would linger
        // after the user navigates back to plain chat.
        boolean cortexMode = cortexPromptResolver.resolve(process.getSessionId()).active();
        // Path only — the model reads the content on demand via doc_read.
        String boundDocPath = cortexBoundDocumentResolver.resolvePath(
                boundDocumentId, process.getTenantId(), process.getProjectId());
        // Clear the stash when this turn carried no selection; the
        // selection is tied to the bound document, so one without the
        // other is not a reference a tool can resolve.
        cortexTurnSelectionHolder.set(process.getId(),
                (boundDocSelection == null || boundDocumentId == null) ? null
                        : new CortexTurnSelectionHolder.Selection(boundDocumentId,
                                boundDocSelection.getFrom(), boundDocSelection.getTo()));

        CollabContextResolver.CollabContext collab =
                collabContextResolver.resolve(process.getSessionId(), mentionedByDisplayName);

        return new ClientTurnContext(voiceMode, activeApp, appInstructions, activeInbox,
                boundDocPath, boundDocSelection, cortexMode,
                collab.active(), collab.participants(), collab.mentionedBy());
    }

    /**
     * One turn's worth of client context. Engines normally hand this
     * straight to {@link #applyTo(PromptContextBuilder)}; the accessors
     * exist for the engine that needs a single value for something other
     * than the prompt.
     */
    public record ClientTurnContext(
            boolean voiceMode,
            @Nullable ActiveAppContext activeApp,
            @Nullable String appInstructions,
            @Nullable ActiveInboxContext activeInbox,
            @Nullable String boundDocPath,
            @Nullable BoundDocSelection boundDocSelection,
            boolean cortexMode,
            boolean collabActive,
            List<String> participants,
            @Nullable String mentionedBy) {

        /**
         * The context of a turn no client described — an autonomous wake,
         * or a unit test that isn't exercising client context. Applying it
         * is a no-op in every template.
         */
        public static final ClientTurnContext EMPTY = new ClientTurnContext(
                false, null, null, null, null, null, false, false, List.of(), null);

        /** Apply every variable to {@code builder}, and return it for chaining. */
        public PromptContextBuilder applyTo(PromptContextBuilder builder) {
            return builder
                    .voiceMode(voiceMode)
                    .activeApp(activeApp)
                    .appInstructions(appInstructions)
                    .activeInbox(activeInbox)
                    .cortexMode(cortexMode)
                    .cortexBoundDoc(boundDocPath)
                    .cortexBoundDocSelection(boundDocSelection)
                    .collabActive(collabActive)
                    .participants(participants)
                    .mentionedBy(mentionedBy);
        }
    }
}
