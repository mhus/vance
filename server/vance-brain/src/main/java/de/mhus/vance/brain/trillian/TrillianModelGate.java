package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelAllowlist;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The cost gate for the one process that runs unattended: the Trillian
 * User-Loop identity session. Its model may be pinned down — to
 * self-hosted instances, to cheap tiers — per Trillian.
 *
 * <p><b>Relaxed by design.</b> Unlike Wowbagger's {@code wowbagger.allowed-models}
 * (fail-closed: no allowlist, no run), an unset {@value #ALLOWED_MODELS_KEY}
 * means {@code *} — everything is allowed. The setting exists to make
 * restriction <em>possible</em>, not to tax the default case.
 *
 * <p><b>Where it lives.</b> Per Trillian in its hub
 * ({@code _user_<trillian>}), resolved over the settings cascade
 * {@code _user_<account> → project → _tenant}. Read <b>without</b> the
 * think-process scope on purpose: a {@code setting_set} must not be able
 * to widen its own gate (the self-approve bypass the Wowbagger gate
 * documents).
 *
 * <p><b>Blank is unset.</b> A blank value at any layer is skipped, not a
 * cascade stop — same as Wowbagger. Otherwise an emptied project or hub
 * field (the setting-form writes {@code ""} to shadow an inherited value)
 * would turn a tenant restriction back into {@code *}. Lifting a tenant
 * restriction for one project takes an explicit {@code *} there.
 *
 * <p><b>What is gated.</b> Only the User-Loop (the expensive continuous
 * runner), checked at its start/rebuild — Control and task workers are
 * operator-chosen recipes and are not checked here.
 */
@Component
@RequiredArgsConstructor
public class TrillianModelGate {

    public static final String ALLOWED_MODELS_KEY = "trillian.allowed-models";

    /** Unset means "everything allowed" — see class javadoc. */
    public static final String DEFAULT_ALLOWLIST = "*";

    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;

    /**
     * The effective allowlist for this Trillian: the first non-blank value of
     * hub, project, tenant layer — else {@code *}.
     */
    public String allowlistFor(String tenantId, String accountId, @Nullable String projectId) {
        List<String> layers = new ArrayList<>(3);
        layers.add(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + accountId);
        if (projectId != null
                && !projectId.isBlank()
                && !layers.contains(projectId)
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            layers.add(projectId);
        }
        layers.add(HomeBootstrapService.TENANT_PROJECT_NAME);
        for (String layer : layers) {
            String v = settingService.getStringValue(tenantId, SettingService.SCOPE_PROJECT, layer, ALLOWED_MODELS_KEY);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return DEFAULT_ALLOWLIST;
    }

    /**
     * Resolves the loop's model spec and refuses it when the allowlist does
     * not cover it. Called before anything of the loop is minted — a refused
     * model means the loop does not run and the operator gets a message
     * naming both the model and the setting.
     *
     * <p>Two projects, two questions. The model is resolved where the loop
     * will run ({@code loopProjectId}, the hub — its alias settings are the
     * ones the loop will see). The allowlist is read over account hub →
     * {@code controlProjectId} → {@code _tenant} (D8: user → project →
     * tenant): the project the Trillian was started in is the project layer.
     *
     * @throws IllegalStateException when the resolved model is not approved
     */
    public void checkLoopModel(
            String tenantId,
            String accountId,
            @Nullable String controlProjectId,
            @Nullable String loopProjectId,
            @Nullable String modelSpec) {
        AiModelResolver.Resolved resolved =
                aiModelResolver.resolveOrDefault(modelSpec, tenantId, loopProjectId, /*processId*/ null);
        String model = resolved.providerInstance() + ":" + resolved.modelName();
        String allowlist = allowlistFor(tenantId, accountId, controlProjectId);
        if (!ModelAllowlist.approved(model, allowlist)) {
            throw new IllegalStateException("Trillian user-loop model '" + model
                    + "' is not approved by setting '" + ALLOWED_MODELS_KEY
                    + "' (comma-separated patterns, * wildcards, matched against"
                    + " 'providerInstance:modelName' or the bare model name; set it in the"
                    + " Trillian's hub `_user_<trillian>`, else project/tenant scope"
                    + "; current allowlist: '" + allowlist + "')");
        }
    }
}
