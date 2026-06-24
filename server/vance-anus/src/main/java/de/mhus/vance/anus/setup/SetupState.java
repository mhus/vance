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

    private ProviderPreset provider = ProviderPreset.GEMINI;
    private String aiModel = "";
    private @Nullable String aiApiKey;

    /** Set together with {@link #aiApiKey} for providers that have their own embeddings. */
    private @Nullable String embeddingApiKey;

    private @Nullable String serperKey;
}
