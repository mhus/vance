package de.mhus.vance.shared.sso;

import de.mhus.vance.shared.user.ExternalEmails;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.helpers.MessageFormatter;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads {@code _vance/config/sso.yaml} into an {@link SsoConfig}.
 *
 * <p><b>Fail-closed, per entry.</b> A broken provider takes its domains with
 * it, a broken domain entry is dropped alone — each with an ERROR log. Nothing
 * here can widen access: an invalid {@code allow} list is not read as "everyone",
 * an unknown tenant is not created, a plaintext secret is not accepted. The
 * password login of everybody else is never affected.
 */
@Slf4j
public final class SsoConfigParser {

    public static final String GOOGLE = "google";
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    public static final String GOOGLE_DISCOVERY = GOOGLE_ISSUER + "/.well-known/openid-configuration";

    /** The system tenant holds the config and is never a sign-in target. */
    private static final String SYSTEM_TENANT = "_vance";

    private SsoConfigParser() {}

    /**
     * @param yamlBody     document content
     * @param tenantExists whether a tenant name exists
     */
    public static SsoConfig parse(String yamlBody, Predicate<String> tenantExists) {
        return parse(yamlBody, tenantExists, new ArrayList<>());
    }

    /**
     * Same as {@link #parse(String, Predicate)}, and every dropped entry is
     * also added to {@code problems} — the admin shell shows them before it
     * writes the file, so a typo does not silently disable a domain.
     */
    public static SsoConfig parse(String yamlBody, Predicate<String> tenantExists, List<String> problems) {
        Object parsed;
        try {
            parsed = new Yaml().load(yamlBody);
        } catch (RuntimeException e) {
            problem(problems, "SSO config: YAML does not parse — SSO disabled: {}", e.getMessage());
            return SsoConfig.EMPTY;
        }
        if (parsed == null) {
            return SsoConfig.EMPTY;
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            problem(problems, "SSO config: top level must be a map — SSO disabled");
            return SsoConfig.EMPTY;
        }
        Map<String, SsoConfig.Provider> providers = parseProviders(root.get("providers"), problems);
        Map<String, SsoConfig.DomainRule> domains =
                parseDomains(root.get("domains"), providers, tenantExists, problems);
        SsoConfig.ConsumerAccounts consumer =
                parseConsumerAccounts(root.get("consumerAccounts"), providers, tenantExists, problems);
        return new SsoConfig(providers, domains, consumer);
    }

