package de.mhus.vance.shared.sso;

import de.mhus.vance.shared.user.ExternalEmails;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parsed, validated {@code _vance/config/sso/<tenant>.yaml} of one tenant.
 * Entries that failed validation are not in here — they were dropped
 * (fail-closed) and logged by {@link SsoConfigParser}.
 *
 * @param tenant the tenant the file belongs to — everything in it leads into
 *     this tenant and no other
 */
public record SsoConfig(
        String tenant,
        Map<String, Provider> providers,
        Map<String, DomainRule> domains,
        @Nullable ConsumerAccounts consumerAccounts) {

    public static final Duration DEFAULT_MAX_SESSION = Duration.ofDays(7);

    /** A tenant without (usable) SSO configuration. */
    public static SsoConfig empty(String tenant) {
        return new SsoConfig(tenant, Map.of(), Map.of(), null);
    }

    public SsoConfig {
        providers = Map.copyOf(providers);
        domains = Map.copyOf(domains);
    }

    /** The rule for a lowercase e-mail domain, if this tenant signs that domain in via SSO. */
    public Optional<DomainRule> domain(String domain) {
        return Optional.ofNullable(domains.get(domain));
    }

    public Optional<Provider> provider(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    /**
     * The provider an account of this tenant may be prepared with for
     * {@code normalizedEmail} — the domain rule of its domain, or, for a Gmail
     * address, the consumer-accounts provider if the tenant is open for it.
     * Empty when the address cannot sign in to this tenant at all.
     */
    public Optional<Provider> preparationProvider(String normalizedEmail) {
        String domain = ExternalEmails.domain(normalizedEmail);
        DomainRule rule = domains.get(domain);
        if (rule != null) {
            return provider(rule.provider());
        }
        if (ExternalEmails.isGmail(domain) && consumerAccounts != null) {
            return provider(consumerAccounts.provider());
        }
        return Optional.empty();
    }

    /** Whether this tenant accepts individually prepared private Google accounts. */
    public boolean consumerAccountsAllowed() {
        return consumerAccounts != null;
    }

    /** Whether this tenant offers any SSO sign-in at all. */
    public boolean offersSignIn() {
        return !domains.isEmpty() || consumerAccounts != null;
    }

    /**
     * One identity provider.
     *
     * @param key          name under {@code providers:}
     * @param type         {@code google} (only type in v1)
     * @param clientId     OAuth client id
     * @param clientSecret secret reference ({@code {{secret:…}}}), resolved at use
     * @param issuer       expected {@code iss}; Google's unless overridden (test seam)
     * @param discoveryUrl where endpoints come from; Google's unless {@code issuer} is overridden
     */
    public record Provider(
            String key, String type, String clientId, String clientSecret, String issuer, String discoveryUrl) {}

    /**
     * A work domain whose Workspace accounts sign in to this tenant.
     *
     * @param allow lowercase local parts allowed in; {@code null} = every address of the domain
     */
    public record DomainRule(
            String domain,
            String provider,
            boolean autoCreate,
            @Nullable Set<String> allow,
            Duration maxSession) {

        public DomainRule {
            allow = allow == null ? null : Set.copyOf(allow);
        }

        public boolean allows(String localPart) {
            return allow == null || allow.contains(localPart);
        }
    }

    /** Individually prepared private Gmail accounts of this tenant. */
    public record ConsumerAccounts(String provider, Duration maxSession) {}
}
