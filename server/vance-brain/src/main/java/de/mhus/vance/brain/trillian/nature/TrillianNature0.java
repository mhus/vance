package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature-0 — the architecture-proof baseline.
 *
 * <p>Deliberately empty beyond its identity: everything it does lives in
 * {@link TrillianNatureBase}, and everything it does not do is what makes
 * it the baseline. No personality, no reflexion, no persistence — the
 * attribute map is rendered into both prompts and dies with the process
 * rows.
 *
 * <p>That emptiness is the point. It proves the two-session mechanics on
 * their own, and it gives every later Nature a statement of what it is
 * adding to.
 *
 * <p>Recipes pin Nature-0 via {@code params.nature: '0'} in the bundled
 * {@code trillian-0.yaml}, {@code trillian-user-0.yaml} and
 * {@code trillian-worker-0.yaml}. The {@code trillian.yaml} default-alias
 * also pins Nature-0 today.
 */
@Component
public class TrillianNature0 extends TrillianNatureBase {

    public static final String ID = "0";

    public TrillianNature0(ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature-0 (architecture proof)";
    }
}
