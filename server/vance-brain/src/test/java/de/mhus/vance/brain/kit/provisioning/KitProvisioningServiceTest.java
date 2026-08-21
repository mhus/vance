package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.shared.kit.KitException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** What one provisioning run does, and what it deliberately does not. */
class KitProvisioningServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "sales";
    private static final String URL = "https://host.example";

    private KitProvisioningLoader loader;
    private KitProvisioningHandlers handlers;
    private KitRecordStore recordStore;
    private KitService kitService;
    private KitProvisioningService service;

    @BeforeEach
    void setUp() {
        loader = mock(KitProvisioningLoader.class);
        handlers = mock(KitProvisioningHandlers.class);
        recordStore = mock(KitRecordStore.class);
        kitService = mock(KitService.class);
        service = new KitProvisioningService(loader, handlers, recordStore, kitService);
    }

    private static KitProvisioningEntry entry(KitProvisioningAuthority authority) {
        return new KitProvisioningEntry("ode", URL, "s3cr3t", authority, Map.of("lang", "de"));
    }

    private static DesiredKit desired(KitProvisioningAuthority authority) {
        return new DesiredKit(URL, "acme-crm", "abc123", authority, Map.of("lang", "de"));
    }

    private void declare(KitProvisioningAuthority authority, DesiredKit... kits) {
        KitProvisioningEntry entry = entry(authority);
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of(entry));
        when(handlers.discover(TENANT, PROJECT, entry)).thenReturn(List.of(kits));
    }

    @Test
    void provision_noDocument_doesNothing() {
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(service.provision(TENANT, PROJECT).installed()).isEmpty();
        verify(handlers, never()).discover(any(), any(), any());
        verify(kitService, never()).install(any(), any(), any(), any());
    }

    @Test
    void provision_missingKitWithManage_installsIt() {
        declare(KitProvisioningAuthority.MANAGE, desired(KitProvisioningAuthority.MANAGE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        assertThat(service.provision(TENANT, PROJECT).installed()).containsExactly("acme-crm");
    }

    @Test
    void provision_passesTokenAndParamsIntoTheInstall() {
        declare(KitProvisioningAuthority.MANAGE, desired(KitProvisioningAuthority.MANAGE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        service.provision(TENANT, PROJECT);

        ArgumentCaptor<KitImportRequestDto> request =
                ArgumentCaptor.forClass(KitImportRequestDto.class);
        verify(kitService).install(eq(TENANT), request.capture(),
                eq(KitProvisioningService.ACTOR), eq(SettingWriteOrigin.USER));
        assertThat(request.getValue().getToken()).isEqualTo("s3cr3t");
        assertThat(request.getValue().getParams()).containsEntry("lang", "de");
        assertThat(request.getValue().getSource().getUrl()).isEqualTo(URL);
        assertThat(request.getValue().getSource().getPath()).isEqualTo("acme-crm");
    }

    @Test
    void provision_missingKitWithoutManage_withholdsIt() {
        declare(KitProvisioningAuthority.UPDATE, desired(KitProvisioningAuthority.UPDATE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        // UPDATE means "refresh what is here", not "add what is not".
        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.withheld()).containsExactly("acme-crm");
        assertThat(outcome.installed()).isEmpty();
        verify(kitService, never()).install(any(), any(), any(), any());
    }

    /** A record whose stamp was written for {@code revision} and the entry's params. */
    private static KitInstalledRecordDto recordStamped(String revision) {
        return KitInstalledRecordDto.builder()
                .id("k1")
                .origin(KitOriginDto.builder()
                        .url(URL)
                        .path("acme-crm")
                        .provisioningStamp(
                                KitProvisioningStamp.of(revision, Map.of("lang", "de")))
                        .build())
                .build();
    }

    @Test
    void provision_installedAndUnchanged_isLeftAlone() {
        declare(KitProvisioningAuthority.MANAGE, desired(KitProvisioningAuthority.MANAGE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm"))
                .thenReturn(recordStamped("abc123"));

        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.alreadyPresent()).containsExactly("acme-crm");
        assertThat(outcome.updated()).isEmpty();
        verify(kitService, never()).install(any(), any(), any(), any());
        verify(kitService, never()).update(any(), any(), any(), any());
    }

    @Test
    void provision_changedSourceWithUpdateAuthority_refreshesIt() {
        declare(KitProvisioningAuthority.UPDATE, desired(KitProvisioningAuthority.UPDATE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm"))
                .thenReturn(recordStamped("older"));

        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.updated()).containsExactly("acme-crm");
        // update, not install: the request carries the params and the new stamp,
        // which updateInstalled would rebuild from the record and lose.
        verify(kitService).update(any(), any(), any(), any());
        verify(kitService, never()).install(any(), any(), any(), any());
    }

    @Test
    void provision_changedSourceAtNotify_isLeftForTheCheck() {
        declare(KitProvisioningAuthority.NOTIFY, desired(KitProvisioningAuthority.NOTIFY));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm"))
                .thenReturn(recordStamped("older"));

        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.updated()).isEmpty();
        assertThat(outcome.alreadyPresent()).containsExactly("acme-crm");
        verify(kitService, never()).update(any(), any(), any(), any());
    }

    @Test
    void provision_recordWithoutStamp_isNotRefreshed() {
        declare(KitProvisioningAuthority.MANAGE, desired(KitProvisioningAuthority.MANAGE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm"))
                .thenReturn(KitInstalledRecordDto.builder().id("k1").build());

        // Nothing is known, so nothing is done — the same answer the check
        // gives, which is the point of sharing the rule.
        assertThat(service.provision(TENANT, PROJECT).updated()).isEmpty();
        verify(kitService, never()).update(any(), any(), any(), any());
    }

    @Test
    void provision_updateCarriesTokenParamsAndTheNewStamp() {
        declare(KitProvisioningAuthority.UPDATE, desired(KitProvisioningAuthority.UPDATE));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm"))
                .thenReturn(recordStamped("older"));

        service.provision(TENANT, PROJECT);

        ArgumentCaptor<KitImportRequestDto> request =
                ArgumentCaptor.forClass(KitImportRequestDto.class);
        verify(kitService).update(eq(TENANT), request.capture(),
                eq(KitProvisioningService.ACTOR), eq(SettingWriteOrigin.USER));
        assertThat(request.getValue().getToken()).isEqualTo("s3cr3t");
        assertThat(request.getValue().getParams()).containsEntry("lang", "de");
        assertThat(request.getValue().getProvisioningStamp())
                .isEqualTo(KitProvisioningStamp.of("abc123", Map.of("lang", "de")));
    }

    @Test
    void provision_brokenDocument_isReportedNotThrown() {
        when(loader.load(TENANT, PROJECT)).thenThrow(new KitException("bad yaml"));

        // Runs from a lifecycle event; failing loudly would take a project's
        // startup with it.
        assertThat(service.provision(TENANT, PROJECT).failures())
                .singleElement().asString().contains("bad yaml");
    }

    @Test
    void provision_unreachableEntry_doesNotCostTheOtherEntries() {
        KitProvisioningEntry dead = new KitProvisioningEntry(
                "ode", "https://dead.example", null, KitProvisioningAuthority.MANAGE, Map.of());
        KitProvisioningEntry alive = entry(KitProvisioningAuthority.MANAGE);
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of(dead, alive));
        when(handlers.discover(TENANT, PROJECT, dead))
                .thenThrow(new KitException("not reachable"));
        when(handlers.discover(TENANT, PROJECT, alive))
                .thenReturn(List.of(desired(KitProvisioningAuthority.MANAGE)));
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.installed()).containsExactly("acme-crm");
        assertThat(outcome.failures()).hasSize(1);
    }

    @Test
    void provision_oneFailingKit_doesNotCostTheOthers() {
        declare(KitProvisioningAuthority.MANAGE,
                new DesiredKit(URL, "broken", "r1", KitProvisioningAuthority.MANAGE, Map.of()),
                desired(KitProvisioningAuthority.MANAGE));
        when(recordStore.findByOrigin(any(), any(), any(), any())).thenReturn(null);
        when(kitService.install(any(), any(), any(), any()))
                .thenThrow(new KitException("boom"))
                .thenReturn(null);

        KitProvisioningService.Outcome outcome = service.provision(TENANT, PROJECT);
        assertThat(outcome.installed()).containsExactly("acme-crm");
        assertThat(outcome.failures()).singleElement().asString().contains("broken");
    }

    @Test
    void provisionCoalesced_runsOnce() {
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of());

        service.provisionCoalesced(TENANT, PROJECT);

        verify(loader).load(TENANT, PROJECT);
    }

    @Test
    void provisionCoalesced_requestDuringARun_causesExactlyOneRerun() {
        // A request that arrives while a run is going must not be dropped: the
        // running one may have read the document before the edit, and then the
        // edit would only take effect at the next tick.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(loader.load(TENANT, PROJECT)).thenAnswer(i -> {
            if (calls.incrementAndGet() == 1) {
                service.provisionCoalesced(TENANT, PROJECT);
            }
            return List.of();
        });

        service.provisionCoalesced(TENANT, PROJECT);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void provisionCoalesced_twoRequestsDuringARun_collapseIntoOneRerun() {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(loader.load(TENANT, PROJECT)).thenAnswer(i -> {
            if (calls.incrementAndGet() == 1) {
                // Both fold into the same pending rerun rather than queueing.
                service.provisionCoalesced(TENANT, PROJECT);
                service.provisionCoalesced(TENANT, PROJECT);
            }
            return List.of();
        });

        service.provisionCoalesced(TENANT, PROJECT);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void provision_neverUninstalls() {
        // Additive only: a line removed from the document leaves what it
        // installed in place. Uninstall is a verb somebody types on purpose.
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of());

        service.provision(TENANT, PROJECT);

        verify(kitService, never()).uninstall(any(), any(), any(), anyBoolean());
    }
}
