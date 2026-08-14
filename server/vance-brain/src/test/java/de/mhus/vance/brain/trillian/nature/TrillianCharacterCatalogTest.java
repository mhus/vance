package de.mhus.vance.brain.trillian.nature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The catalog is a document, which means it can be overridden by hand.
 * So the cases that matter are the broken ones: minting a Trillian must
 * not depend on someone's YAML being correct.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianCharacterCatalogTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";

    @Mock
    DocumentService documentService;

    @Test
    void aCharacterComesFromTheCatalog() {
        given("""
                names:
                  - { name: Zaphod, gender: male }
                traits:
                  - "Impossible."
                """);

        Map<String, Object> attrs = catalog().generate(TENANT, PROJECT, new Random(1));

        assertThat(attrs)
                .containsEntry("name", "Zaphod")
                .containsEntry("gender", "male")
                .containsEntry("character", "Impossible.");
    }

    @Test
    void theCatalogIsRead_fromTheCascade() {
        // The whole reason it is a document: a tenant or project can
        // override the bundled list without touching Java.
        given("names:\n  - { name: Ada, gender: female }\n");

        catalog().generate(TENANT, PROJECT, new Random(1));

        org.mockito.Mockito.verify(documentService)
                .lookupCascade(TENANT, PROJECT, TrillianCharacterCatalog.PATH);
    }

    @Test
    void aMissingCatalog_stillMints() {
        when(documentService.lookupCascade(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(catalog().generate(TENANT, PROJECT, new Random(1)))
                .containsKeys("name", "gender", "character");
    }

    @Test
    void aBrokenOverride_stillMints() {
        // Hand-edited files break. A Trillian with a dull name beats a
        // Trillian that cannot be created.
        given("names: [ unclosed");

        assertThat(catalog().generate(TENANT, PROJECT, new Random(1)))
                .containsKeys("name", "gender", "character");
    }

    @Test
    void anEntryWithoutAName_isSkipped() {
        given("""
                names:
                  - { gender: female }
                  - { name: Ada, gender: female }
                traits:
                  - "Terse."
                """);

        assertThat(catalog().generate(TENANT, PROJECT, new Random(1)))
                .containsEntry("name", "Ada");
    }

    @Test
    void aNameWithoutAGender_leavesItEmpty() {
        // Never derived from the name — that is the one thing the second
        // column exists to prevent.
        given("names:\n  - { name: Alex }\ntraits:\n  - \"Terse.\"\n");

        assertThat(catalog().generate(TENANT, PROJECT, new Random(1)))
                .containsEntry("name", "Alex")
                .containsEntry("gender", "");
    }

    private TrillianCharacterCatalog catalog() {
        return new TrillianCharacterCatalog(documentService);
    }

    private void given(String yaml) {
        when(documentService.lookupCascade(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new LookupResult(
                        TrillianCharacterCatalog.PATH, yaml,
                        LookupResult.Source.RESOURCE, null)));
    }
}
