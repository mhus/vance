package de.mhus.vance.addon.brain.workbook.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FenceExtractor}: pulling {@code vance-*} fences out of
 * a workpage body, parsing their YAML attrs, recursing into containers, and
 * reporting malformed YAML per block.
 */
class FenceExtractorTest {

    private final FenceExtractor extractor = new FenceExtractor();

    @Test
    void extract_singleFormFence_parsesTypeAndAttrs() {
        String body = """
                # Title

                ```vance-form
                config: vance:/data/x.records.json?kind=records
                saveScript: vance:calc.js
                ```
                """;
        List<FenceBlock> blocks = extractor.extract("p.md", body);

        assertThat(blocks).hasSize(1);
        FenceBlock b = blocks.get(0);
        assertThat(b.type()).isEqualTo("form");
        assertThat(b.str("config")).isEqualTo("vance:/data/x.records.json?kind=records");
        assertThat(b.str("saveScript")).isEqualTo("vance:calc.js");
        assertThat(b.parseError()).isNull();
    }

    @Test
    void extract_ignoresNonVanceFencesAndPlainCode() {
        String body = """
                ```js
                const x = 1;
                ```

                Some text.
                """;
        assertThat(extractor.extract("p.md", body)).isEmpty();
    }

    @Test
    void extract_recursesIntoColumnsContainer() {
        // 4-backtick columns wrapping a 3-backtick vance-form.
        String body = "````vance-columns\n"
                + "```vance-form\n"
                + "config: vance:a.records.json?kind=records\n"
                + "```\n"
                + "````\n";
        List<FenceBlock> blocks = extractor.extract("p.md", body);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo("form");
        assertThat(blocks.get(0).str("config")).isEqualTo("vance:a.records.json?kind=records");
    }

    @Test
    void extract_malformedYaml_setsParseError() {
        String body = "```vance-form\n: : not valid : yaml :\n\t- broken\n```\n";
        List<FenceBlock> blocks = extractor.extract("p.md", body);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).parseError()).isNotNull();
    }

    @Test
    void extract_reportsOneBasedLineOfOpeningFence() {
        String body = "line1\nline2\n```vance-input\nconfig: vance:n.md\n```\n";
        List<FenceBlock> blocks = extractor.extract("p.md", body);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).line()).isEqualTo(3);
    }
}
