package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TemplateLoaderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PREFIX = TemplateLoader.TEMPLATE_PATH_PREFIX;

    private static final String MEETING_DEF = """
            title:       { de: "Meeting-Notiz", en: "Meeting note" }
            description: { de: "Notiz",          en: "Note" }
            icon: clipboard
            tags: [note, meeting]
            name:
              mode: free
              default: "meeting-{{ date }}"
            fields:
              - name: topic
                type: string
                required: true
                label: { en: "Topic" }
            """;

    private static final String MEETING_BODY =
            "---\nkind: workpage\ntitle: {{ topic }}\n---\n# {{ topic }}\n";

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final TemplateLoader loader = new TemplateLoader(documentService, renderer);

    private static LookupResult res(String path, String content, LookupResult.Source src) {
        return new LookupResult(path, content, src, null);
    }

    private void stubListing(Map<String, LookupResult> map) {
        when(documentService.listByPrefixCascade(eq(TENANT), eq(PROJECT), eq(PREFIX)))
                .thenReturn(map);
    }

    @Test
    void load_resolvesDefinitionAndBody_fromSameTier() {
        Map<String, LookupResult> map = new LinkedHashMap<>();
        map.put(PREFIX + "meeting-notes.yaml", res(PREFIX + "meeting-notes.yaml", MEETING_DEF, LookupResult.Source.VANCE));
        map.put(PREFIX + "meeting-notes.tmpl.md", res(PREFIX + "meeting-notes.tmpl.md", MEETING_BODY, LookupResult.Source.VANCE));
        stubListing(map);

        Optional<ResolvedTemplate> hit = loader.load(TENANT, PROJECT, "meeting-notes");

        assertThat(hit).isPresent();
        ResolvedTemplate t = hit.get();
        assertThat(t.name()).isEqualTo("meeting-notes");
        assertThat(t.title()).containsEntry("en", "Meeting note");
        assertThat(t.tags()).containsExactly("note", "meeting");
        assertThat(t.nameMode()).isEqualTo(TemplateNameMode.FREE);
        assertThat(t.nameDefaultTemplate()).isEqualTo("meeting-{{ date }}");
        assertThat(t.fields()).hasSize(1);
        assertThat(t.bodyPath()).isEqualTo(PREFIX + "meeting-notes.tmpl.md");
        assertThat(t.bodyExtension()).isEqualTo("md");
        assertThat(t.source()).isEqualTo(TemplateSource.VANCE);
    }

    @Test
    void load_missingBody_throwsParseException() {
        Map<String, LookupResult> map = new LinkedHashMap<>();
        map.put(PREFIX + "meeting-notes.yaml", res(PREFIX + "meeting-notes.yaml", MEETING_DEF, LookupResult.Source.VANCE));
        stubListing(map);

        assertThat(catchTemplateParse(() -> loader.load(TENANT, PROJECT, "meeting-notes")))
                .contains("no body file");
    }

    @Test
    void load_bodyOverride_pointsToExplicitFile() {
        String def = MEETING_DEF + "body: shared.tmpl.md\n";
        Map<String, LookupResult> map = new LinkedHashMap<>();
        map.put(PREFIX + "meeting-notes.yaml", res(PREFIX + "meeting-notes.yaml", def, LookupResult.Source.VANCE));
        map.put(PREFIX + "shared.tmpl.md", res(PREFIX + "shared.tmpl.md", MEETING_BODY, LookupResult.Source.VANCE));
        stubListing(map);

        Optional<ResolvedTemplate> hit = loader.load(TENANT, PROJECT, "meeting-notes");

        assertThat(hit).isPresent();
        assertThat(hit.get().bodyPath()).isEqualTo(PREFIX + "shared.tmpl.md");
    }

    @Test
    void load_fixedName_requiresValue() {
        String def = """
                title: { en: "App" }
                description: { en: "App manifest" }
                name:
                  mode: fixed
                """;
        Map<String, LookupResult> map = new LinkedHashMap<>();
        map.put(PREFIX + "app.yaml", res(PREFIX + "app.yaml", def, LookupResult.Source.VANCE));
        map.put(PREFIX + "app.tmpl.yaml", res(PREFIX + "app.tmpl.yaml", "$meta:\n  kind: application\n", LookupResult.Source.VANCE));
        stubListing(map);

        assertThat(catchTemplateParse(() -> loader.load(TENANT, PROJECT, "app")))
                .contains("name.value");
    }

    @Test
    void listAll_dedupsAndSkipsBodyless_andFiltersAvailableIn() {
        Map<String, LookupResult> map = new LinkedHashMap<>();
        // valid template
        map.put(PREFIX + "meeting-notes.yaml", res(PREFIX + "meeting-notes.yaml", MEETING_DEF, LookupResult.Source.RESOURCE));
        map.put(PREFIX + "meeting-notes.tmpl.md", res(PREFIX + "meeting-notes.tmpl.md", MEETING_BODY, LookupResult.Source.RESOURCE));
        // bodyless template — must be skipped, not throw
        map.put(PREFIX + "broken.yaml", res(PREFIX + "broken.yaml", MEETING_DEF, LookupResult.Source.RESOURCE));
        // template restricted to a different project via availableIn
        String scoped = MEETING_DEF + "availableIn: [ \"other-project\" ]\n";
        map.put(PREFIX + "scoped.yaml", res(PREFIX + "scoped.yaml", scoped, LookupResult.Source.RESOURCE));
        map.put(PREFIX + "scoped.tmpl.md", res(PREFIX + "scoped.tmpl.md", MEETING_BODY, LookupResult.Source.RESOURCE));
        stubListing(map);

        var all = loader.listAll(TENANT, PROJECT);

        assertThat(all).extracting(ResolvedTemplate::name)
                .containsExactly("meeting-notes");
    }

    private static String catchTemplateParse(Runnable r) {
        try {
            r.run();
            return "";
        } catch (TemplateParseException e) {
            return e.getMessage();
        }
    }
}
