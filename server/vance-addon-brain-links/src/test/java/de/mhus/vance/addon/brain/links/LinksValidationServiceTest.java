package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The validator earns its place only by reporting what the normal load path
 * does <em>silently</em>. So every test here pairs a manifest with the silent
 * loss it causes — and one test guards the other direction: things that work
 * must not produce findings, or the reader learns to ignore them.
 */
class LinksValidationServiceTest {

    private final LinksValidationService service =
            new LinksValidationService(mock(DocumentService.class));

    @Test
    void validate_healthyManifestHasNoFindings() {
        // A validator that cries about correct files trains its reader to skip
        // it — this is the test that keeps the other checks honest.
        assertThat(service.findings("""
                $meta:
                  kind: application
                  app: links
                title: Reading
                links:
                  groups:
                  - Rust
                  entries:
                  - url: https://a.example/one
                    title: One
                    group: Rust
                    tags: [async]
                    addedAt: '2026-08-21T08:00:00Z'
                  - url: https://b.example/two
                  index:
                    outputPath: _index.md
                """)).isEmpty();
    }

    @Test
    void validate_reportsAnEntryWithoutAUrl() {
        // LinkEntry.fromMap returns null and the row vanishes without a word.
        List<Finding> findings = service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - title: I have no url
                """);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.code()).isEqualTo("url-missing");
            assertThat(f.location()).isEqualTo("links.entries[0]");
        });
    }

    @Test
    void validate_reportsAUrlTheReaderRefuses() {
        List<Finding> findings = service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - url: javascript:alert(1)
                """);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("url-unusable");
            assertThat(f.message()).contains("dropped when read");
        });
    }

    @Test
    void validate_reportsADuplicateUrlAsAnError() {
        // The second entry is unreachable: remove and update resolve by URL and
        // both land on the first. That is worse than a cosmetic duplicate.
        List<Finding> findings = service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - url: https://a.example/x
                  - url: HTTPS://A.example/x
                """);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.code()).isEqualTo("url-duplicate");
        });
    }

    @Test
    void validate_reportsAWrongApp() {
        // The folder would simply not open as a link list.
        assertThat(service.findings("""
                $meta: { kind: application, app: binder }
                links:
                  entries: []
                """))
                .extracting(Finding::code).contains("wrong-app");
    }

    @Test
    void validate_reportsFieldsIgnoredBecauseOfTheirType() {
        List<Finding> findings = service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - url: https://a.example/x
                    title:
                    - a list, not a title
                    tags: not-a-list
                """);

        assertThat(findings).extracting(Finding::code)
                .containsExactlyInAnyOrder("field-ignored", "tags-not-a-list");
        assertThat(findings).allSatisfy(f ->
                assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_reportsANonHttpImageBecauseItRendersAsNothing() {
        // And "no picture" is indistinguishable from "the page has none".
        assertThat(service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - url: https://a.example/x
                    image: /local/file.png
                """))
                .extracting(Finding::code).contains("image-not-http");
    }

    @Test
    void validate_reportsBrokenYamlAndStopsThere() {
        // Everything after a parse error would be guesswork on a broken tree.
        List<Finding> findings = service.findings("$meta: {kind: application\nlinks: [");

        assertThat(findings).singleElement()
                .extracting(Finding::code).isEqualTo("yaml-broken");
    }

    @Test
    void validate_reportsAMissingMetaHeader() {
        assertThat(service.findings("""
                links:
                  entries: []
                """))
                .extracting(Finding::code).contains("meta-missing");
    }

    @Test
    void validate_reportsEntriesThatAreNotAList() {
        assertThat(service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries: just a string
                """))
                .extracting(Finding::code).contains("entries-not-a-list");
    }

    @Test
    void validate_acceptsTheBareUrlShortForm() {
        // The form a person writes by hand; it must not read as an error.
        assertThat(service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries:
                  - a.example/x
                """)).isEmpty();
    }

    @Test
    void validate_warnsWhenTheIndexEscapesTheAppFolder() {
        assertThat(service.findings("""
                $meta: { kind: application, app: links }
                links:
                  entries: []
                  index:
                    outputPath: ../elsewhere.md
                """))
                .extracting(Finding::code).contains("index-escapes");
    }

    @Test
    void validate_emptyDocumentIsAnError() {
        assertThat(service.findings("   "))
                .extracting(Finding::code).containsExactly("empty");
    }

    @Test
    void requireExactlyOne_refusesBothAndNeither() {
        assertThatThrownBy(() -> LinksValidationService.requireExactlyOne("links", "body"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> LinksValidationService.requireExactlyOne(null, null))
                .isInstanceOf(ToolException.class);
    }
}
