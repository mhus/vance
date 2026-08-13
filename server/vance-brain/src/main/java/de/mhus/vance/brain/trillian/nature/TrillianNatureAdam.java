package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.trillian.TrillianAttributeStore;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature-A, {@code adam} — the first persistent Trillian.
 *
 * <p>Adds to {@link TrillianNatureBase}, not to Nature-0: the attribute
 * map and how it renders into both prompts are shared mechanics, not
 * generation-zero behaviour to inherit. What distinguishes adam is
 * that its attributes <b>outlive the process rows</b>.
 *
 * <p>Under Nature-0 an attribute lives in {@code engineParams} and dies
 * with the worker process. Everything that keeps it alive across an
 * archive is carrying code written for that one transition, and the
 * human cannot see the value at all except by asking the agent. adam
 * mirrors the map into {@code _vance/trillian/<account>.yaml} in the
 * pair's project on every change, and seeds a fresh worker loop from it.
 * The Trillian is then persistent in the plain sense: what it was told
 * to be is a document, editable by hand, gone only when deleted.
 *
 * <p>Nature-A's remaining promises from
 * {@code specification/public/trillian-engine.md} §13 — reflexion after
 * task completion, personality as a typed schema, mode switching, token
 * budgets — are not here yet. They overlay onto the same hooks and,
 * notably, onto the same document: a Nature that reflects needs
 * somewhere durable to write its conclusion, and that is now in place.
 */
@Component
@Slf4j
public class TrillianNatureAdam extends TrillianNatureBase {

    public static final String ID = "adam";

    private final TrillianAttributeStore attributeStore;

    public TrillianNatureAdam(
            ThinkProcessService thinkProcessService,
            TrillianAttributeStore attributeStore) {
        super(thinkProcessService);
        this.attributeStore = attributeStore;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature-A 'adam' (persistent attributes)";
    }

    @Override
    public Map<String, Object> initialAttributes(
            String tenantId, String projectId, String account) {
        Map<String, Object> stored = attributeStore.load(tenantId, projectId, account);
        if (!stored.isEmpty()) {
            log.info("Trillian adam: seeded {} attribute(s) for '{}' from {}",
                    stored.size(), account, TrillianAttributeStore.pathFor(account));
        }
        return stored;
    }

    @Override
    public void attributesChanged(
            ThinkProcessDocument worker, Map<String, Object> attributes) {
        String account = accountOf(worker);
        if (account == null) {
            // No account means no key to file this under. Only reachable
            // with broken wiring, where the attribute write itself was
            // already questionable — say so, don't fail the write.
            log.warn("Trillian adam: worker process '{}' carries no account name — "
                    + "attributes stay ephemeral", worker.getId());
            return;
        }
        attributeStore.save(
                worker.getTenantId(), worker.getProjectId(), account, attributes);
    }

    @Override
    public void attributesDiscarded(String tenantId, String projectId, String account) {
        attributeStore.discard(tenantId, projectId, account);
    }

    /** The service account off the worker's own wiring. */
    private static @Nullable String accountOf(ThinkProcessDocument worker) {
        if (worker.getEngineParams() == null) {
            return null;
        }
        Object raw = worker.getEngineParams()
                .get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
