package de.mhus.vance.shared.sso;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SsoConfigParserTest {

    private static final String TENANT = "acme";

    private static final String PROVIDER = """
            providers:
              google:
                type: google
                clientId: "id-1.apps.googleusercontent.com"
                clientSecret: "{{secret:vault:sso.google.clientSecret}}"
            """;

    @Test
    void parsesProviderDomainAndConsumerAccounts() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - domain: ACME.com
                    provider: google
                    autoCreate: true
                    allow: [Wile.Coyote, road.runner]
                    maxSessionDays: 3
                consumerAccounts:
                  provider: google
                """);

        SsoConfig.Provider google = config.provider("google").orElseThrow();
        assertThat(google.issuer()).isEqualTo(SsoConfigParser.GOOGLE_ISSUER);
        assertThat(google.discoveryUrl()).isEqualTo(SsoConfigParser.GOOGLE_DISCOVERY);

        SsoConfig.DomainRule rule = config.domain("acme.com").orElseThrow();
        assertThat(config.tenant()).isEqualTo("acme");
        assertThat(rule.autoCreate()).isTrue();
        assertThat(rule.allows("wile.coyote")).isTrue();
        assertThat(rule.allows("someone")).isFalse();
        assertThat(rule.maxSession()).isEqualTo(Duration.ofDays(3));

        assertThat(config.consumerAccountsAllowed()).isTrue();
        assertThat(config.consumerAccounts().maxSession()).isEqualTo(SsoConfig.DEFAULT_MAX_SESSION);
    }

    @Test
    void missingAllow_meansWholeDomain() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google}
                """);
        assertThat(config.domain("acme.com").orElseThrow().allows("anybody")).isTrue();
    }

    @Test
    void emptyAllow_dropsTheDomain_insteadOfOpeningIt() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google, allow: []}
                """);
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void plaintextSecret_dropsProvider_andItsDomains() {
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  google: {type: google, clientId: x, clientSecret: "GOCSPX-plain"}
                domains:
                  - {domain: acme.com, provider: google}
                """);
        assertThat(config.provider("google")).isEmpty();
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void unsupportedType_isDropped() {
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  entra: {type: oidc, clientId: x, clientSecret: "{{secret:vault:x}}"}
                """);
        assertThat(config.providers()).isEmpty();
    }

    @Test
    void gmailUnderDomains_isRefused() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: gmail.com, provider: google}
                  - {domain: googlemail.com, provider: google}
                """);
        assertThat(config.domains()).isEmpty();
    }

    @Test
    void tenantKeys_areRefused_theFileNamesTheTenant() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, tenant: other, provider: google}
                consumerAccounts: {provider: google, tenants: [other]}
                """, problems);

        assertThat(config.domains()).isEmpty();
        assertThat(config.consumerAccounts()).isNull();
        assertThat(problems).hasSize(2).allSatisfy(p -> assertThat(p).contains("unknown key"));
    }

    @Test
    void domainListedTwice_neitherWins() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google}
                  - {domain: acme.com, provider: google}
                  - {domain: acme.com, provider: google}
                """);
        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void brokenYaml_disablesSso() {
        assertThat(SsoConfigParser.parse(TENANT, "providers: [unclosed")).isEqualTo(SsoConfig.empty(TENANT));
        assertThat(SsoConfigParser.parse(TENANT, "- a list")).isEqualTo(SsoConfig.empty(TENANT));
    }

    @Test
    void issuerOverride_derivesDiscovery() {
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  google:
                    type: google
                    clientId: x
                    clientSecret: "{{secret:vault:x}}"
                    issuer: "http://localhost:7777/"
                """);
        SsoConfig.Provider p = config.provider("google").orElseThrow();
        assertThat(p.issuer()).isEqualTo("http://localhost:7777");
        assertThat(p.discoveryUrl()).isEqualTo("http://localhost:7777/.well-known/openid-configuration");
    }

    @Test
    void preparationProvider_followsDomainRuleOrConsumerAccounts() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google}
                consumerAccounts: {provider: google}
                """);
        assertThat(config.preparationProvider("wile@acme.com")).isPresent();
        assertThat(config.preparationProvider("mike@gmail.com")).isPresent();
        assertThat(config.preparationProvider("mike@example.org")).isEmpty();

        SsoConfig workOnly = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google}
                """);
        assertThat(workOnly.preparationProvider("mike@gmail.com")).isEmpty();
    }

    @Test
    void droppedEntries_areReportedToTheCaller() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: ghost}
                  - {domain: ok.com, provider: google}
                """, problems);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("acme.com").contains("ghost");
    }

    // ─── unknown keys and provider keys ────────────────────────────────

    @Test
    void misspeltAllow_dropsTheDomain_insteadOfOpeningIt() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google, alow: [wile]}
                """, problems);

        assertThat(config.domain("acme.com")).isEmpty();
        assertThat(problems).singleElement().asString().contains("acme.com").contains("alow");
    }

    @Test
    void unknownProviderField_dropsTheProvider_andItsDomains() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  google:
                    type: google
                    clientId: x
                    clientSecret: "{{secret:vault:k}}"
                    isuer: "http://localhost:7777"
                domains:
                  - {domain: acme.com, provider: google}
                """, problems);

        assertThat(config.provider("google")).isEmpty();
        assertThat(config.domain("acme.com")).isEmpty();
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("isuer"));
    }

    @Test
    void unknownConsumerAccountsField_dropsConsumerAccounts() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                consumerAccounts: {provider: google, maxSessionDay: 3}
                """, problems);

        assertThat(config.consumerAccounts()).isNull();
        assertThat(problems).singleElement().asString().contains("maxSessionDay");
    }

    @Test
    void unknownTopLevelKey_isReported_restIsRead() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google}
                consumerAcounts: {provider: google}
                """, problems);

        assertThat(config.domain("acme.com")).isPresent();
        assertThat(config.consumerAccounts()).isNull();
        assertThat(problems).singleElement().asString().contains("consumerAcounts");
    }

    @Test
    void autoCreate_mustBeABoolean() {
        SsoConfig config = SsoConfigParser.parse(TENANT, PROVIDER + """
                domains:
                  - {domain: acme.com, provider: google, autoCreate: "yes please"}
                """);

        assertThat(config.domain("acme.com")).isEmpty();
    }

    @Test
    void providerKey_outsideThePathSegmentRule_isDropped() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  google_ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                  google.ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                  google-ws: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
                """, problems);

        assertThat(config.providers()).containsOnlyKeys("google-ws");
        assertThat(problems).hasSize(2);
    }

    @Test
    void issuerOverride_isStoredInCanonicalForm() {
        SsoConfig config = SsoConfigParser.parse(TENANT, """
                providers:
                  google: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}", issuer: "accounts.google.com"}
                """);

        assertThat(config.provider("google").orElseThrow().issuer()).isEqualTo(SsoConfigParser.GOOGLE_ISSUER);
    }
}
