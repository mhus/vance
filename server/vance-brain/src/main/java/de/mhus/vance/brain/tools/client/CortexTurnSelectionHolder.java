package de.mhus.vance.brain.tools.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Per-process, per-turn holder for the Cortex "bound file" selection so
 * the no-arg {@code doc_get_selection()} tool can find it.
 *
 * <p>The selection range rides transiently with the steer
 * ({@code ProcessSteerRequest.boundDocSelection}) and is drained into
 * the engine per turn — it is <em>not</em> visible to a tool through the
 * standard {@link de.mhus.vance.toolpack.ToolInvocationContext} (which
 * only carries scope ids). The engine stashes the current turn's
 * selection here (keyed by processId) right before running the LLM, and
 * clears it when the turn carried none. Lanes serialise turns per
 * process, so a single processId slot is race-free.
 */
@Service
public class CortexTurnSelectionHolder {

    public record Selection(String documentId, int from, int to) {}

    private final Map<String, Selection> byProcess = new ConcurrentHashMap<>();

    /** Set (or clear, when {@code selection == null}) this turn's bound selection. */
    public void set(@Nullable String processId, @Nullable Selection selection) {
        if (processId == null) return;
        if (selection == null) {
            byProcess.remove(processId);
        } else {
            byProcess.put(processId, selection);
        }
    }

    public @Nullable Selection get(@Nullable String processId) {
        return processId == null ? null : byProcess.get(processId);
    }
}
