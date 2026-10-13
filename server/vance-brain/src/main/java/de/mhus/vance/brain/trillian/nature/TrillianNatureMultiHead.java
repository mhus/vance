package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;

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
 * <p><b>Heads are a Frankie fork, configured per Nature.</b> A head runs
 * the {@code trillian-head} engine on the Nature's {@link #headRecipe()};
 * the engine owns the conversation surface and doctrine, the recipe pins
 * model and Nature, and {@link #headSystemAddendum} adds who is talking.
 * The machinery — contribution event, listener, head lifecycle, sweep —
 * lives in {@code TrillianHeadService} and its
 * listeners, not in the Nature; a concrete Nature on this side decides
 * persona and defaults, not mechanics. See
 * {@code planning/trillian-exposure.md} §3.2/§3.3.
 *
 * <p>{@link #multiHead()} is {@code final} for the same reason as on the
 * single-headed side: a Nature picks its side by what it extends, not by
 * overriding a flag.
 */
public abstract class TrillianNatureMultiHead extends TrillianNatureBase {

    protected TrillianNatureMultiHead(ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public final boolean multiHead() {
        return true;
    }

    /**
     * The head's system addendum: the shared persona (the loop's
     * attributes, read through the head's peer link, exactly as Control
     * renders them), then the Nature's {@link #headPromptAddendum}.
     * {@code final} so every multi-headed Nature composes it the same way;
     * the human's Control keeps {@link #controlPromptAddendum} and never
     * sees head text.
     */
    @Override
    public final String headSystemAddendum(ThinkProcessDocument head) {
        String persona = personaOf(head);
        String own = headPromptAddendum(head);
        if (own == null || own.isBlank()) {
            return persona;
        }
        return persona.isBlank() ? own : persona + "\n\n" + own;
    }
}
