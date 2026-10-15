package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which keys the default library is verified against — spec
 * {@code kits.md} §12.1.
 *
 * <p>The bundled ring has to survive a tenant overriding the library for
 * some other reason (policy, title); only a ring the tenant writes itself
 * replaces it. That replacement is the emergency path for a compromised
 * key, so it must actually win.
 */
class KitSourceRegistryKeysTest {

    private static final String TENANT = "acme";

    private final DocumentService documents = mock(DocumentService.class);
    private final KitSourceRegistry registry = new KitSourceRegistry(documents);

    @Test
    void defaultLibrary_withoutAnyConfiguration_carriesTheBundledKeys() {
        givenSources(null);

        assertThat(registry.resolve(TENANT, KitSourceRegistry.DEFAULT_LIBRARY_URL + "/kits/security")
                        .getPublicKeys())
                .isEqualTo(KitLibraryKeys.bundled());
    }

    @Test
    void defaultLibrary_overriddenWithoutKeys_keepsTheBundledKeys() {
        givenSources("""
                sources:
                  - id: library
                    type: library
                    url: https://library.vancetope.com/
                    title: Our library
                """);

        KitSourceDto library = registry.resolve(TENANT, KitSourceRegistry.DEFAULT_LIBRARY_URL);
        assertThat(library.getTitle()).isEqualTo("Our library");
        assertThat(library.getPublicKeys()).isEqualTo(KitLibraryKeys.bundled());
    }

    @Test
    void defaultLibrary_overriddenUnderAnotherSpelling_keepsTheBundledKeys() {
        // Case and trailing slashes do not change where a url points. An
        // override spelled differently is still the default library — it
        // must neither lose the bundled keys nor sit beside a second,
        // built-in entry for the same host.
        givenSources("""
                sources:
                  - id: library
                    type: library
                    url: HTTPS://Library.Vancetope.com//
                    title: Our library
                """);

        assertThat(registry.resolve(TENANT, KitSourceRegistry.DEFAULT_LIBRARY_URL + "/kits/security")
                        .getPublicKeys())
                .isEqualTo(KitLibraryKeys.bundled());
        assertThat(registry.configuredSources(TENANT))
                .singleElement()
                .satisfies(source -> assertThat(source.getTitle()).isEqualTo("Our library"));
    }

    @Test
    void defaultLibrary_overriddenWithARing_trustsOnlyThatRing() {
        givenSources("""
                sources:
                  - id: library
                    type: library
                    url: https://library.vancetope.com
                    publicKeys:
                      replacement-key: |
                        -----BEGIN PUBLIC KEY-----
                        MCowBQYDK2VwAyEAcqV6ocaiL3YO/RCUfea1PGn7T+Wyz/5HjhrwFqzWOmw=
                        -----END PUBLIC KEY-----
                """);

        assertThat(registry.resolve(TENANT, KitSourceRegistry.DEFAULT_LIBRARY_URL)
                        .getPublicKeys())
                .containsOnlyKeys("replacement-key");
    }

    @Test
    void anotherSource_doesNotInheritTheLibraryKeys() {
        // The bundled keys vouch for one library. Lending them to any
        // source that forgot its own would let that source's kits verify
        // as if the library had signed them.
        givenSources("""
                sources:
                  - id: other
                    type: library
                    url: https://kits.example.org
                """);

        assertThat(registry.resolve(TENANT, "https://kits.example.org/x").getPublicKeys())
                .isNull();
    }

    private void givenSources(@Nullable String yaml) {
        if (yaml == null) {
            when(documents.findByPath(
                            eq(TENANT),
                            eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                            eq(KitSourceRegistry.SOURCES_PATH)))
                    .thenReturn(Optional.empty());
            return;
        }
        DocumentDocument doc = mock(DocumentDocument.class);
        when(documents.findByPath(
                        eq(TENANT), eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq(KitSourceRegistry.SOURCES_PATH)))
                .thenReturn(Optional.of(doc));
        when(documents.readContent(any(DocumentDocument.class))).thenReturn(yaml);
    }
}
