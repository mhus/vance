package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.access.IntegrationSurface;
import org.junit.jupiter.api.Test;

/**
 * What the capture profile does and — more importantly — does not open.
 *
 * <p>The negative cases are the point. Add, edit and remove share the path
 * {@code /addon/links/entry}, so the only thing keeping a browser extension
 * from deleting the list is that {@code PATCH} and {@code DELETE} were never
 * declared. That is a claim worth a test rather than a comment.
 */
class LinksCaptureScopeProfileTest {

    private final LinksCaptureScopeProfile profile = new LinksCaptureScopeProfile();

    private boolean covers(String method, String path) {
        for (IntegrationSurface surface : profile.surfaces()) {
            if (surface.matches(method, path)) return true;
        }
        return false;
    }

    @Test
    void opens_theThreeCaptureRoutes() {
        assertThat(covers("GET", "/addon/links/groups")).isTrue();
        assertThat(covers("GET", "/addon/links/entry/lookup")).isTrue();
        assertThat(covers("POST", "/addon/links/capture")).isTrue();
    }

    /**
     * A capture credential cannot read the list. Answering "is this page
     * already saved" is what it wanted {@code /scan} for, and lookup answers
     * that with one row.
     */
    @Test
    void doesNotOpen_theWholeList() {
        assertThat(covers("GET", "/addon/links/scan")).isFalse();
    }

    @Test
    void doesNotOpen_theAppsOwnEntryRoutes() {
        assertThat(covers("POST", "/addon/links/entry")).isFalse();
        assertThat(covers("PATCH", "/addon/links/entry")).isFalse();
        assertThat(covers("DELETE", "/addon/links/entry")).isFalse();
    }

    /**
     * The sharpest case for method-aware surfaces in this profile: reading the
     * group names and rewriting them are one verb apart on one path.
     */
    @Test
    void readingGroups_doesNotCarryTheRightToRewriteThem() {
        assertThat(covers("GET", "/addon/links/groups")).isTrue();
        assertThat(covers("POST", "/addon/links/groups")).isFalse();
    }

    @Test
    void doesNotOpen_markingSomethingSeen() {
        assertThat(covers("POST", "/addon/links/entry/viewed")).isFalse();
    }

    @Test
    void doesNotOpen_curation() {
        assertThat(covers("POST", "/addon/links/reorder")).isFalse();
        assertThat(covers("POST", "/addon/links/group/rename")).isFalse();
        assertThat(covers("POST", "/addon/links/rebuild")).isFalse();
    }

    @Test
    void doesNotOpen_anythingOutsideTheAddon() {
        assertThat(covers("GET", "/documents")).isFalse();
        assertThat(covers("POST", "/addon/workbook/page")).isFalse();
        assertThat(covers("POST", "/integration-tokens")).isFalse();
    }

    @Test
    void requiresAProjectPin() {
        assertThat(profile.requiresProject()).isTrue();
    }
}
