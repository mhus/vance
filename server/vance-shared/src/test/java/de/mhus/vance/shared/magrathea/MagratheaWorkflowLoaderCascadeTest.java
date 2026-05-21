package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Cascade behaviour of {@link MagratheaWorkflowLoader#load} and
 * {@link MagratheaWorkflowLoader#listAll}. Mocks {@link DocumentService}
 * so the test stays pure-logic — the resolver itself only needs the
 * cascade contract that {@code lookupCascade} / {@code listByPrefixCascade}
 * exposes.
 */
class MagratheaWorkflowLoaderCascadeTest {

    private static final String MIN_YAML = """
            start: end
            states:
              end:
                type: terminal
            """;

    private final DocumentService documents = mock(DocumentService.class);
    private final MagratheaWorkflowLoader loader = new MagratheaWorkflowLoader(documents);

    @Test
    void load_maps_project_source_to_PROJECT() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/x.yaml")))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/workflows/x.yaml", MIN_YAML, LookupResult.Source.PROJECT, null)));

        Optional<ResolvedMagratheaWorkflow> wf = loader.load("acme", "p1", "x");

        assertThat(wf).isPresent();
        assertThat(wf.get().source()).isEqualTo(MagratheaWorkflowSource.PROJECT);
    }

    @Test
    void load_maps_vance_source_to_TENANT() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/x.yaml")))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/workflows/x.yaml", MIN_YAML, LookupResult.Source.VANCE, null)));

        ResolvedMagratheaWorkflow wf = loader.load("acme", "p1", "x").orElseThrow();
        assertThat(wf.source()).isEqualTo(MagratheaWorkflowSource.TENANT);
    }

    @Test
    void load_silently_ignores_resource_source() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/x.yaml")))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/workflows/x.yaml", MIN_YAML, LookupResult.Source.RESOURCE, null)));

        Optional<ResolvedMagratheaWorkflow> wf = loader.load("acme", "p1", "x");

        assertThat(wf).isEmpty();
    }

    @Test
    void load_returns_empty_when_no_cascade_hit() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/unknown.yaml")))
                .thenReturn(Optional.empty());

        assertThat(loader.load("acme", "p1", "unknown")).isEmpty();
    }

    @Test
    void load_normalizes_name_to_lowercase() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/my-flow.yaml")))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/workflows/my-flow.yaml", MIN_YAML,
                        LookupResult.Source.PROJECT, null)));

        ResolvedMagratheaWorkflow wf = loader.load("acme", "p1", "MY-FLOW").orElseThrow();
        assertThat(wf.name()).isEqualTo("my-flow");
    }

    @Test
    void load_blank_name_returns_empty() {
        assertThat(loader.load("acme", "p1", "")).isEmpty();
        assertThat(loader.load("acme", "p1", "   ")).isEmpty();
    }

    @Test
    void load_propagates_parse_errors_as_MagratheaWorkflowParseException() {
        when(documents.lookupCascade(eq("acme"), eq("p1"), eq("_vance/workflows/bad.yaml")))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/workflows/bad.yaml",
                        "no_start_here: true",
                        LookupResult.Source.PROJECT, null)));

        assertThatThrownBy(() -> loader.load("acme", "p1", "bad"))
                .isInstanceOf(MagratheaWorkflowParseException.class)
                .hasMessageContaining("Failed to parse workflow 'bad'");
    }

    @Test
    void listAll_skips_malformed_entries_and_returns_valid_ones() {
        when(documents.listByPrefixCascade(eq("acme"), eq("p1"), eq("_vance/workflows/")))
                .thenReturn(Map.of(
                        "_vance/workflows/good.yaml",
                        new LookupResult("_vance/workflows/good.yaml", MIN_YAML,
                                LookupResult.Source.PROJECT, null),
                        "_vance/workflows/bad.yaml",
                        new LookupResult("_vance/workflows/bad.yaml", "no_start: true",
                                LookupResult.Source.PROJECT, null),
                        "_vance/workflows/resource.yaml",
                        new LookupResult("_vance/workflows/resource.yaml", MIN_YAML,
                                LookupResult.Source.RESOURCE, null)));

        var all = loader.listAll("acme", "p1");

        assertThat(all).hasSize(1);
        assertThat(all.get(0).name()).isEqualTo("good");
    }
}
