package de.mhus.vance.anus.setup;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Mutable state captured by the setup wizard between the tenant/user
 * selection and the final save.
 *
 * <p>{@code tenantCreated} / {@code userCreated} flag whether the wizard
 * still needs to call {@code ensure()} / {@code create()} on the
 * respective service (true when the operator chose "create new"). For
 * existing records the same flags stay {@code false} and the {@code Save}
 * step only writes the AI-provider + research settings — plus an
 * {@code update()} on the user when title or email were edited.
 */
@Getter
@Setter
class SetupState {

    private String tenantId = "";
    private @Nullable String tenantTitle;
    private boolean tenantCreated;

    private String userName = "";
    private @Nullable String userTitle;
    private @Nullable String userEmail;
    /** Plaintext, only populated for newly-created users. */
    private @Nullable String userPassword;
    private boolean userCreated;

    /** Track edits so {@code save()} only calls {@code userService.update} when needed. */
    private boolean userFieldsChanged;

    /** {@code null} = no AI provider configured (the new, no-default state). */
    private @Nullable ProviderPreset provider;

    /**
     * Provider-instance name for presets that let the operator name it
     * ({@link ProviderPreset#CUSTOM}); {@code null} for the fixed-id presets,
     * which carry their own. This is the settings namespace
     * ({@code ai.provider.<instance>.*}), the {@code ai.default.provider}
     * value and the left half of every model spec — so a gateway gets its own
     * credentials instead of overwriting OpenAI's.
     */
    private @Nullable String instanceName;

    private String aiModel = "";
    private @Nullable String aiApiKey;

    /** Endpoint override — only set for {@link ProviderPreset#CUSTOM}. */
    private @Nullable String baseUrl;

    /**
     * The settings namespace this state writes under, or {@code null} when no
     * provider is picked / a custom one is not yet named.
     */
    @Nullable String effectiveInstance() {
        ProviderPreset p = provider;
        if (p == null) {
            return null;
        }
        if (!p.requiresInstanceName()) {
            return p.settingsId();
        }
        return ProviderPreset.normaliseInstanceName(instanceName);
    }

    /** Set together with {@link #aiApiKey} for providers that have their own embeddings. */
    private @Nullable String embeddingApiKey;

    private @Nullable String serperKey;
}
