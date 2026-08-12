package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The read-side deny-list. It exists because PASSWORD is readable on the
 * connector path: the type stopped being the thing that keeps a reference
 * away from a server-internal credential, so the key name has to.
 */
class SecretReferenceKeyPolicyTest {

    private final SecretReferenceKeyPolicy policy =
            new SecretReferenceKeyPolicy("ai.provider.*,vault.*");

    @Test
    void defaultPatterns_coverTheKeysCompiledCodeOwns() {
        assertThat(policy.isDenied("ai.provider.openai.apiKey")).isTrue();
        assertThat(policy.isDenied("vault.clientSecret")).isTrue();
    }

    @Test
    void connectorCredentials_areNotCovered() {
        // The whole point of PASSWORD-on-the-connector-path: an SMTP or REST
        // credential stays usable. Denying these would make the type useless.
        assertThat(policy.isDenied("smtp.password")).isFalse();
        assertThat(policy.isDenied("credentials.jira.api_token")).isFalse();
    }

    @Test
    void refusalNamesTheKey_ratherThanResolvingToEmpty() {
        // An empty substitution surfaces as an opaque downstream 401; the
        // operator has to be able to see which key was refused and why.
        assertThatThrownBy(() -> policy.requireReferenceReadable("ai.provider.openai.apiKey"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("ai.provider.openai.apiKey");

        assertThatCode(() -> policy.requireReferenceReadable("smtp.password"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPrefixPatternDoesNotMatchAShorterKey() {
        assertThat(policy.isDenied("ai.provider")).isFalse();
        assertThat(policy.isDenied("ai")).isFalse();
    }

    @Test
    void emptyConfiguration_deniesNothing() {
        // An operator can switch the guard off; that has to be an explicit,
        // visible act rather than a silent default.
        SecretReferenceKeyPolicy open = new SecretReferenceKeyPolicy("");

        assertThat(open.denyPatterns()).isEmpty();
        assertThat(open.isDenied("ai.provider.openai.apiKey")).isFalse();
    }

    @Test
    void exactPatternsMatchOnlyThemselves() {
        SecretReferenceKeyPolicy exact = new SecretReferenceKeyPolicy("office.jwtSecret");

        assertThat(exact.isDenied("office.jwtSecret")).isTrue();
        assertThat(exact.isDenied("office.jwtSecret.old")).isFalse();
    }

    @Test
    void isSeparateFromTheWriteList_soWideningOneDoesNotWidenTheOther() {
        // Same grammar, same default, different property: "an agent may not
        // write this" and "no reference may resolve this" are different
        // questions, and a shared list would couple them.
        SecretReferenceKeyPolicy read = new SecretReferenceKeyPolicy("office.jwtSecret");
        AgentSettingKeyPolicy write = new AgentSettingKeyPolicy("ai.provider.*");

        assertThat(read.isDenied("ai.provider.openai.apiKey")).isFalse();
        assertThat(write.isDenied("office.jwtSecret")).isFalse();
    }
}
