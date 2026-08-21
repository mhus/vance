package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a kit may and may not set, and how the three lists relate. */
class KitSettingKeyPolicyTest {

    private static KitSettingKeyPolicy withDefaults() {
        return new KitSettingKeyPolicy("ai.provider.*,vault.*");
    }

    @Test
    void providerConfig_isDenied() {
        // A kit that could set this would redirect the project's model traffic.
        assertThat(withDefaults().isDenied("ai.provider.openai.apiKey")).isTrue();
        assertThat(withDefaults().isDenied("ai.provider.openai.baseUrl")).isTrue();
    }

    @Test
    void vaultBinding_isDenied() {
        assertThat(withDefaults().isDenied("vault.type")).isTrue();
    }

    @Test
    void ordinarySettings_arePermitted() {
        // The point of a kit is to bring settings; only the two reserved
        // families are out of reach.
        assertThat(withDefaults().isDenied("crm.baseUrl")).isFalse();
        assertThat(withDefaults().isDenied("chat.language")).isFalse();
        assertThat(withDefaults().isDenied("ai.default.model")).isFalse();
    }

    @Test
    void kitTokenIsNotOnThisList() {
        // It is on the agent-write list instead: a kit setting kit.token.* would
        // be odd but not a privilege escalation, whereas an agent writing it
        // would choose where a project gets its tool definitions.
        assertThat(withDefaults().isDenied("kit.token.acme")).isFalse();
        assertThat(new AgentSettingKeyPolicy("ai.provider.*,vault.*,store.*,kit.*")
                .isDenied("kit.token.acme")).isTrue();
    }

    @Test
    void kitTokenStaysResolvableThroughAReference() {
        // Must stay so: the provisioning document resolves exactly this key
        // through {{secret:project:kit.token.<id>}}. Denying it on the read side
        // would break the feature that introduced it — which is why the write
        // list and the reference list are separate.
        assertThat(new SecretReferenceKeyPolicy("ai.provider.*,vault.*")
                .isDenied("kit.token.acme")).isFalse();
    }

    @Test
    void emptyConfiguration_deniesNothing() {
        // An operator who trusts kits in their deployment shortens the list;
        // that is the only override there is (see the class javadoc).
        assertThat(new KitSettingKeyPolicy("").isDenied("ai.provider.openai.apiKey")).isFalse();
    }
}
