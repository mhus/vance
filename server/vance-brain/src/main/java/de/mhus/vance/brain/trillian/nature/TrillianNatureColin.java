package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature {@code colin} — the first multi-headed Nature: a
 * conversational Trillian whose discussion threads are each answered by
 * their own conversation head.
 *
 * <p>Named after Colin, the small flying robot from Mostly Harmless —
 * delighted to be talked to, available to everyone, and not weighed down
 * by deep plans. That is the profile of this side of the head axis:
 * conversations are what it *is*, not work the loop gets crammed with. The
 * loop stays free of other people's texts — it only ever sees the
 * structured tasks a head hands over.
 *
 * <p>Behaviourally the baseline of the multi-headed side, in the same
 * sense void is the baseline of the single-headed one: no persistence, no
 * reflexion, no boredom initiative. What colin adds over a hypothetical
 * empty branch is its identity — a name of its own in
 * {@code callName()}, so a conversation reads "Colin is ready" rather than
 * a class — plus the multi-headed mechanics of
 * {@link TrillianNatureMultiHead} and its bundled recipe family
 * ({@code trillian-colin}, {@code trillian-user-colin},
 * {@code trillian-worker-colin}, {@code trillian-head-colin}).
 *
 * <p><b>The loop of a colin carries no thread tools and no inbox
 * findings</b> — {@code inbox_unread} is single-head behaviour (adam
 * §7.2a), and a thread that a head answers must never also reach the loop,
 * or two would answer. Thread surface belongs to the head recipe alone.
 */
@Component
public class TrillianNatureColin extends TrillianNatureMultiHead {

    public static final String ID = "colin";

    public TrillianNatureColin(ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature colin (conversation heads)";
    }

    @Override
    public String callName(Map<String, Object> attributes) {
        return "Colin";
    }
}
