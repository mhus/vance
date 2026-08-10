package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import java.util.Optional;

/**
 * SPI for inbox items whose answer must <em>do</em> something on the
 * server, rather than merely travel back to the asking process.
 *
 * <p><b>Why this exists.</b> The standard answer path is purely
 * process-directed: {@code InboxItemService.answer} flips the item and
 * publishes {@link InboxItemAnsweredEvent}, the brain routes a
 * {@code SteerMessage.InboxAnswer} to {@code originProcessId}, the engine
 * wakes and reads it. For anything an LLM must <em>not</em> be able to do
 * on its own — changing permissions, installing a kit, deleting a project
 * — that is not enough: an agent that can act after a "yes" can act
 * without one, which makes the approval decoration rather than control.
 *
 * <p>An effect closes that gap. The requesting side writes the intended
 * mutation down and asks; the mutation itself is held by the server and
 * executed here, triggered only by a human decision. What will happen is
 * fixed before the question is asked, and the asking side cannot influence
 * it afterwards.
 *
 * <p><b>Contract.</b>
 * <ul>
 *   <li>Implementations are Spring beans, keyed by {@link #effectType()}
 *       and resolved through {@link InboxEffectRegistry}.</li>
 *   <li>Exactly one of {@link #onApproved} / {@link #onRejected} runs, at
 *       most once per item — the dispatch rides the same single
 *       PENDING→ANSWERED transition that already guards against
 *       double-submit.</li>
 *   <li>Neither runs when the responder abstains
 *       ({@code INSUFFICIENT_INFO} / {@code UNDECIDABLE}) or when the item
 *       is dismissed. Abstention is not consent.</li>
 *   <li>Implementations run in a SYSTEM context and must re-check the
 *       responder's authority themselves — between request and decision
 *       hours may pass and rights may change.</li>
 *   <li>Implementations should be defensive about their own state: a
 *       throw leaves the item {@code ANSWERED} (the human decision is not
 *       discarded) and only records the failure on the item.</li>
 * </ul>
 *
 * <p>See {@code planning/permission-request-inbox.md} §12 Phase 1.
 */
public interface InboxEffect {

    /**
     * Registry key, matched against {@code InboxItemDocument.effectType}.
     * Stable string — it is persisted on items that may outlive a
     * release.
     */
    String effectType();

    /**
     * The responder said yes. Perform the held mutation.
     *
     * @param item   the item as persisted after the transition; carries
     *               {@code effectRef} identifying what was requested
     * @param answer the answer that triggered this, including
     *               {@code answeredBy}
     */
    void onApproved(InboxItemDocument item, AnswerPayload answer);

    /**
     * The responder said no. Typically: mark the held mutation rejected
     * so it cannot be picked up later. Must not perform it.
     */
    void onRejected(InboxItemDocument item, AnswerPayload answer);

    /**
     * Server-rendered facts about what approving would do, for the
     * deciding UI. Empty when the effect has nothing to show or its
     * subject is gone.
     *
     * <p>Implementations must take these from their own storage. Deriving
     * them from the item's body would hand the description back to
     * whoever wrote that text — which for an agent-raised request is the
     * one thing that must not happen.
     */
    default Optional<EffectDescription> describe(InboxItemDocument item) {
        return Optional.empty();
    }
}
