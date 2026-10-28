package de.mhus.vance.shared.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunnerBindingServiceTest {

    private TenantService tenants;
    private ProjectService projects;
    private RunnerBindingService service;
    private final String recipient = AgeKeys.generateKeyPair().recipient();

    @BeforeEach
    void setUp() {
        tenants = mock(TenantService.class);
        projects = mock(ProjectService.class);
        service = new RunnerBindingService(tenants, projects);
    }

    private static RunnerBinding binding(String id) {
        return RunnerBinding.builder()
                .runnerId(id)
                .url("http://" + id + ":9840")
                .recipient("age1x")
                .build();
    }

    @Test
    void theProjectsOwnRunner_winsOverTheTenants() {
        when(tenants.findByName("acme"))
                .thenReturn(Optional.of(TenantDocument.builder()
                        .name("acme")
                        .runner(binding("tenant-runner"))
                        .build()));
        when(projects.findByTenantAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("kunde-x")
                        .runner(binding("project-runner"))
                        .build()));

        RunnerBindingService.Effective effective =
                service.resolve("acme", "kunde-x").orElseThrow();

        assertThat(effective.binding().getRunnerId()).isEqualTo("project-runner");
        assertThat(effective.source()).isEqualTo(RunnerBindingService.Source.PROJECT);
    }

    @Test
    void aProjectWithoutOne_fallsBackToTheTenants() {
        when(tenants.findByName("acme"))
                .thenReturn(Optional.of(TenantDocument.builder()
                        .name("acme")
                        .runner(binding("tenant-runner"))
                        .build()));
        when(projects.findByTenantAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("kunde-x")
                        .build()));

        RunnerBindingService.Effective effective =
                service.resolve("acme", "kunde-x").orElseThrow();

        assertThat(effective.binding().getRunnerId()).isEqualTo("tenant-runner");
        assertThat(effective.source()).isEqualTo(RunnerBindingService.Source.TENANT);
    }

    @Test
    void noRunnerAnywhere_isEmpty_andNullProjectAsksTheTenantOnly() {
        when(tenants.findByName("acme"))
                .thenReturn(Optional.of(TenantDocument.builder().name("acme").build()));

        assertThat(service.resolve("acme", null)).isEmpty();
        verify(projects, never()).findByTenantAndName(any(), any());
        assertThat(service.resolve("ghost", "p")).isEmpty();
    }

    @Test
    void set_writesAValidatedBindingOnTheRightLevel() {
        service.set("acme", null, "runner-acme", "http://runner-acme.vance.svc:9840/", recipient);
        service.set("acme", "kunde-x", "runner-x", "https://r.example.test", recipient);

        ArgumentCaptor<RunnerBinding> tenantBinding = ArgumentCaptor.forClass(RunnerBinding.class);
        verify(tenants).setRunner(eq("acme"), tenantBinding.capture());
        assertThat(tenantBinding.getValue().getUrl())
                .as("trailing slash dropped")
                .isEqualTo("http://runner-acme.vance.svc:9840");
        assertThat(tenantBinding.getValue().getRecipient()).isEqualTo(recipient);
        assertThat(tenantBinding.getValue().getRegisteredAt()).isNotNull();
        verify(projects).setRunner(eq("acme"), eq("kunde-x"), any(RunnerBinding.class));
    }

    @Test
    void set_refusesWhatCouldPointTheBrainSomewhereElse() {
        for (String url : new String[] {
            "ftp://x",
            "http://",
            "runner:9840",
            "http://user:pw@host:9840",
            "http://host/?a=1",
            "http://host/#f",
            "not a url",
            ""
        }) {
            assertThatThrownBy(() -> service.set("acme", null, "r1", url, recipient))
                    .as("url '%s'", url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(tenants, never()).setRunner(any(), any());
    }

    @Test
    void set_refusesABadIdOrKey() {
        for (String id : new String[] {"", "Runner", "-x", "a_b", "x".repeat(64), "a b"}) {
            assertThatThrownBy(() -> service.set("acme", null, id, "http://h:1", recipient))
                    .as("id '%s'", id)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String key : new String[] {"", "age1short", "AGE-SECRET-KEY-1ABC", "hello"}) {
            assertThatThrownBy(() -> service.set("acme", null, "r1", "http://h:1", key))
                    .as("key '%s'", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void clear_removesOnlyWhatIsThere() {
        when(tenants.findByName("acme"))
                .thenReturn(Optional.of(TenantDocument.builder()
                        .name("acme")
                        .runner(binding("r"))
                        .build()));
        when(projects.findByTenantAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("kunde-x")
                        .build()));

        assertThat(service.clear("acme", null)).isTrue();
        verify(tenants).setRunner("acme", null);
        assertThat(service.clear("acme", "kunde-x"))
                .as("nothing set on the project")
                .isFalse();
        verify(projects, never()).setRunner(any(), any(), any());
    }
}
