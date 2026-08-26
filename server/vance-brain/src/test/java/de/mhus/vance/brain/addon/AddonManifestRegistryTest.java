package de.mhus.vance.brain.addon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The {@code kinds:} block is what lets the Web-UI stop loading every
 * federation remote at boot, so what matters here is less "does YAML parse"
 * than the three distinctions the loader has to keep straight: declared vs.
 * absent vs. empty, and that a manifest without a {@code tile:} still gets
 * read at all.
 */
class AddonManifestRegistryTest {

    private static AddonManifestRegistry registryOf(String... manifests) throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource[] resources = new Resource[manifests.length];
        for (int i = 0; i < manifests.length; i++) {
            resources[i] = new ByteArrayResource(manifests[i].getBytes(StandardCharsets.UTF_8));
        }
        when(resolver.getResources(anyString())).thenReturn(resources);
        return new AddonManifestRegistry(resolver);
    }

    @Test
    void kinds_declaredList_isReadPerAddon() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: calendar
                kinds:
                  - "calendar"
                  - "timeline"
                  - "application:calendar"
                """);

        assertThat(registry.kindsFor("calendar"))
                .containsExactly("calendar", "timeline", "application:calendar");
    }

    @Test
    void kinds_manifestWithoutTile_isStillRead() throws IOException {
        // The tile block ends in a `continue`, and all but two addons declare
        // kinds and no tile — reading kinds after it would skip nearly all.
        AddonManifestRegistry registry = registryOf("""
                id: kanban
                kinds:
                  - "application:kanban"
                """);

        assertThat(registry.tileFor("kanban")).isNull();
        assertThat(registry.kindsFor("kanban")).containsExactly("application:kanban");
    }

    @Test
    void kinds_absentKey_readsAsNullSoTheHostLoadsEagerly() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: mystery
                version: 1.0.0
                """);

        assertThat(registry.kindsFor("mystery")).isNull();
    }

    @Test
    void kinds_emptyList_readsAsDeclaredNoneRatherThanAbsent() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: store
                kinds: []
                """);

        assertThat(registry.kindsFor("store")).isNotNull().isEmpty();
    }

    @Test
    void kinds_nonListValue_dropsTheDeclarationWithoutFailingTheBoot() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: broken
                kinds: calendar
                """);

        assertThat(registry.kindsFor("broken")).isNotNull().isEmpty();
    }

    @Test
    void kinds_blankEntries_areDropped() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: canvas
                kinds:
                  - "canvas"
                  - "  "
                  - " application:canvasbook "
                """);

        assertThat(registry.kindsFor("canvas")).containsExactly("canvas", "application:canvasbook");
    }

    @Test
    void kinds_coexistWithTileAndProfileOnTheSameManifest() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: simpleauth
                tile:
                  label: Permissions
                profile:
                  label: Grants
                kinds: []
                """);

        assertThat(registry.tileFor("simpleauth")).isNotNull();
        assertThat(registry.profileTabFor("simpleauth")).isNotNull();
        assertThat(registry.kindsFor("simpleauth")).isNotNull().isEmpty();
    }

    @Test
    void eager_declared_isReported() throws IOException {
        AddonManifestRegistry registry = registryOf("""
                id: workbook
                kinds:
                  - "workpage"
                eager: true
                """);

        assertThat(registry.eagerFor("workbook")).isTrue();
        // Still indexed: the host must not load it a second time when a
        // workpage is opened.
        assertThat(registry.kindsFor("workbook")).containsExactly("workpage");
    }

    @Test
    void eager_absentOrFalse_staysOffTheWire() throws IOException {
        AddonManifestRegistry registry = registryOf(
                "id: calendar\nkinds: [\"calendar\"]\n",
                "id: kanban\neager: false\nkinds: [\"application:kanban\"]\n");

        assertThat(registry.eagerFor("calendar")).isNull();
        assertThat(registry.eagerFor("kanban")).isNull();
    }

    @Test
    void kinds_unknownAddon_hasNoDeclaration() throws IOException {
        AddonManifestRegistry registry = registryOf("id: calendar\nkinds: [\"calendar\"]\n");

        assertThat(registry.kindsFor("nope")).isNull();
    }

    @Test
    void manifests_thatAreNotMaps_areSkippedRatherThanFatal() throws IOException {
        AddonManifestRegistry registry = registryOf(
                "just a string",
                """
                id: calendar
                kinds:
                  - "calendar"
                """);

        assertThat(registry.kindsFor("calendar")).containsExactly("calendar");
    }
}
