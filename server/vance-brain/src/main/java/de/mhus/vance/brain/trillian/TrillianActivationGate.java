package de.mhus.vance.brain.trillian;

import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The kill switch for the one thing Trillian does unattended: starting a
 * User-Loop. Setting {@value #ENABLED_KEY} ({@code false}) means no new
 * loop is bootstrapped — the control conversation stays fully usable, it
 * just has no working side.
 *
 * <p><b>Scope cascade.</b> Resolved like {@link TrillianModelGate}'s
 * allowlist: hub ({@code _user_<trillian>}) → project → {@code _vance},
 * read <b>without</b> the think-process scope so a {@code setting_set}
 * cannot switch its own gate back on. That makes the setting answer two
 * questions with one key: {@code false} in a Trillian's hub deactivates
 * exactly that Trillian, {@code false} at project or tenant level stops
 * every new loop in that scope.
 *
 * <p><b>Running loops are untouched.</b> The gate is checked when a loop
 * would be built; an already wired pair keeps running. Stopping live work
 * is an explicit, per-process decision ({@code //trillian stop}, the
 * process stop), never the side effect of a configuration change.
 *
 * <p><b>Self-healing.</b> A suppressed loop retries on the control
 * process's next turn — flip the setting and the loop builds then. Same
 * pattern as the model gate; the refusal is announced once in the control
 * chat ({@link TrillianSessionBootstrapper} {@code suppressLoop}).
 */
@Component
@RequiredArgsConstructor
public class TrillianActivationGate {

    public static final String ENABLED_KEY = "trillian.enabled";

    private final SettingService settingService;

    /**
     * Whether this Trillian may start a user-loop. Unset means enabled —
     * the switch exists to make deactivation possible, not to tax the
     * default case.
     */
    public boolean loopsEnabled(String tenantId, @Nullable String accountId, @Nullable String projectId) {
        return settingService.getBooleanValueUserProjectCascade(
                tenantId, accountId, projectId, /*thinkProcessId*/ null, ENABLED_KEY, /*defaultValue*/ true);
    }

    /** Operator-facing reason, naming the setting and where to change it. */
    public static String refusalMessage() {
        return "the user-loop is switched off by setting '" + ENABLED_KEY
                + "' (set it to true in the Trillian's hub `_user_<trillian>`,"
                + " else project/tenant scope)";
    }
}
