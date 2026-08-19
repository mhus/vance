package de.mhus.vance.toolpack.research;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Configuration handed by {@code SearchProviderFactory} to
 * {@link SearchProtocol#instantiate} when it builds one instance of a
 * protocol from the {@code research.endpoint.<id>.*} settings.
 *
 * <p>{@code credentialSettingKey} is the setting key the instance will
 * look up at request time via {@code SettingService} — read on demand
 * so a rotated key is picked up without re-assembling the factory
 * cache. The factory itself never reads the credential.
 *
 * <p>{@code extras} carries protocol-specific tuning knobs that don't
 * fit into the four common fields (e.g. {@code regionHint},
 * {@code timeoutMs}, OpenAlex's {@code contactEmail}). Protocols pick
 * what they need; unknown keys are ignored.
 *
 * <p>{@code tenantId} / {@code projectId} say <b>where this instance was
 * assembled</b>. Most protocols never need them: every call they make
 * carries a {@link SearchScope} already, and reading the credential from
 * that scope is the house style. They exist for the call a protocol has
 * to make <i>outside</i> any request — fetching a remote capability
 * declaration, for instance, which happens behind
 * {@link SearchProviderInstance#modalities()} where there is no scope
 * parameter and cannot be one, because the dispatcher filters on
 * modality before it ever asks about availability.
 *
 * <p>The factory has both values anyway (its cache is keyed on them), so
 * this is a fact the config was simply not carrying rather than a new
 * concept. Deliberately <b>no {@code processId}</b>: the factory resolves
 * endpoint settings at project scope precisely so one process's overrides
 * cannot leak into an instance every other process in the project shares,
 * and handing a process id to a project-lived instance would reopen that.
 */
public record ProviderInstanceConfig(
        String instanceId,
        String protocolId,
        String baseUrl,
        String credentialSettingKey,
        Map<String, Object> extras,
        @Nullable String tenantId,
        @Nullable String projectId) {

    public ProviderInstanceConfig {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        if (protocolId == null || protocolId.isBlank()) {
            throw new IllegalArgumentException("protocolId is required");
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * The scope-less form, for protocols that do everything inside a
     * request — which is all of them but one. Convenient in tests, and
     * honest about what those protocols use: nothing here tells them
     * where they are because nothing needs to.
     */
    public ProviderInstanceConfig(
            String instanceId,
            String protocolId,
            String baseUrl,
            String credentialSettingKey,
            Map<String, Object> extras) {
        this(instanceId, protocolId, baseUrl, credentialSettingKey, extras, null, null);
    }
}
