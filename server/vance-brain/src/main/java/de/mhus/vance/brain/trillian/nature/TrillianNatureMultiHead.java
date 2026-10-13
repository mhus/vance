package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * The multi-headed side of the Nature hierarchy: one conversation process
 * ("head") per discussion thread addressed to this Trillian, while the
 * Trillian's own loop stays free of other people's conversations.
 *
 * <p><b>What that split buys.</b> A single loop carrying every conversation
 * would leak what counterpart A said into the context B is answered from,
 * let one prepared contribution colour answers and the Trillian's own work
 * (injection), serialise every conversation behind one turn lease, and
 * crowd out the actual work. One head per thread is the same shape as one
 * worker per task: identity, persona and rights stay with the Trillian
 * (heads run as the {@code _trillian-*} account in its hub), the context
 * belongs to the conversation, and the thread is the record.
 *
 * <p><b>Heads are recipes, not engine work.</b> A head runs the control
 * engine on the Nature's {@link #headRecipe()}; the recipe carries the
 * thread doctrine and cuts the toolset to the thread family plus the task
 * handover. The machinery — contribution event, listener, head lifecycle,
 * inactivity sweep — lives in {@code TrillianHeadService} and its
 * listeners, not in the Nature; a concrete Nature on this side decides
 * persona and defaults, not mechanics. See
 * {@code planning/trillian-exposure.md} §3.2/§3.3.
 *
 * <p>{@link #multiHead()} is {@code final} for the same reason as on the
 * single-headed side: a Nature picks its side by what it extends, not by
 * overriding a flag.
 */
public abstract class TrillianNatureMultiHead extends TrillianNatureBase {

    protected TrillianNatureMultiHead(de.mhus.vance.shared.thinkprocess.ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public final boolean multiHead() {
        return true;
    }

    /**
     * Head-aware prompt addendum dispatch: a head process runs the control
     * engine, so this is the one hook the engine calls for it — the shared
     * persona (the loop's attributes, read through the peer link) renders
     * as for any control process, and the Nature's
     * {@link #headPromptAddendum(ThinkProcessDocument)} lands next to it.
     *
     * <p>Without this dispatch a multi-headed Nature would have to decide in
     * {@code controlPromptAddendum} whether it is talking to the human's
     * control session or to a conversation head — a distinction every one of
     * them would rebuild, and get wrong in its own way. Here, once.
     */
    @Override
    public String controlPromptAddendum(ThinkProcessDocument process) {
        String persona = super.controlPromptAddendum(process);
        String head = headPromptAddendum(process);
        if (head == null || head.isBlank()) {
            return persona;
        }
        return persona == null || persona.isBlank() ? head : persona + "\n\n" + head;
    }
}
