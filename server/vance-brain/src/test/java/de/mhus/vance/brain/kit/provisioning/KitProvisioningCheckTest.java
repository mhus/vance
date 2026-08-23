package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.kit.KitException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** When the periodic check reports, and when it deliberately stays quiet. */
class KitProvisioningCheckTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "sales";
    private static final String URL = "https://host.example";
    private static final String AUTHOR = "mara";

    private KitProvisioningLoader loader;
    private KitProvisioningHandlers handlers;
    private KitRecordStore recordStore;
    private MaximegalonService inboxItems;
    private KitProvisioningCheck check;

    @BeforeEach
    void setUp() {
        loader = mock(KitProvisioningLoader.class);
        handlers = mock(KitProvisioningHandlers.class);
        recordStore = mock(KitRecordStore.class);
        inboxItems = mock(MaximegalonService.class);
        check = new KitProvisioningCheck(loader, handlers, recordStore, inboxItems);
        when(loader.declaredBy(TENANT, PROJECT)).thenReturn(AUTHOR);
        when(inboxItems.listFiltered(any(), any(), any(), any())).thenReturn(List.of());
    }

    private void declare(KitProvisioningAuthority authority, String revision) {
        KitProvisioningEntry entry = new KitProvisioningEntry(
                "ode", URL, null, authority, Map.of("lang", "de"));
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of(entry));
        when(handlers.discover(TENANT, PROJECT, entry)).thenReturn(List.of(
                new DesiredKit(URL, "acme-crm", revision, authority, Map.of("lang", "de"))));
    }

    private void installedWithStamp(String revision, Map<String, Object> params) {
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(
                KitInstalledRecordDto.builder()
                        .id("k1")
                        .origin(KitOriginDto.builder()
                                .url(URL)
                                .path("acme-crm")
                                .provisioningStamp(KitProvisioningStamp.of(revision, params))
                                .build())
                        .build());
    }

    @Test
    void unchangedSource_reportsNothing() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-1");
        installedWithStamp("rev-1", Map.of("lang", "de"));

        assertThat(check.check(TENANT, PROJECT).changed()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void changedRevision_isReported() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));

        assertThat(check.check(TENANT, PROJECT).changed()).containsExactly("acme-crm");
        verify(inboxItems).create(any());
    }

    @Test
    void changedParamsWithSameRevision_isReported() {
        // The whole reason the params are folded into the stamp: a source
        // declares its revision on a call that never saw them, so without this
        // an edited params: line would never take effect.
        declare(KitProvisioningAuthority.NOTIFY, "rev-1");
        installedWithStamp("rev-1", Map.of("lang", "en"));

        assertThat(check.check(TENANT, PROJECT).changed()).containsExactly("acme-crm");
    }

    @Test
    void paramOrderDoesNotCountAsChange() {
        KitProvisioningEntry entry = new KitProvisioningEntry("ode", URL, null,
                KitProvisioningAuthority.NOTIFY,
                new java.util.LinkedHashMap<>(Map.of("b", 2, "a", 1)));
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of(entry));
        when(handlers.discover(TENANT, PROJECT, entry)).thenReturn(List.of(
                new DesiredKit(URL, "acme-crm", "rev-1", KitProvisioningAuthority.NOTIFY,
                        entry.params())));
        // Same content, written the other way round.
        java.util.Map<String, Object> reversed = new java.util.LinkedHashMap<>();
        reversed.put("a", 1);
        reversed.put("b", 2);
        installedWithStamp("rev-1", reversed);

        assertThat(check.check(TENANT, PROJECT).changed()).isEmpty();
    }

    @Test
    void sourceWithoutRevision_isNotChecked() {
        declare(KitProvisioningAuthority.NOTIFY, null);
        installedWithStamp("rev-1", Map.of("lang", "de"));

        // Nothing is checked rather than guessed: guessing would refetch every
        // tick or never.
        assertThat(check.check(TENANT, PROJECT).changed()).isEmpty();
    }

    @Test
    void recordWithoutStamp_isNotReported() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(
                KitInstalledRecordDto.builder()
                        .id("k1")
                        .origin(KitOriginDto.builder().url(URL).path("acme-crm").build())
                        .build());

        // Installed by hand or before the stamp existed. Announcing a divergence
        // for every such kit on the first tick after an upgrade would be noise.
        assertThat(check.check(TENANT, PROJECT).changed()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void updateAuthority_reportsNothing() {
        declare(KitProvisioningAuthority.UPDATE, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));

        // The entry already granted unattended refresh; asking a person would
        // be asking a question that was answered in the document.
        assertThat(check.check(TENANT, PROJECT).changed()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void missingKitAtNotify_isReported() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-1");
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        assertThat(check.check(TENANT, PROJECT).missing()).containsExactly("acme-crm");
    }

    @Test
    void missingKitAtManage_isNotReported() {
        declare(KitProvisioningAuthority.MANAGE, "rev-1");
        when(recordStore.findByOrigin(TENANT, PROJECT, URL, "acme-crm")).thenReturn(null);

        // The source may install it itself; the other triggers will.
        assertThat(check.check(TENANT, PROJECT).missing()).isEmpty();
    }

    @Test
    void openItemForTheSameKit_suppressesASecond() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));
        when(inboxItems.listFiltered(TENANT, List.of(AUTHOR), MaximegalonStatus.PENDING,
                KitProvisioningCheck.TAG))
                .thenReturn(List.of(MaximegalonDocument.builder()
                        .effectRef(PROJECT + "|" + URL + "|acme-crm")
                        .build()));

        // Six unanswered notices a day are a reason to switch the feature off.
        KitProvisioningCheck.Report report = check.check(TENANT, PROJECT);
        assertThat(report.changed()).containsExactly("acme-crm");
        assertThat(report.reported()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void openItemForAnotherKit_doesNotSuppress() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));
        when(inboxItems.listFiltered(any(), any(), any(), any()))
                .thenReturn(List.of(MaximegalonDocument.builder()
                        .effectRef(PROJECT + "|" + URL + "|other-kit")
                        .build()));

        assertThat(check.check(TENANT, PROJECT).reported()).containsExactly("acme-crm");
    }

    @Test
    void itemCarriesTagRefAndAssignee() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));

        check.check(TENANT, PROJECT);

        ArgumentCaptor<MaximegalonDocument> item =
                ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItems).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo(AUTHOR);
        assertThat(item.getValue().getTags()).containsExactly(KitProvisioningCheck.TAG);
        assertThat(item.getValue().getEffectRef()).isEqualTo(PROJECT + "|" + URL + "|acme-crm");
        assertThat(item.getValue().isRequiresAction()).isTrue();
        assertThat(item.getValue().getPayload()).containsEntry("kitPath", "acme-crm");
    }

    @Test
    void withoutAnAuthor_nothingIsCreated() {
        declare(KitProvisioningAuthority.NOTIFY, "rev-2");
        installedWithStamp("rev-1", Map.of("lang", "de"));
        when(loader.declaredBy(TENANT, PROJECT)).thenReturn(null);

        KitProvisioningCheck.Report report = check.check(TENANT, PROJECT);
        assertThat(report.changed()).containsExactly("acme-crm");
        assertThat(report.reported()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void unreachableHost_isNotADivergence() {
        KitProvisioningEntry entry = new KitProvisioningEntry(
                "ode", URL, null, KitProvisioningAuthority.NOTIFY, Map.of());
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of(entry));
        when(handlers.discover(TENANT, PROJECT, entry))
                .thenThrow(new KitException("not reachable"));

        // Reporting it would fill an inbox with notices about somebody else's
        // outage.
        KitProvisioningCheck.Report report = check.check(TENANT, PROJECT);
        assertThat(report.changed()).isEmpty();
        assertThat(report.missing()).isEmpty();
        verify(inboxItems, never()).create(any());
    }

    @Test
    void noProvisioningDocument_costsNothing() {
        when(loader.load(TENANT, PROJECT)).thenReturn(List.of());

        check.check(TENANT, PROJECT);

        verify(handlers, never()).discover(any(), any(), any());
        verify(loader, never()).declaredBy(any(), any());
    }
}
