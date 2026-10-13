package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;

/**
 * The single-headed side of the Nature hierarchy: one loop carries
 * everything this Trillian does — its tasks, its self-checks and, where
 * the Nature has an inbox at all, every thread addressed to its account.
 *
 * <p><b>Why a class of its own and not just {@link TrillianNatureBase}.</b>
 * The base holds what every Trillian does, whichever side it is on. How
 * many heads a Trillian has is a structural decision that the multi-head
 * side answers differently (one conversation process per thread, see
 * {@code planning/trillian-a2a.md} §3.3). Putting the single-head answer
 * into the base would make it the silent default every Nature inherits;
 * as its own branch it is a choice a Nature makes by what it extends.
 *
 * <p>{@link #multiHead()} is {@code final} for the same reason: a concrete
 * Nature that wants the other side has to move in the hierarchy, not
 * override a flag.
 */
public abstract class TrillianNatureSingleHead extends TrillianNatureBase {

    protected TrillianNatureSingleHead(ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public final boolean multiHead() {
        return false;
    }
}
