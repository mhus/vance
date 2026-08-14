package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature {@code void} — the architecture-proof baseline.
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
 * <p>Recipes pin this Nature via {@code params.nature: 'void'} in the
 * bundled {@code trillian-void.yaml}, {@code trillian-user-void.yaml} and
 * {@code trillian-worker-void.yaml}. The {@code trillian.yaml}
 * default-alias also pins {@code void} today.
 */
@Component
public class TrillianNatureVoid extends TrillianNatureBase {

    public static final String ID = "void";

    public TrillianNatureVoid(ThinkProcessService thinkProcessService) {
        super(thinkProcessService);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature void (architecture proof)";
    }
}
