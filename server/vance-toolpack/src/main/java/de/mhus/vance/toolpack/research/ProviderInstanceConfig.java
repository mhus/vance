package de.mhus.vance.toolpack.research;

import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Configuration handed by {@code SearchProviderFactory} to
 * {@link SearchProtocol#instantiate} when it builds one instance of a
 * protocol from its configuration document under
 * {@code _vance/config/research/}.
 *
 * <p>{@code credentials} is a supplier rather than a value, the same shape the
 * feed and mount SPIs use, and for the same three reasons:
 * <ul>
 *   <li><b>Read on demand.</b> A rotated credential takes effect without
 *       waiting for the factory cache to expire.
 *   <li><b>One resolver.</b> The factory owns reference resolution
 *       ({@code {{secret:…}}}, {@code {noop}} literals, vault lookups), so a
 *       protocol cannot get it subtly wrong — three of them used to do this
 *       themselves against the setting service.
 *   <li><b>No secret in a record.</b> A credential field would land in the
 *       auto-generated {@code toString()} of every request record it travelled
 *       in, which is one debug log away from being leaked.
 * </ul>
 * It may return null — an unauthenticated source is the normal case.
 *
 * <p>{@code credentialLocation} stays for diagnostics: it names the document
 * and field the value would come from, which is what an operator needs when it
 * is missing.
 *
 * <p>{@code extras} carries protocol-specific tuning knobs that don't
 * fit into the common fields (e.g. {@code regionHint},
 * {@code timeoutMs}, OpenAlex's {@code contactEmail}). Protocols pick
 * what they need; unknown keys are ignored.
 *
 * <p>{@code tenantId} / {@code projectId} say <b>where this instance was
 * assembled</b>. Most protocols never need them: every call they make
 * carries a {@link SearchScope} already. They exist for the call a protocol has
 * to make <i>outside</i> any request — fetching a remote capability
 * declaration, for instance, which happens behind
 * {@link SearchProviderInstance#modalities()} where there is no scope
 * parameter and cannot be one, because the dispatcher filters on
 * modality before it ever asks about availability.
 *
 * <p>The factory has both values anyway (its cache is keyed on them), so
 * this is a fact the config was simply not carrying rather than a new
 * concept. Deliberately <b>no {@code processId}</b>: the factory resolves
 * endpoint configuration at project scope precisely so one process's overrides
 * cannot leak into an instance every other process in the project shares,
 * and handing a process id to a project-lived instance would reopen that.
 */
public record ProviderInstanceConfig(
        String instanceId,
        String protocolId,
        String baseUrl,
        String credentialLocation,
        Supplier<@Nullable String> credentials,
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
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (credentialLocation == null) {
            credentialLocation = "";
        }
        if (credentials == null) {
            credentials = () -> null;
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * The scope-less, credential-less form, for protocols that need neither —
     * which is most of them. Convenient in tests, and honest about what those
     * protocols use: nothing here tells them where they are because nothing
     * needs to.
     */
    public ProviderInstanceConfig(
            String instanceId,
            String protocolId,
            String baseUrl,
            String credentialLocation,
            Map<String, Object> extras) {
        this(instanceId, protocolId, baseUrl, credentialLocation,
                () -> null, extras, null, null);
    }

    /** Scoped but credential-less — for an endpoint that needs no key. */
    public ProviderInstanceConfig(
            String instanceId,
            String protocolId,
            String baseUrl,
            String credentialLocation,
            Map<String, Object> extras,
            @Nullable String tenantId,
            @Nullable String projectId) {
        this(instanceId, protocolId, baseUrl, credentialLocation,
                () -> null, extras, tenantId, projectId);
    }

    /** The credential right now, or null when this source is unauthenticated. */
    public @Nullable String credential() {
        return credentials.get();
    }

    /** A protocol-specific knob as text, or {@code fallback} when unset. */
    public String extra(String key, String fallback) {
        Object value = extras.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
