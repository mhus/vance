package de.mhus.vance.brain.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the bundled wizards under
 * {@code src/main/resources/vance-defaults/wizards/}. Reads each YAML
 * straight from the classpath and runs it through {@link WizardLoader}'s
 * parse + Pebble-compile path. Catches typos and template syntax
 * errors that would otherwise only surface on a tenant's first
 * wizard list refresh.
 */
class BundledWizardsTest {

    private static final String TENANT = "acme";

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final WizardLoader loader = new WizardLoader(documentService, renderer);

    @Test
    void gremium_parses_cleanly() throws IOException {
        ResolvedWizard w = loadBundled("gremium");
        assertThat(w.name()).isEqualTo("gremium");
        assertThat(w.source()).isEqualTo(WizardSource.RESOURCE);
        assertThat(w.title()).containsKeys("de", "en");
        assertThat(w.fields())
                .extracting(f -> f.getName())
                .containsExactly("outputName", "purpose", "members", "createNow");

        // The members field is a repeat with two nested items.
        var membersField = w.fields().stream()
                .filter(f -> f.getName().equals("members"))
                .findFirst().orElseThrow();
        assertThat(membersField.getType()).isEqualTo("repeat");
        assertThat(membersField.getMin()).isEqualTo(2);
        assertThat(membersField.getMax()).isEqualTo(8);
        assertThat(membersField.getItem()).hasSize(2);
    }

    @Test
    void vogonStrategie_parses_cleanly() throws IOException {
        ResolvedWizard w = loadBundled("vogon-strategie");
        assertThat(w.name()).isEqualTo("vogon-strategie");
        assertThat(w.source()).isEqualTo(WizardSource.RESOURCE);
        assertThat(w.fields())
                .extracting(f -> f.getName())
                .containsExactly("outputName", "deliverable", "scope", "depth", "runNow");

        var depth = w.fields().stream()
                .filter(f -> f.getName().equals("depth"))
                .findFirst().orElseThrow();
        assertThat(depth.getType()).isEqualTo("select");
        assertThat(depth.getChoices())
                .extracting(c -> c.getValue())
                .containsExactly("light", "balanced", "deep");
    }

    @Test
    void essayRecipe_links_to_essayMitRecipe_followUp() throws IOException {
        ResolvedWizard w = loadBundled("essay-recipe");
        assertThat(w.followUps()).hasSize(1);
        WizardFollowUp fu = w.followUps().get(0);
        assertThat(fu.wizard()).isEqualTo("essay-mit-recipe");
        assertThat(fu.prefill()).containsEntry("recipe", "{{ recipeName }}");
    }

    @Test
    void essayMitRecipe_parses_cleanly() throws IOException {
        ResolvedWizard w = loadBundled("essay-mit-recipe");
        assertThat(w.fields())
                .extracting(f -> f.getName())
                .containsExactly("recipe", "topic", "keyPoints");
    }

    @Test
    void createProject_isEddieOnly_byAvailableIn() throws IOException {
        ResolvedWizard w = loadBundled("create-project");
        assertThat(w.fields())
                .extracting(f -> f.getName())
                .containsExactly("projectName", "kit");
        // Kit must remain optional — the YAML omits required, so it defaults to false.
        var kit = w.fields().stream()
                .filter(f -> f.getName().equals("kit"))
                .findFirst().orElseThrow();
        assertThat(kit.isRequired()).isFalse();

        // Listing filter: only user-namespace + tenant project.
        assertThat(w.availableIn()).containsExactly("_user_*", "_tenant");
        assertThat(WizardLoader.isAvailableIn(w.availableIn(), "_user_alice")).isTrue();
        assertThat(WizardLoader.isAvailableIn(w.availableIn(), "_tenant")).isTrue();
        assertThat(WizardLoader.isAvailableIn(w.availableIn(), "research-2026")).isFalse();
    }

    private ResolvedWizard loadBundled(String name) throws IOException {
        String resourcePath = "vance-defaults/wizards/" + name + ".yaml";
        String yaml = readClasspath(resourcePath);
        String docPath = "_vance/wizards/" + name + ".yaml";

        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(
                eq(TENANT),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                eq(docPath)))
                .thenReturn(Optional.of(new LookupResult(
                        docPath, yaml, LookupResult.Source.RESOURCE, null)));

        return loader.load(TENANT, null, null, name).orElseThrow(
                () -> new AssertionError("bundled wizard '" + name + "' could not be loaded"));
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = BundledWizardsTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("classpath resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
