package de.mhus.vance.brain.servicecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ServiceCatalogTest {

    private static final String YAML = """
            title: { en: "Jira", de: "Jira DE" }
            description: { en: "Tickets" }
            icon: ticket
            match: [jira]
            login: { provider: atlassian, manual: oauth-tools }
            highlights:
              - tool: createIssue
                description: { en: "Create" }
                params: { body: { fields: { summary: "x" } } }
            """;

    @Test
    void anEntryIsParsedWithItsLoginAndHighlights() {
        ServiceCatalogEntry e = ServiceCatalogLoader.parse("jira", YAML);

        assertThat(e.provider()).isEqualTo("atlassian");
        assertThat(e.manual()).isEqualTo("oauth-tools");
        assertThat(e.highlights()).singleElement().satisfies(h -> {
            assertThat(h.tool()).isEqualTo("createIssue");
            assertThat(h.params()).containsKey("body");
        });
        assertThat(ServiceCatalogEntry.localized(e.title(), "de")).isEqualTo("Jira DE");
        // A language it has no text for falls back to English.
        assertThat(ServiceCatalogEntry.localized(e.description(), "de")).isEqualTo("Tickets");
    }

    @Test
    void anEntryDescribesAPackByItsNameOrItsNameContinuedWithAnUnderscore() {
        ServiceCatalogEntry e = ServiceCatalogLoader.parse("jira", YAML);

        assertThat(e.describes("jira")).isTrue();
        assertThat(e.describes("Jira_REST")).isTrue();
        assertThat(e.describes("jirassic")).isFalse();
        assertThat(e.describes("slack_rest")).isFalse();
    }

    @Test
    void anEntryWithoutAMatchOrWithAHighlightWithoutAToolIsRefused() {
        assertThatThrownBy(() -> ServiceCatalogLoader.parse("x", "title: { en: X }\nmatch: []\n"))
                .hasMessageContaining("match");
        assertThatThrownBy(() -> ServiceCatalogLoader.parse("x", "match: [a]\nhighlights: [ { description: d } ]\n"))
                .hasMessageContaining("tool");
    }

    @Test
    void aBrokenEntryIsSkippedAndTheOthersStillList() {
        DocumentService documents = mock(DocumentService.class);
        // The mocks are made first: stubbing inside another stubbing's arguments is what Mockito refuses.
        LookupResult jira = lookup(YAML);
        LookupResult broken = lookup("::: not yaml :::\n- [");
        LookupResult readme = lookup("ignored");
        when(documents.listByPrefixCascade(any(), any(), any()))
                .thenReturn(Map.of(
                        "_vance/service-catalog/jira.yaml", jira,
                        "_vance/service-catalog/broken.yaml", broken,
                        "_vance/service-catalog/readme.md", readme));

        List<ServiceCatalogEntry> entries = new ServiceCatalogLoader(documents).list("acme", "p");

        assertThat(entries).extracting(ServiceCatalogEntry::name).containsExactly("jira");
    }

    /** What ships: every bundled entry parses, and Jira and Slack are there. */
    @Test
    void theBundledEntriesParse() throws IOException {
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:vance-defaults/_vance/service-catalog/*.yaml");
        assertThat(resources).hasSizeGreaterThanOrEqualTo(2);
        List<String> names = new java.util.ArrayList<>();
        for (var resource : resources) {
            String file = resource.getFilename();
            String name = file.substring(0, file.length() - ".yaml".length());
            ServiceCatalogEntry e = ServiceCatalogLoader.parse(
                    name, new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(ServiceCatalogEntry.localized(e.title(), "en")).as(name).isNotBlank();
            assertThat(ServiceCatalogEntry.localized(e.description(), "de"))
                    .as(name)
                    .isNotBlank();
            assertThat(e.highlights()).as(name).isNotEmpty();
            names.add(name);
        }
        assertThat(names).contains("jira", "slack");
    }

    private static LookupResult lookup(String content) {
        LookupResult r = mock(LookupResult.class);
        when(r.content()).thenReturn(content);
        return r;
    }
}
