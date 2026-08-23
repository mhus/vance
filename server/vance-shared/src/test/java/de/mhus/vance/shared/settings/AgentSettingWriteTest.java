package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The three agent-write rules from {@code planning/setting-type-hidden.md} §6:
 * an agent-originated secret write never overwrites a PASSWORD setting (W1),
 * always lands as HIDDEN (W2), and never touches a reserved key (W3).
 */
class AgentSettingWriteTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = SettingService.SCOPE_PROJECT;
    private static final String REF = "instant-hole";

    private final SettingRepository repository = mock(SettingRepository.class);
    private final AesEncryptionService encryption =
            new AesEncryptionService("unit-test-master-key");

    private SettingService serviceWith(String denyKeys) {
        SettingService s = new SettingService(repository, mock(MongoTemplate.class),
                encryption, mock(AuditService.class),
                megadodoProvider(), new AgentSettingKeyPolicy(denyKeys));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    // ─────── the type follows the use, not the origin ───────

    @Test
    void a_connector_credential_stays_password_even_though_an_agent_wrote_it() {
        // The whole point of PASSWORD: a connector can use it, agents and scripts
        // cannot read it back. A value having passed through the model context
        // once is no reason to weaken it forever.
        SettingService service = serviceWith("");
        stub("smtp.password", null);

        SettingDocument saved = service.setAgentSecret(
                TENANT, PROJECT, REF, "smtp.password", "s3cr3t", SettingType.PASSWORD);

        assertThat(saved.getType()).isEqualTo(SettingType.PASSWORD);
        assertThat(saved.getValue()).isNotNull().isNotEqualTo("s3cr3t");
    }

    @Test
    void a_secret_a_script_has_to_resolve_is_written_hidden() {
        SettingService service = serviceWith("");
        stub("deploy-token", null);

        assertThat(service.setAgentSecret(
                TENANT, PROJECT, REF, "deploy-token", "s3cr3t", SettingType.HIDDEN).getType())
                .isEqualTo(SettingType.HIDDEN);
    }

    @Test
    void an_existing_hidden_secret_may_be_overwritten() {
        SettingService service = serviceWith("");
        stub("deploy-token", doc(SettingType.HIDDEN, encryption.encrypt("old")));

        assertThat(service.setAgentSecret(
                TENANT, PROJECT, REF, "deploy-token", "new", SettingType.HIDDEN).getType())
                .isEqualTo(SettingType.HIDDEN);
    }

    // ─────── W1 — no overwrite of a PASSWORD setting ───────

    @Test
    void an_existing_password_setting_is_never_overwritten_by_an_agent() {
        SettingService service = serviceWith("");
        stub("smtp.password", doc(SettingType.PASSWORD, encryption.encrypt("real-credential")));

        assertThatThrownBy(() -> service.setAgentSecret(
                TENANT, PROJECT, REF, "smtp.password", "agent-value", SettingType.PASSWORD))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("cannot be overwritten by an agent");
    }

    @Test
    void the_human_write_path_may_still_write_that_same_key() {
        // W1 constrains the agent, not the operator: setEncryptedSecret carries no
        // agent restriction, which is what the USER-origin paths use.
        SettingService service = serviceWith("");
        stub("smtp.password", doc(SettingType.PASSWORD, encryption.encrypt("real-credential")));

        assertThat(service.setEncryptedSecret(
                TENANT, PROJECT, REF, "smtp.password", "new", SettingType.PASSWORD).getType())
                .isEqualTo(SettingType.PASSWORD);
    }

    // ─────── W3 — reserved keys ───────

    @Test
    void a_deny_listed_key_is_refused_even_when_it_does_not_exist_yet() {
        // The gap W1+W2 leave: creating it fresh as HIDDEN would make an
        // infrastructure credential permanently agent-readable.
        SettingService service = serviceWith("ai.provider.*,vault.*");
        stub("ai.provider.default.apiKey", null);

        assertThatThrownBy(() -> service.setAgentSecret(
                TENANT, PROJECT, REF, "ai.provider.default.apiKey", "sk-agent-chosen",
                SettingType.PASSWORD))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("reserved for operator configuration");
    }

    @Test
    void a_key_outside_the_deny_list_is_written_normally() {
        SettingService service = serviceWith("ai.provider.*,vault.*");
        stub("credentials.jira.api_token", null);

        assertThat(service.setAgentSecret(
                TENANT, PROJECT, REF, "credentials.jira.api_token", "tok",
                SettingType.PASSWORD).getType())
                .isEqualTo(SettingType.PASSWORD);
    }

    // ─────── the deny-list grammar ───────

    @Test
    void deny_patterns_match_exact_keys_and_trailing_wildcards_only() {
        AgentSettingKeyPolicy policy =
                new AgentSettingKeyPolicy(" ai.provider.* , vault.clientSecret ");

        assertThat(policy.denyPatterns()).containsExactly("ai.provider.*", "vault.clientSecret");
        assertThat(policy.isDenied("ai.provider.default.apiKey")).isTrue();
        assertThat(policy.isDenied("ai.provider.")).isTrue();
        assertThat(policy.isDenied("vault.clientSecret")).isTrue();
        // Exact pattern must not match a prefix of a longer key.
        assertThat(policy.isDenied("vault.clientSecretBackup")).isFalse();
        // A similar-looking namespace must not be swept in.
        assertThat(policy.isDenied("ai.providers.x")).isFalse();
        assertThat(policy.isDenied("smtp.password")).isFalse();
    }

    @Test
    void an_empty_deny_list_denies_nothing() {
        AgentSettingKeyPolicy policy = new AgentSettingKeyPolicy("   ");

        assertThat(policy.denyPatterns()).isEmpty();
        assertThat(policy.isDenied("ai.provider.default.apiKey")).isFalse();
    }

    // ─────── helpers ───────

    private void stub(String key, @Nullable SettingDocument doc) {
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                TENANT, PROJECT, REF, key))
                .thenReturn(Optional.ofNullable(doc));
    }

    private static SettingDocument doc(SettingType type, String value) {
        SettingDocument d = new SettingDocument();
        d.setTenantId(TENANT);
        d.setType(type);
        d.setValue(value);
        return d;
    }

    /** Lazy provider stand-in — SettingService resolves Megadodo on demand. */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.shared.megadodo.MegadodoService> megadodoProvider() {
        org.springframework.beans.factory.ObjectProvider<
                de.mhus.vance.shared.megadodo.MegadodoService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject())
                .thenReturn(mock(de.mhus.vance.shared.megadodo.MegadodoService.class));
        return provider;
    }

}
