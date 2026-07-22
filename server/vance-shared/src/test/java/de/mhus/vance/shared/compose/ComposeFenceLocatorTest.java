package de.mhus.vance.shared.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComposeFenceLocatorTest {

    private static final String TWO =
            "# Page\n\n```vance-compose\nname: first\n```\n\n```vance-compose\nname: second\n```\n";

    @Test
    void findAll_returnsEachFenceWithItsYaml_inOrder() {
        List<ComposeFenceLocator.Fence> fences = ComposeFenceLocator.findAll(TWO);

        assertThat(fences).hasSize(2);
        assertThat(fences.get(0).yaml()).isEqualTo("name: first\n");
        assertThat(fences.get(1).yaml()).isEqualTo("name: second\n");
    }

    @Test
    void findAll_none_whenNoComposeFence() {
        assertThat(ComposeFenceLocator.findAll("# Page\n\njust text\n")).isEmpty();
    }

    @Test
    void findAt_returnsFenceContainingOffset() {
        List<ComposeFenceLocator.Fence> fences = ComposeFenceLocator.findAll(TWO);

        ComposeFenceLocator.Fence hit = ComposeFenceLocator.findAt(fences, TWO.indexOf("name: second"));

        assertThat(hit).isNotNull();
        assertThat(hit.yaml()).isEqualTo("name: second\n");
    }

    @Test
    void findAt_null_whenOffsetOutsideAnyFence() {
        List<ComposeFenceLocator.Fence> fences = ComposeFenceLocator.findAll(TWO);

        assertThat(ComposeFenceLocator.findAt(fences, 0)).isNull(); // "# Page" heading
    }

    @Test
    void replaceYaml_splicesNewBody_keepingSurroundingDoc() {
        String doc = "a\n```vance-compose\nname: x\n```\nb\n";
        ComposeFenceLocator.Fence f = ComposeFenceLocator.findAll(doc).get(0);

        String out = ComposeFenceLocator.replaceYaml(doc, f, "name: y\nextra: 1\n");

        assertThat(out).isEqualTo("a\n```vance-compose\nname: y\nextra: 1\n```\nb\n");
    }

    @Test
    void findAll_ignoresUnterminatedFence() {
        assertThat(ComposeFenceLocator.findAll("```vance-compose\nname: x\nno close here\n")).isEmpty();
    }
}
