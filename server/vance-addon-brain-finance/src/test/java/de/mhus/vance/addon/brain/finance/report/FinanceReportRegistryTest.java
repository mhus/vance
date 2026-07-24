package de.mhus.vance.addon.brain.finance.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceReportRegistryTest {

    private final FinanceReportRegistry registry = new FinanceReportRegistry(
            List.of(new TableReportProcessor(), new SeriesReportProcessor()));

    @Test
    void list_returnsAllProcessors() {
        assertThat(registry.list()).extracting(FinanceReportProcessor::type)
                .containsExactlyInAnyOrder("table", "series");
    }

    @Test
    void find_resolvesByTypeToOutputKind() {
        assertThat(registry.find("table").outputKind()).isEqualTo("sheet");
        assertThat(registry.find("series").outputKind()).isEqualTo("chart");
    }

    @Test
    void find_unknownType_returnsNull() {
        assertThat(registry.find("nope")).isNull();
    }
}
