package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Documents → source configurations. The behaviour worth pinning is what
 * happens to a <i>broken</i> document: it is skipped with a log line, never
 * fatal, because one unparseable file must not cost a project its other
 * sources.
 */
class SourceConfigLoaderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PREFIX = SourceConfigPaths.FEEDS;

    private DocumentService documents;
    private SourceConfigLoader loader;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        loader = new SourceConfigLoader(documents);
    }

    private void given(Map<String, String> docs) {
        Map<String, LookupResult> hits = new LinkedHashMap<>();
        docs.forEach((path, body) -> hits.put(
                path, new LookupResult(path, body, LookupResult.Source.PROJECT, null)));
        when(documents.listByPrefixCascade(eq(TENANT), eq(PROJECT), anyString()))
                .thenReturn(hits);
    }

    private List<SourceConfig> load() {
        return loader.load(TENANT, PROJECT, PREFIX);
    }

    @Test
    void load_readsTheCommonFieldsAndTakesTheNameFromTheFilename() {
        given(Map.of(PREFIX + "hrafnagud.yaml", """
                protocol: ode
                baseUrl: https://hrafnagud.example
                apiKey: "{{secret:vault:hrafnagud}}"
                """));

        SourceConfig config = load().get(0);

        assertThat(config.name()).isEqualTo("hrafnagud");
        assertThat(config.protocol()).isEqualTo("ode");
        assertThat(config.baseUrl()).isEqualTo("https://hrafnagud.example");
        assertThat(config.apiKey()).isEqualTo("{{secret:vault:hrafnagud}}");
        assertThat(config.documentPath()).isEqualTo(PREFIX + "hrafnagud.yaml");
    }

    @Test
    void load_defaultsToEnabled_becauseADocumentThatExistsIsMeantToBeUsed() {
        given(Map.of(PREFIX + "a.yaml", "protocol: ode\n"));

        assertThat(load().get(0).enabled()).isTrue();
    }

    @Test
    void load_honoursAnExplicitDisable() {
        given(Map.of(PREFIX + "a.yaml", "protocol: ode\nenabled: false\n"));

        assertThat(load().get(0).enabled()).isFalse();
    }

    @Test
    void load_keepsUnknownFieldsAsExtras_withTheirYamlShape() {
        // The capability the flat setting namespace did not have: a list stays
        // a list instead of becoming the string "[a, b]".
        given(Map.of(PREFIX + "a.yaml", """
                protocol: mastodon
                baseUrl: https://mstdn.example
                selectors:
                  - hashtag:linux
                  - public:local
                """));

        assertThat(load().get(0).extras().get("selectors"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("hashtag:linux", "public:local");
    }

    @Test
    void load_brokenDocumentIsSkipped_theOthersSurvive() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put(PREFIX + "broken.yaml", "protocol: [unclosed\n");
        docs.put(PREFIX + "good.yaml", "protocol: ode\n");
        given(docs);

        assertThat(load()).extracting(SourceConfig::name).containsExactly("good");
    }

    @Test
    void load_documentThatIsNotAMappingIsSkipped() {
        given(Map.of(PREFIX + "a.yaml", "just a string\n"));

        assertThat(load()).isEmpty();
    }

    @Test
    void load_ignoresPathsThatAreNotConfigDocuments() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put(PREFIX + "README.md", "# how to configure a feed source\n");
        docs.put(PREFIX + "a.yaml", "protocol: ode\n");
        given(docs);

        assertThat(load()).extracting(SourceConfig::name).containsExactly("a");
    }

    @Test
    void parse_emptyDocumentIsAnError_notAnEmptyConfiguration() {
        // A file someone created and left blank is a mistake worth a log line;
        // silently producing a protocol-less config would hide it one layer on.
        assertThatThrownBy(() -> loader.parse("a", PREFIX + "a.yaml", ""))
                .isInstanceOf(SourceConfigParseException.class);
    }

    @Test
    void credentialLocation_namesTheFileAndTheField() {
        given(Map.of(PREFIX + "a.yaml", "protocol: ode\n"));

        assertThat(load().get(0).credentialLocation())
                .isEqualTo(PREFIX + "a.yaml#apiKey");
    }

    @Test
    void nameFromPath_acceptsYmlButRefusesSubfolders() {
        assertThat(SourceConfigPaths.nameFromPath(PREFIX, PREFIX + "a.yml")).isEqualTo("a");
        assertThat(SourceConfigPaths.nameFromPath(PREFIX, PREFIX + "sub/a.yaml")).isNull();
        assertThat(SourceConfigPaths.nameFromPath(PREFIX, "_vance/config/research/a.yaml")).isNull();
    }

    // ─── readerIdentity: the tenant sets the ceiling ─────────────────────

    @Test
    void readerIdentity_projectAskingForMoreThanTheTenantAllows_isCapped() {
        givenProject(Map.of(PREFIX + "a.yaml", """
                protocol: ode
                readerIdentity: identity
                """));
        givenTenant(Map.of(PREFIX + "a.yaml", """
                protocol: ode
                readerIdentity: pseudonym
                """));

        assertThat(load().get(0).readerIdentity()).isEqualTo(ReaderIdentityMode.PSEUDONYM);
    }

    @Test
    void readerIdentity_projectAskingForLessThanTheTenantAllows_keepsTheLowerValue() {
        // A ceiling restricts; it never raises a project that chose to send
        // nothing up to what the tenant would have permitted.
        givenProject(Map.of(PREFIX + "a.yaml", "protocol: ode\n"));
        givenTenant(Map.of(PREFIX + "a.yaml", """
                protocol: ode
                readerIdentity: identity
                """));

        assertThat(load().get(0).readerIdentity()).isEqualTo(ReaderIdentityMode.NONE);
    }

    @Test
    void readerIdentity_withoutATenantDocumentOfThatName_isNotCapped() {
        // The documented limit: an explicit restriction has to be written down
        // to exist. A source configured only in a project has nothing above it.
        givenProject(Map.of(PREFIX + "a.yaml", """
                protocol: ode
                readerIdentity: pseudonym
                """));
        givenTenant(Map.of());

        assertThat(load().get(0).readerIdentity()).isEqualTo(ReaderIdentityMode.PSEUDONYM);
    }

    @Test
    void readerIdentity_allNone_doesNotReadTheTenantCascadeAtAll() {
        // The common case has to stay free: no second document read when there
        // is nothing that could be capped.
        givenProject(Map.of(PREFIX + "a.yaml", "protocol: ode\n"));

        assertThat(load().get(0).readerIdentity()).isEqualTo(ReaderIdentityMode.NONE);
        org.mockito.Mockito.verify(documents, org.mockito.Mockito.never())
                .listByPrefixCascade(eq(TENANT), eq(TENANT_PROJECT), anyString());
    }

    private static final String TENANT_PROJECT =
            de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME;

    private void givenProject(Map<String, String> docs) {
        when(documents.listByPrefixCascade(eq(TENANT), eq(PROJECT), anyString()))
                .thenReturn(hits(docs));
    }

    private void givenTenant(Map<String, String> docs) {
        when(documents.listByPrefixCascade(eq(TENANT), eq(TENANT_PROJECT), anyString()))
                .thenReturn(hits(docs));
    }

    private static Map<String, LookupResult> hits(Map<String, String> docs) {
        Map<String, LookupResult> hits = new LinkedHashMap<>();
        docs.forEach((path, body) -> hits.put(
                path, new LookupResult(path, body, LookupResult.Source.PROJECT, null)));
        return hits;
    }
}
