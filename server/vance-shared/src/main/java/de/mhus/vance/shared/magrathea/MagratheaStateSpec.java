package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One state in a workflow's {@code states:} map. Common lifecycle
 * fields (type, transitions, retry, storeAs, on, catch) are typed;
 * type-specific fields (recipe, run, tool, inbox, …) ride in the
 * generic {@link #spec} map for the type-executor to read.
 *
 * <p>This intentionally mirrors the loose typing of
 * {@code ResolvedScheduler.params}: the parser checks the shape that
 * is universal, the type-executors validate the bits that are specific
 * to them. The trade-off is fewer dedicated record classes and one
 * place where the runtime contract is enforced — closer to the YAML
 * the user wrote.
 *
 * @param name State key as declared in the YAML.
 * @param type Task type — drives type-executor dispatch (plan §4).
 * @param description Optional documentation string.
 * @param timeoutSeconds Task-level timeout. Null for {@code TIMER_TASK},
 *                       {@code CONDITION_TASK}, {@code TERMINAL}.
 * @param storeAs Optional variable key to write the task output into.
 * @param onOutcomes Outcome-to-state mapping for the {@code on:} block.
 * @param catchKinds Error-kind-to-state mapping for the {@code catch:}
 *                   block.
 * @param transitions Ordered list — used only by {@code CONDITION_TASK}.
 * @param retry Retry spec, {@link MagratheaRetrySpec#none()} when omitted.
 * @param spec Type-specific fields verbatim from the YAML state. The
 *             type-executor reads {@code recipe:}, {@code run:},
 *             {@code inbox:}, {@code workflow:}, {@code params:},
 *             {@code tool:}, {@code duration:}, {@code result:}, etc.
 *             from here.
 */
public record MagratheaStateSpec(
        String name,
        MagratheaTaskType type,
        @Nullable String description,
        @Nullable Integer timeoutSeconds,
        @Nullable String storeAs,
        Map<String, String> onOutcomes,
        Map<MagratheaErrorKind, String> catchKinds,
        List<MagratheaTransition> transitions,
        MagratheaRetrySpec retry,
        Map<String, Object> spec) {

    /** Convenience for type-executors: read a typed spec field. */
    public @Nullable Object specField(String key) {
        return spec.get(key);
    }

    /** Convenience: read a string spec field, or {@code null} when missing/blank. */
    public @Nullable String specString(String key) {
        Object raw = spec.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
