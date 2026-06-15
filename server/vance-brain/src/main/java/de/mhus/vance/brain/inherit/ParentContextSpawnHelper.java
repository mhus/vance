package de.mhus.vance.brain.inherit;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Centralised spawn-time wrapping of a worker prompt with a
 * {@code ## Parent context} block. Consumed by every code path that
 * creates a child think-process so the wrap semantics stays consistent:
 *
 * <ul>
 *   <li>{@code ProcessCreateTool} — Arthur's DELEGATE + explicit
 *       {@code process_create} calls.</li>
 *   <li>{@code MarvinEngine.driveSubProcessOnce} — Marvin's
 *       {@code CALL_RECIPE} spawn-and-drive path (which bypasses
 *       {@code ProcessCreateTool}).</li>
 *   <li>Future spawn paths that don't route through the tool layer.</li>
 * </ul>
 *
 * <p>Both render the same Markdown block (`## Parent context (from
 * `name`, ...)`) and append the same `process_history_text` footer when
 * there is no parent or the level is {@code none} — so the spawned
 * worker always knows how to pull more parent history on demand,
 * regardless of which spawn path created it.
 */
@Component
@RequiredArgsConstructor
public class ParentContextSpawnHelper {

    /** Default budget for the inline parent-context block. */
    public static final int DEFAULT_BUDGET_CHARS = 30_000;

    private final ParentContextRenderer parentContextRenderer;
    private final ThinkProcessService thinkProcessService;

    /**
     * Resolves the caller process, asks the renderer for the matching
     * context block, and wraps {@code originalPrompt} with it. When
     * there is no caller, the original prompt is returned unchanged.
     * When the level is {@code none} or the renderer has nothing to
     * include, a one-line footer naming the parent process is appended
     * instead — so the worker always knows how to reach back via
     * {@code process_history_text}.
     *
     * @param inheritContextRaw the {@code inheritContext} recipe param
     *                          string (typically from
     *                          {@code AppliedRecipe.params()})
     * @param callerProcessId   Mongo id of the spawning process; may
     *                          be {@code null} for system-spawned
     *                          children
     * @param originalPrompt    the user-supplied content to wrap
     * @return the (potentially wrapped) prompt, never {@code null}
     */
    public String wrap(
            @Nullable String inheritContextRaw,
            @Nullable String callerProcessId,
            @Nullable String originalPrompt) {
        String taskBody = originalPrompt == null ? "" : originalPrompt;
        if (callerProcessId == null || callerProcessId.isBlank()) {
            return taskBody;
        }
        ThinkProcessDocument parent = thinkProcessService.findById(callerProcessId).orElse(null);
        if (parent == null) return taskBody;
        String parentName = parent.getName() == null || parent.getName().isBlank()
                ? callerProcessId : parent.getName();

        InheritLevel level = InheritLevel.parse(inheritContextRaw);
        String contextBlock = null;
        if (!(level instanceof InheritLevel.None)) {
            contextBlock = parentContextRenderer.render(
                    callerProcessId,
                    parent.getTenantId(),
                    parent.getSessionId(),
                    level,
                    DEFAULT_BUDGET_CHARS);
        }

        StringBuilder out = new StringBuilder();
        if (contextBlock != null && !contextBlock.isBlank()) {
            out.append(contextBlock);
            if (!contextBlock.endsWith("\n")) out.append('\n');
            out.append('\n');
            out.append("## Your task\n\n");
            out.append(taskBody);
        } else {
            // No context block — either the recipe set inheritContext=none
            // or the parent has no history yet. Still surface the parent
            // name so the worker can pull on demand.
            out.append(taskBody);
            if (!taskBody.endsWith("\n")) out.append('\n');
            out.append('\n');
            out.append("---\n");
            out.append("Parent process: `").append(parentName)
                    .append("` (in your session). Pull its history on demand via:\n")
                    .append("  process_history_text(name=\"").append(parentName).append("\")\n");
        }
        return out.toString();
    }
}