    private static Map<String, SsoConfig.Provider> parseProviders(@Nullable Object raw, List<String> problems) {
        Map<String, SsoConfig.Provider> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            problem(problems, "SSO config: 'providers' must be a map — no provider configured");
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
            try {
                out.put(key, parseProvider(key, e.getValue()));
            } catch (IllegalStateException ex) {
                problem(problems, "SSO config: provider '{}' dropped: {}", key, ex.getMessage());
            }
        }
        return out;
    }

    private static SsoConfig.Provider parseProvider(String key, @Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> spec)) {
            throw new IllegalStateException("must be a map");
        }
        String type = requiredString(spec, "type");
        if (!GOOGLE.equals(type)) {
            throw new IllegalStateException("type '" + type + "' is not supported (only 'google')");
        }
        String clientId = requiredString(spec, "clientId");
        String clientSecret = requiredString(spec, "clientSecret");
        if (!clientSecret.startsWith("{{secret:") || !clientSecret.endsWith("}}")) {
            throw new IllegalStateException(
                    "'clientSecret' must be a secret reference ({{secret:…}}), never plaintext");
        }
        String issuer = optionalString(spec, "issuer");
        if (issuer == null) {
            return new SsoConfig.Provider(key, type, clientId, clientSecret, GOOGLE_ISSUER, GOOGLE_DISCOVERY);
        }
        // Issuer override: the identity provider is not Google itself but
        // speaks its protocol (the QA fake). Endpoints come from its discovery.
        String trimmed = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return new SsoConfig.Provider(
                key, type, clientId, clientSecret, trimmed, trimmed + "/.well-known/openid-configuration");
    }

    private static Map<String, SsoConfig.DomainRule> parseDomains(
            @Nullable Object raw,
            Map<String, SsoConfig.Provider> providers,
            Predicate<String> tenantExists,
            List<String> problems) {
        Map<String, SsoConfig.DomainRule> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            problem(problems, "SSO config: 'domains' must be a list — no domain configured");
            return out;
        }
        Set<String> duplicates = new HashSet<>();
        for (Object item : list) {
            String label = item instanceof Map<?, ?> m ? String.valueOf(m.get("domain")) : String.valueOf(item);
            try {
                SsoConfig.DomainRule rule = parseDomain(item, providers, tenantExists);
                if (out.containsKey(rule.domain()) || duplicates.contains(rule.domain())) {
                    // Two tenants claiming one domain: neither wins.
                    out.remove(rule.domain());
                    duplicates.add(rule.domain());
                    problem(problems, "SSO config: domain '{}' is listed more than once — dropped", rule.domain());
                    continue;
                }
                out.put(rule.domain(), rule);
            } catch (IllegalStateException ex) {
                problem(problems, "SSO config: domain entry '{}' dropped: {}", label, ex.getMessage());
            }
        }
        return out;
    }

    private static SsoConfig.DomainRule parseDomain(
            @Nullable Object raw, Map<String, SsoConfig.Provider> providers, Predicate<String> tenantExists) {
        if (!(raw instanceof Map<?, ?> spec)) {
            throw new IllegalStateException("must be a map");
        }
        String domain = requiredString(spec, "domain").trim().toLowerCase(Locale.ROOT);
        if (!domain.contains(".") || domain.contains("@") || domain.contains(" ")) {
            throw new IllegalStateException("'" + domain + "' is not a domain");
        }
        if (ExternalEmails.isGmail(domain)) {
            throw new IllegalStateException(
                    "'" + domain + "' belongs to everybody — private accounts go through 'consumerAccounts'");
        }
        String tenant = checkedTenant(requiredString(spec, "tenant"), tenantExists);
        String provider = providerKey(spec, providers);
        boolean autoCreate = Boolean.TRUE.equals(spec.get("autoCreate"));
        Set<String> allow = parseAllow(spec.get("allow"));
        Duration maxSession = maxSession(spec.get("maxSessionDays"));
        return new SsoConfig.DomainRule(domain, tenant, provider, autoCreate, allow, maxSession);
    }

    private static SsoConfig.@Nullable ConsumerAccounts parseConsumerAccounts(
            @Nullable Object raw,
            Map<String, SsoConfig.Provider> providers,
            Predicate<String> tenantExists,
            List<String> problems) {
        if (raw == null) {
            return null;
        }
        try {
            if (!(raw instanceof Map<?, ?> spec)) {
                throw new IllegalStateException("must be a map");
            }
            String provider = providerKey(spec, providers);
            if (!(spec.get("tenants") instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("'tenants' must be a non-empty list");
            }
            Set<String> tenants = new HashSet<>();
            for (Object t : list) {
                if (!(t instanceof String s) || s.isBlank()) {
                    throw new IllegalStateException("'tenants' contains a blank or non-string entry");
                }
                tenants.add(checkedTenant(s, tenantExists));
            }
            return new SsoConfig.ConsumerAccounts(provider, tenants, maxSession(spec.get("maxSessionDays")));
        } catch (IllegalStateException ex) {
            problem(problems, "SSO config: 'consumerAccounts' dropped: {}", ex.getMessage());
            return null;
        }
    }

    /** Logs a dropped entry at ERROR and records it for the caller. */
    private static void problem(List<String> problems, String format, Object... args) {
        log.error(format, args);
        problems.add(MessageFormatter.arrayFormat(format, args).getMessage());
    }

    private static String providerKey(Map<?, ?> spec, Map<String, SsoConfig.Provider> providers) {
        String provider = requiredString(spec, "provider").trim().toLowerCase(Locale.ROOT);
        if (!providers.containsKey(provider)) {
            throw new IllegalStateException("provider '" + provider + "' is not configured (or was dropped)");
        }
        return provider;
    }

    private static String checkedTenant(String tenant, Predicate<String> tenantExists) {
        String t = tenant.trim();
        if (SYSTEM_TENANT.equals(t)) {
            throw new IllegalStateException("tenant '" + SYSTEM_TENANT + "' is not a sign-in target");
        }
        if (!tenantExists.test(t)) {
            throw new IllegalStateException("tenant '" + t + "' does not exist");
        }
        return t;
    }

    /**
     * {@code null} when the key is absent (= the whole domain). A list that is
     * present but empty after validation is an error, not "everyone".
     */
    private static @Nullable Set<String> parseAllow(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'allow' must be a list of names");
        }
        Set<String> out = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank() || s.contains("@") || s.contains("*")) {
                throw new IllegalStateException("'allow' holds names (local parts), not '" + item + "'");
            }
            out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("'allow' is empty — omit it to allow the whole domain");
        }
        return out;
    }

    private static Duration maxSession(@Nullable Object raw) {
        if (raw == null) {
            return SsoConfig.DEFAULT_MAX_SESSION;
        }
        if (!(raw instanceof Integer days) || days < 1 || days > 90) {
            throw new IllegalStateException("'maxSessionDays' must be a whole number between 1 and 90");
        }
        return Duration.ofDays(days);
    }

    private static String requiredString(Map<?, ?> spec, String field) {
        if (!(spec.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("missing '" + field + "'");
        }
        return s.trim();
    }

    private static @Nullable String optionalString(Map<?, ?> spec, String field) {
        return spec.get(field) instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
