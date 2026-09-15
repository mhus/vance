package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for the headless agent mode's AI-settings semantics — the
 * review finding behind {@link SetupState#isHeadless()}: keys the config
 * states ({@code ai.default.provider}/{@code ai.default.model}) are
 * re-asserted on every run, while the derived keys the config never names
 * ({@code ai.alias.default.*}, embedding wiring) are bootstrapped only
 * while absent so an operator's Web-UI split survives config re-runs.
 */
class SetupWizardHeadlessTest {

    private static final String CONFIG_EXISTING = """
            tenant:
              name: acme
            user:
              name: admin
            ai:
              provider: gemini
              model: gemini-2.5-flash
            """;

    /** Fresh tenant + fresh user: password and api-key are required then. */
    private static final String CONFIG_FRESH = """
            tenant:
              name: acme
            user:
              name: admin
              password: Vancy-2026!
            ai:
              provider: gemini
              model: gemini-2.5-flash
              api-key: sk-test
            """;

    @TempDir
    Path tempDir;

    private final TenantService tenantService = mock(TenantService.class);
    private final UserService userService = mock(UserService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final HomeBootstrapService homeBootstrapService = mock(HomeBootstrapService.class);

    private SetupWizard wizard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(homeBootstrapService.ensureTenantProject(any()))
                .thenReturn(mock(de.mhus.vance.shared.project.ProjectDocument.class));
        wizard = new SetupWizard(
                tenantService,
                userService,
                mock(PasswordService.class),
                mock(PasswordPolicyService.class),
                settingService,
                documentService,
                homeBootstrapService,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
    }

    @Test
    void headlessReRun_derivedKeysAreKept_configStatedKeysReasserted() throws Exception {
        TenantDocument tenant = mock(TenantDocument.class);
        when(tenant.getTitle()).thenReturn("Acme");
        when(tenantService.findByName("acme")).thenReturn(Optional.of(tenant));
        UserDocument user = mock(UserDocument.class);
        when(userService.findByTenantAndName("acme", "admin")).thenReturn(Optional.of(user));
        // Every derived key already holds a value — an operator tuned the
        // tiers in the Web-UI after the first setup run.
        when(settingService.getStringValue(eq("acme"), any(), any(), any())).thenReturn("operator-choice");
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.of(mock(de.mhus.vance.shared.document.DocumentDocument.class)));

        assertThat(wizard.runHeadless(configFile(CONFIG_EXISTING), false)).isZero();

        // config-stated keys: declarative, re-asserted every run
        assertThat(stringKeysWritten()).contains("ai.default.provider", "ai.default.model");
        // derived keys: never re-asserted while present
        assertThat(stringKeysWritten())
                .doesNotContain(
                        "ai.alias.default.fast",
                        "ai.alias.default.analyze",
                        "ai.alias.default.deep",
                        "ai.alias.default.web",
                        "ai.alias.default.code",
                        "ai.embedding.provider");
        // no api key in the config → the existing one is kept, not overwritten
        verify(settingService, never()).setEncryptedPassword(any(), any(), any(), any(), any());
    }

    @Test
    void headlessFirstRun_derivedKeysAreBootstrapped() throws Exception {
        when(tenantService.findByName("acme")).thenReturn(Optional.empty());
        when(userService.findByTenantAndName("acme", "admin")).thenReturn(Optional.empty());
        when(settingService.getStringValue(eq("acme"), any(), any(), any())).thenReturn(null);
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.of(mock(de.mhus.vance.shared.document.DocumentDocument.class)));

        int exit = wizard.runHeadless(configFile(CONFIG_FRESH), false);

        assertThat(exit).isZero();
        // fresh tenant: the derived tiers are bootstrapped to the config model
        assertThat(stringKeysWritten())
                .contains(
                        "ai.alias.default.fast",
                        "ai.alias.default.analyze",
                        "ai.alias.default.deep",
                        "ai.alias.default.web",
                        "ai.alias.default.code",
                        "ai.embedding.provider");
        verify(settingService)
                .set(
                        eq("acme"),
                        eq(SettingService.SCOPE_PROJECT),
                        eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                        eq("ai.alias.default.fast"),
                        eq("gemini:gemini-2.5-flash"),
                        any(),
                        any());
    }

    // ---- helpers -------------------------------------------------------

    /** All keys the save path wrote through {@code SettingService.set}. */
    private List<String> stringKeysWritten() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        Mockito.verify(settingService, Mockito.atLeastOnce())
                .set(any(), any(), any(), keys.capture(), any(), any(), any());
        return keys.getAllValues();
    }

    private String configFile(String yaml) throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file.toString();
    }
}
