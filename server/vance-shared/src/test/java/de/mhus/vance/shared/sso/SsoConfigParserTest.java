package de.mhus.vance.shared.sso;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class SsoConfigParserTest {

    private static final Predicate<String> TENANTS = Set.of("acme", "mhus", "other")::contains;

    private static final String PROVIDER = """
            providers:
              google:
                type: google
                clientId: "id-1.apps.googleusercontent.com"
                clientSecret: "{{secret:vault:sso.google.clientSecret}}"
            """;

    @Test
    void parsesProviderDomainAndConsumerAccounts() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - domain: ACME.com
                    tenant: acme
                    provider: google
                    autoCreate: true
                    allow: [Wile.Coyote, road.runner]
                    maxSessionDays: 3
                consumerAccounts:
                  provider: google
                  tenants: [mhus]
                """, TENANTS);

        SsoConfig.Provider google = config.provider("google").orElseThrow();
        assertThat(google.issuer()).isEqualTo(SsoConfigParser.GOOGLE_ISSUER);
        assertThat(google.discoveryUrl()).isEqualTo(SsoConfigParser.GOOGLE_DISCOVERY);

        SsoConfig.DomainRule rule = config.domain("acme.com").orElseThrow();
        assertThat(rule.tenant()).isEqualTo("acme");
        assertThat(rule.autoCreate()).isTrue();
        assertThat(rule.allows("wile.coyote")).isTrue();
        assertThat(rule.allows("someone")).isFalse();
        assertThat(rule.maxSession()).isEqualTo(Duration.ofDays(3));

        assertThat(config.consumerAccountsAllowed("mhus")).isTrue();
        assertThat(config.consumerAccountsAllowed("acme")).isFalse();
        assertThat(config.consumerAccounts().maxSession()).isEqualTo(SsoConfig.DEFAULT_MAX_SESSION);
    }

    @Test
    void missingAllow_meansWholeDomain() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                """, TENANTS);
        assertThat(config.domain("acme.com").orElseThrow().allows("anybody")).isTrue();
    }

    @Test
    void emptyAllow_dropsTheDomain_insteadOfOpeningIt() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google, allow: []}
                """, TENANTS);
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void plaintextSecret_dropsProvider_andItsDomains() {
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  google: {type: google, clientId: x, clientSecret: "GOCSPX-plain"}
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                """, TENANTS);
        assertThat(config.provider("google")).isEmpty();
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void unsupportedType_isDropped() {
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  entra: {type: oidc, clientId: x, clientSecret: "{{secret:vault:x}}"}
                """, TENANTS);
        assertThat(config.providers()).isEmpty();
    }

    @Test
    void gmailUnderDomains_isRefused() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: gmail.com, tenant: acme, provider: google}
                  - {domain: googlemail.com, tenant: acme, provider: google}
                """, TENANTS);
        assertThat(config.domains()).isEmpty();
    }

    @Test
    void unknownOrSystemTenant_isDropped() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: ghost, provider: google}
                  - {domain: vance.com, tenant: _vance, provider: google}
                consumerAccounts: {provider: google, tenants: [_vance]}
                """, TENANTS);
        assertThat(config.domains()).isEmpty();
        assertThat(config.consumerAccounts()).isNull();
    }

    @Test
    void domainListedTwice_neitherWins() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                  - {domain: acme.com, tenant: other, provider: google}
                  - {domain: acme.com, tenant: mhus, provider: google}
                """, TENANTS);
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void brokenYaml_disablesSso() {
        assertThat(SsoConfigParser.parse("providers: [unclosed", TENANTS)).isEqualTo(SsoConfig.EMPTY);
        assertThat(SsoConfigParser.parse("- a list", TENANTS)).isEqualTo(SsoConfig.EMPTY);
    }

    @Test
    void issuerOverride_derivesDiscovery() {
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  google:
                    type: google
                    clientId: x
                    clientSecret: "{{secret:vault:x}}"
                    issuer: "http://localhost:7777/"
                """, TENANTS);
        SsoConfig.Provider p = config.provider("google").orElseThrow();
        assertThat(p.issuer()).isEqualTo("http://localhost:7777");
        assertThat(p.discoveryUrl()).isEqualTo("http://localhost:7777/.well-known/openid-configuration");
    }

    @Test
    void preparationProvider_followsDomainRuleOrConsumerTenant() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                consumerAccounts: {provider: google, tenants: [mhus]}
                """, TENANTS);
        assertThat(config.preparationProvider("acme", "wile@acme.com")).isPresent();
        assertThat(config.preparationProvider("other", "wile@acme.com")).isEmpty();
        assertThat(config.preparationProvider("mhus", "mike@gmail.com")).isPresent();
        assertThat(config.preparationProvider("acme", "mike@gmail.com")).isEmpty();
        assertThat(config.preparationProvider("mhus", "mike@example.org")).isEmpty();
    }

    @Test
    void droppedEntries_areReportedToTheCaller() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: ghost, provider: google}
                  - {domain: ok.com, tenant: acme, provider: google}
                """, TENANTS, problems);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("acme.com").contains("ghost");
    }

    // ─── unknown keys and provider keys ────────────────────────────────

    @Test
    void misspeltAllow_dropsTheDomain_insteadOfOpeningIt() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google, alow: [wile]}
                """, TENANTS, problems);

        assertThat(config.domain("acme.com")).isEmpty();
        assertThat(problems).singleElement().asString().contains("acme.com").contains("alow");
    }

    @Test
    void unknownProviderField_dropsTheProvider_andItsDomains() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  google:
                    type: google
                    clientId: x
                    clientSecret: "{{secret:vault:k}}"
                    isuer: "http://localhost:7777"
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                """, TENANTS, problems);

        assertThat(config.provider("google")).isEmpty();
        assertThat(config.domain("acme.com")).isEmpty();
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("isuer"));
    }

    @Test
    void unknownConsumerAccountsField_dropsConsumerAccounts() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                consumerAccounts: {provider: google, tenants: [mhus], maxSessionDay: 3}
                """, TENANTS, problems);

        assertThat(config.consumerAccounts()).isNull();
        assertThat(problems).singleElement().asString().contains("maxSessionDay");
    }

    @Test
    void unknownTopLevelKey_isReported_restIsRead() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google}
                consumerAcounts: {provider: google, tenants: [mhus]}
                """, TENANTS, problems);

        assertThat(config.domain("acme.com")).isPresent();
        assertThat(config.consumerAccounts()).isNull();
        assertThat(problems).singleElement().asString().contains("consumerAcounts");
    }

    @Test
    void autoCreate_mustBeABoolean() {
        SsoConfig config = SsoConfigParser.parse(PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: acme, provider: google, autoCreate: "yes please"}
                """, TENANTS);

        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void providerKey_outsideThePathSegmentRule_isDropped() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  google_ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                  google.ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                  google-ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                """, TENANTS, problems);

        assertThat(config.providers()).containsOnlyKeys("google-ws");
        assertThat(problems).hasSize(2);
    }

    @Test
    void issuerOverride_isStoredInCanonicalForm() {
        SsoConfig config = SsoConfigParser.parse("""
                providers:
                  google: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}", issuer: "accounts.google.com"}
                """, TENANTS);

        assertThat(config.provider("google").orElseThrow().issuer()).isEqualTo(SsoConfigParser.GOOGLE_ISSUER);
    }
}
