package de.mhus.vance.shared.vault;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The vault that is just Vancetope's own settings — {@code vault.type: settings},
 * and the <b>default</b> when no {@code vault.type} is bound anywhere along the
 * cascade (see {@link VaultService}).
 *
 * <p>Purpose: make {@code {{secret:vault:<key>}}} work from day one, without an
 * external secret manager. A document that references {@code vault:my-token}
 * stays valid when Infisical is bound later — the value moves, the reference does
 * not. Which is what the provider-agnostic {@code vault:} prefix was for.
 *
 * <h2>HIDDEN only — the one thing that must not be wrong here</h2>
 * Reads go through {@link SettingService#getReferenceSecretCascade}, so this
 * provider hands out {@link de.mhus.vance.api.settings.SettingType#HIDDEN} values
 * and refuses {@code PASSWORD} ones with a
 * {@code SecretAccessDeniedException}.
 *
 * <p>Before this provider existed, {@code vault:} bypassed the setting-type gate
 * legitimately: it never touched settings. Now it does touch them, so reaching
 * for {@code getDecryptedPassword} instead would make
 * {@code {{secret:vault:ai.provider.default.apiKey}}} readable again — exactly the
 * hole the type split closed. See {@code planning/setting-type-hidden.md} §5.
 *
 * <h2>Cascade and scope</h2>
 * Reads use the <b>project</b> cascade (think-process → project → {@code _tenant})
 * and deliberately skip the user layer, matching the bare-key reference form. A
 * per-user setting must not be able to shadow a project-level credential that a
 * shared tool document depends on — that is what the explicit
 * {@code {{secret:user:…}}} form is for.
 *
 * <p>Writes land at <b>project</b> scope through
 * {@link SettingService#setAgentSecret}, which applies the agent-write rules: no
 * overwrite of an existing PASSWORD setting, reserved keys refused, result always
 * HIDDEN. That is the right gate — {@code vault_secret_generate} /
 * {@code vault_secret_set} are LLM tools. A write without a project in scope is
 * refused rather than silently redirected to {@code _tenant}, which would let a
 * project-scoped caller create a tenant-wide credential.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettingsVaultProvider implements VaultProvider {

    /** Value of {@code vault.type} that selects this provider. */
    public static final String TYPE = "settings";

    private final SettingService settingService;

    @Override
    public String type() {
        return TYPE;
    }

    /** No remote endpoint — {@code vault.baseUrl} is meaningless here. */
    @Override
    public boolean requiresEndpoint() {
        return false;
    }

    @Override
    public @Nullable String readSecret(VaultBinding binding, VaultScope scope, String key) {
        log.trace("Settings vault read: key='{}' tenant='{}' project='{}'",
                key, scope.tenantId(), scope.projectId());
        return settingService.getReferenceSecretCascade(
                scope.tenantId(), scope.projectId(), /*thinkProcessId*/ null, key);
    }

    @Override
    public void writeSecret(VaultBinding binding, VaultScope scope, String key, String value) {
        String projectId = scope.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new VaultException(
                    "settings vault: writing '" + key + "' requires a project scope — "
                            + "a tenant-wide secret has to be set by a human in the settings editor");
        }
        log.trace("Settings vault write: key='{}' tenant='{}' project='{}'",
                key, scope.tenantId(), projectId);
        // HIDDEN: a vault secret exists to be resolved by the very agents and
        // scripts that write it (vault_secret_generate → {{secret:vault:…}}).
        // That is the use, so that is the type.
        settingService.setAgentSecret(
                scope.tenantId(), SettingService.SCOPE_PROJECT, projectId, key,
                value, SettingType.HIDDEN);
    }
}
