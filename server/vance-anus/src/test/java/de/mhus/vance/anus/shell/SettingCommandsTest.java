package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.shell.SettingCommands.StorageRef;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.Map;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SettingCommandsTest {

    @Test
    void mapToStorage_tenantScope_resolvesToTenantSystemProject_andIgnoresRef() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, null);

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo(HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    @Test
    void mapToStorage_tenantScope_ignoresProvidedRef() {
        // tenant has exactly one system project per tenant; --ref makes no sense.
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, "ignored");

        assertThat(ref.id()).isEqualTo(HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    @Test
    void mapToStorage_userScope_prefixesLoginIntoHubProject() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_USER, "alice");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "alice");
    }

    @Test
    void mapToStorage_userScope_blankRefIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage(SettingService.SCOPE_USER, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope=user");
    }

    @Test
    void mapToStorage_projectScope_passesRefThrough() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_PROJECT, "literature-review");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo("literature-review");
    }

    @Test
    void mapToStorage_projectScope_blankRefIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage(SettingService.SCOPE_PROJECT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--ref");
    }

    @Test
    void mapToStorage_thinkProcessScope_passesRefThrough() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_THINK_PROCESS, "tp-42");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_THINK_PROCESS);
        assertThat(ref.id()).isEqualTo("tp-42");
    }

    @Test
    void mapToStorage_unknownScopeIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage("nonsense", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scope");
    }

    @Test
    void storageToWire_tenantSystemProject_becomesTenantScopeWithEmptyRef() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME);

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_TENANT);
        assertThat(wire.id()).isEqualTo("");
    }

    @Test
    void storageToWire_hubProject_becomesUserScopeWithLogin() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "bob");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_USER);
        assertThat(wire.id()).isEqualTo("bob");
    }

    @Test
    void storageToWire_regularProject_staysAsProject() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, "literature-review");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(wire.id()).isEqualTo("literature-review");
    }

    @Test
    void storageToWire_thinkProcess_passesThrough() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_THINK_PROCESS, "tp-7");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_THINK_PROCESS);
        assertThat(wire.id()).isEqualTo("tp-7");
    }

    // ──────────────── encrypted types ────────────────
    //
    // Every branch below used to compare against SettingType.PASSWORD, which
    // treats HIDDEN as a plaintext type: the ciphertext gets printed, the write
    // is refused deep inside SettingService, and a dry-run echoes the plaintext.
    // These pin the predicate instead of the constant.

    private static SettingDocument doc(SettingType type, @Nullable String value) {
        SettingDocument d = new SettingDocument();
        d.setType(type);
        d.setValue(value);
        return d;
    }

    @Test
    void displayValue_hiddenSetting_isMaskedLikePassword() {
        assertThat(SettingCommands.displayValue(doc(SettingType.HIDDEN, "AES-ciphertext")))
                .isEqualTo("[set]");
    }

    @Test
    void displayValue_passwordSetting_isMasked() {
        assertThat(SettingCommands.displayValue(doc(SettingType.PASSWORD, "AES-ciphertext")))
                .isEqualTo("[set]");
    }

    @Test
    void displayValue_plaintextSetting_isShown() {
        assertThat(SettingCommands.displayValue(doc(SettingType.STRING, "plain")))
                .isEqualTo("plain");
    }

    @Test
    void set_hiddenType_isRefusedWithAPointerAtSetSecret() {
        SettingService settings = mock(SettingService.class);
        SettingCommands commands = new SettingCommands(settings, emptyLineReader());

        String out = commands.set("acme", SettingService.SCOPE_TENANT, null,
                "smtp.password", "s3cret", SettingType.HIDDEN, null);

        assertThat(out).contains("set-secret").contains("HIDDEN");
        verifyNoInteractions(settings);
    }

    @Test
    void setSecret_hiddenType_writesThroughTheEncryptedPathKeepingTheType() {
        SettingService settings = mock(SettingService.class);
        when(settings.setEncryptedSecret(any(), any(), any(), any(), any(), any()))
                .thenReturn(doc(SettingType.HIDDEN, "cipher"));
        SettingCommands commands = new SettingCommands(settings, emptyLineReader());

        commands.setSecret("acme", SettingService.SCOPE_TENANT, null,
                "smtp.password", "s3cret", SettingType.HIDDEN);

        verify(settings).setEncryptedSecret(
                "acme", SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME,
                "smtp.password", "s3cret", SettingType.HIDDEN);
    }

    @Test
    void setSecret_plaintextType_isRefusedWithAPointerAtSet() {
        SettingService settings = mock(SettingService.class);
        SettingCommands commands = new SettingCommands(settings, emptyLineReader());

        String out = commands.setSecret("acme", SettingService.SCOPE_TENANT, null,
                "retries", "3", SettingType.INT);

        assertThat(out).contains("setting set");
        verifyNoInteractions(settings);
    }

    @Test
    void importYaml_hiddenEntry_isEncryptedAndKeepsItsType() {
        SettingService settings = mock(SettingService.class);
        SettingCommands commands = new SettingCommands(settings, emptyLineReader());

        SettingCommands.Outcome outcome = commands.applyOne(
                "acme", SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, null),
                "imap.password", Map.of("type", "HIDDEN", "value", "s3cret"), false);

        assertThat(outcome.applied()).isTrue();
        verify(settings).setEncryptedSecret(
                "acme", SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME,
                "imap.password", "s3cret", SettingType.HIDDEN);
        verify(settings, never()).set(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void importYaml_dryRun_neverEchoesAnEncryptedPlaintext() {
        SettingService settings = mock(SettingService.class);
        SettingCommands commands = new SettingCommands(settings, emptyLineReader());

        for (SettingType type : new SettingType[] {SettingType.PASSWORD, SettingType.HIDDEN}) {
            SettingCommands.Outcome outcome = commands.applyOne(
                    "acme", SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, null),
                    "k", Map.of("type", type.name(), "value", "s3cret"), true);

            assertThat(outcome.line()).doesNotContain("s3cret").contains("<encrypted>");
        }
        verifyNoInteractions(settings);
    }

    /** The prompt fallback is never reached in these tests — --value is always set. */
    private static ObjectProvider<LineReader> emptyLineReader() {
        @SuppressWarnings("unchecked")
        ObjectProvider<LineReader> provider = mock(ObjectProvider.class);
        return provider;
    }
}
