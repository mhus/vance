package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.AppTarget;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntake;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntakeContext;
import de.mhus.vance.brain.applications.VanceApplication.TargetPurpose;
import de.mhus.vance.brain.applications.VanceApplication.TargetsContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The share side of the links app: which places it offers, and where an
 * incoming link actually lands.
 *
 * <p>The group is the reason the flat share list exists — when the app handler
 * became one-for-all, group and position dropped out of this dialog as an
 * accepted loss (planning/milliways-app-handler.md §2).
 */
class LinksApplicationShareTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String FOLDER = "bookmarks";

    private LinksStore store;
    private LinksManifestOps manifestOps;
    private LinksApplication app;

    @BeforeEach
    void setUp() {
        store = mock(LinksStore.class);
        manifestOps = mock(LinksManifestOps.class);
        app = new LinksApplication(store, mock(de.mhus.vance.brain.tools.document.DocumentLinkBuilder.class),
                manifestOps);
    }

    // ── Which places it offers ─────────────────────────────────────

    @Test
    void targets_intake_offersTheDeclaredGroups() {
        givenGroups("Lesen", "Tools");

        assertThat(app.targets(context(TargetPurpose.INTAKE)))
                .extracting(AppTarget::handle)
                .containsExactly("Lesen", "Tools");
    }

    @Test
    void targets_navigate_offersNothing() {
        // A group is not somewhere one navigates to: the app renders every group
        // on one page, so there is no "open group" state for a link to land in.
        givenGroups("Lesen");

        assertThat(app.targets(context(TargetPurpose.NAVIGATE))).isEmpty();
    }

    @Test
    void targets_brokenManifest_isNoPlacesRatherThanAFailure() {
        // Reported where the file is edited. Here it must leave the app itself
        // a usable share target.
        when(store.load(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("unparseable"));

        assertThat(app.targets(context(TargetPurpose.INTAKE))).isEmpty();
    }

    @Test
    void targets_blankDeclaredGroupIsSkipped() {
        // A blank handle would be rejected by AppTarget; drop it before that.
        givenGroups("Lesen", "  ");

        assertThat(app.targets(context(TargetPurpose.INTAKE)))
                .extracting(AppTarget::handle).containsExactly("Lesen");
    }

    // ── Where a share lands ────────────────────────────────────────

    @Test
    void acceptShare_pickedGroup_landsThere() {
        givenGroups("Lesen", "Tools");

        app.acceptShare(shareInto("Tools"));

        assertThat(capturedFields().group()).isEqualTo("Tools");
    }

    @Test
    void acceptShare_noGroupPicked_landsInTheLeadSection() {
        givenGroups("Lesen");

        app.acceptShare(shareInto(null));

        assertThat(capturedFields().group()).isNull();
    }

    @Test
    void acceptShare_groupThatNoLongerExists_fallsBackToTheLeadSection() {
        // The group list can change between the dialog opening and the share
        // arriving. Losing the group beats losing the link.
        givenGroups("Lesen");

        app.acceptShare(shareInto("Weg"));

        assertThat(capturedFields().group()).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenGroups(String... groups) {
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "groups", List.of(groups), "entries", List.of())));
        DocumentDocument doc = new DocumentDocument();
        doc.setPath(FOLDER + "/_app.yaml");
        when(store.load(eq(TENANT), eq(PROJECT), anyString()))
                .thenReturn(new LinksStore.Loaded(FOLDER, doc, manifest(Map.of()), config));
        when(manifestOps.addEntry(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
    }

    private LinksManifestOps.LinkFields capturedFields() {
        ArgumentCaptor<LinksManifestOps.LinkFields> captor =
                ArgumentCaptor.forClass(LinksManifestOps.LinkFields.class);
        verify(manifestOps).addEntry(anyString(), anyString(), anyString(), anyString(),
                captor.capture(), any());
        return captor.getValue();
    }

    private TargetsContext context(TargetPurpose purpose) {
        return new TargetsContext(TENANT, PROJECT, FOLDER, "mara", purpose, Map.of());
    }

    private ShareIntakeContext shareInto(String target) {
        return new ShareIntakeContext(TENANT, PROJECT, FOLDER,
                new ShareIntake("Titel", "https://example.com/x", null, false),
                "meine Notiz", "mara", target);
    }

    private static ApplicationDocument manifest(Map<String, Object> block) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(LinksConfig.BLOCK, block);
        return new ApplicationDocument("application", LinksConfig.BLOCK,
                "Lesezeichen", null, config, new LinkedHashMap<>());
    }
}
