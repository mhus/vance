package de.mhus.vance.shared.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SsoConfigServiceTest {

    private static final String YAML = """
            providers:
              google: {type: google, clientId: x, clientSecret: "{{secret:vault:k}}"}
            domains:
              - {domain: acme.com, provider: google}
            """;

    private DocumentService documents;
    private TenantService tenants;
    private SsoConfigService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        tenants = mock(TenantService.class);
        service = new SsoConfigService(documents, tenants, Clock.systemUTC());
        TenantDocument acme = mock(TenantDocument.class);
        when(acme.isEnabled()).thenReturn(true);
        when(tenants.findByName("acme")).thenReturn(Optional.of(acme));
        DocumentDocument doc = mock(DocumentDocument.class);
        when(documents.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documents.findByPath(TenantService.SYSTEM_TENANT, "_tenant", "_vance/config/sso/acme.yaml"))
                .thenReturn(Optional.of(doc));
        when(documents.readContent(doc)).thenReturn(YAML);
    }

    @Test
    void readsTheFileOfTheTenant_onDemand() {
        SsoConfig config = service.config("acme");

        assertThat(config.tenant()).isEqualTo("acme");
        assertThat(config.domain("acme.com")).isPresent();
    }

    @Test
    void tenantWithoutFile_hasNoSso() {
        TenantDocument other = mock(TenantDocument.class);
        when(other.isEnabled()).thenReturn(true);
        when(tenants.findByName("other")).thenReturn(Optional.of(other));

        assertThat(service.config("other").offersSignIn()).isFalse();
    }

    @Test
    void unknownDisabledOrSystemTenant_isEmpty_andNeverReadsAFile() {
        TenantDocument off = mock(TenantDocument.class);
        when(off.isEnabled()).thenReturn(false);
        when(tenants.findByName("off")).thenReturn(Optional.of(off));

        assertThat(service.config("ghost").offersSignIn()).isFalse();
        assertThat(service.config("off").offersSignIn()).isFalse();
        assertThat(service.config(TenantService.SYSTEM_TENANT).offersSignIn()).isFalse();
        assertThat(service.config("../acme").offersSignIn()).isFalse();
        verify(documents, never()).findByPath(any(), any(), org.mockito.ArgumentMatchers.contains("ghost"));
    }

    @Test
    void tenantOfPath_recognisesOnlyTheTenantFiles() {
        assertThat(SsoConfigService.tenantOfPath("_vance/config/sso/acme.yaml")).contains("acme");
        assertThat(SsoConfigService.tenantOfPath("_vance/config/sso/a/b.yaml")).isEmpty();
        assertThat(SsoConfigService.tenantOfPath("_vance/config/sso.yaml")).isEmpty();
        assertThat(SsoConfigService.tenantOfPath("_vance/config/sso/acme.md")).isEmpty();
    }
}
