package de.mhus.vance.shared.sso;

import de.mhus.vance.shared.user.ExternalEmails;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parsed, validated {@code _vance/config/sso.yaml}. Entries that failed
 * validation are not in here — they were dropped (fail-closed) and logged by
 * {@link SsoConfigParser}.
 */
public record SsoConfig(
        Map<String, Provider> providers,
        Map<String, DomainRule> domains,
        @Nullable ConsumerAccounts consumerAccounts) {

    public static final Duration DEFAULT_MAX_SESSION = Duration.ofDays(7);

    public static final SsoConfig EMPTY = new SsoConfig(Map.of(), Map.of(), null);

    public SsoConfig {
        providers = Map.copyOf(providers);
        domains = Map.copyOf(domains);
    }

    /** The rule for a lowercase e-mail domain, if SSO is configured for it. */
    public Optional<DomainRule> domain(String domain) {
        return Optional.ofNullable(domains.get(domain));
    }

    public Optional<Provider> provider(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    /**
     * The provider an account in {@code tenant} may be prepared with for
     * {@code normalizedEmail} — the domain rule pointing at this tenant, or,
     * for a Gmail address, the consumer-accounts provider if the tenant is
     * open for it. Empty when the address cannot sign in to this tenant at all.
     */
    public Optional<Provider> preparationProvider(String tenant, String normalizedEmail) {
        String domain = ExternalEmails.domain(normalizedEmail);
        DomainRule rule = domains.get(domain);
        if (rule != null) {
            return rule.tenant().equals(tenant) ? provider(rule.provider()) : Optional.empty();
        }
        if (ExternalEmails.isGmail(domain) && consumerAccountsAllowed(tenant) && consumerAccounts != null) {
            return provider(consumerAccounts.provider());
        }
        return Optional.empty();
    }

    /** Whether {@code tenant} accepts individually prepared private Google accounts. */
    public boolean consumerAccountsAllowed(String tenant) {
        return consumerAccounts != null && consumerAccounts.tenants().contains(tenant);
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
     * Domain → tenant mapping for Workspace accounts.
     *
     * @param allow lowercase local parts allowed in; {@code null} = every address of the domain
     */
    public record DomainRule(
            String domain,
            String tenant,
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

    /** Tenants that accept individually prepared private Gmail accounts. */
    public record ConsumerAccounts(String provider, Set<String> tenants, Duration maxSession) {
        public ConsumerAccounts {
            tenants = Set.copyOf(tenants);
        }
    }
}
