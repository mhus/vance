package de.mhus.vance.addon.brain.finance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssessmentReportProcessorTest {

    private static final ReportContext CTX = new ReportContext("t1", "p1", "proc1", "alice");

    /** projekt(+) { einnahmen(+ 12000/yr), ausgaben(-1 branch, 800/month) }. */
    private static FinanceTreeDocument tree() {
        FinanceNode einnahmen = new FinanceNode("einnahmen", "Einnahmen", null, null, 1, null, null,
                List.of(new FinanceValue(12000, ValueMode.RECURRING, new Period(1, PeriodUnit.YEAR),
                        null, null, null, null)),
                List.of());
        FinanceNode ausgaben = new FinanceNode("ausgaben", "Ausgaben", null, null, -1, null, null,
                List.of(new FinanceValue(800, ValueMode.RECURRING, new Period(1, PeriodUnit.MONTH),
                        null, null, null, null)),
                List.of());
        FinanceNode root = new FinanceNode("projekt", "Projekt", null, null, 1, null, null,
                List.of(), List.of(einnahmen, ausgaben));
        return new FinanceTreeDocument(1, "Q1 Plan", null, root);
    }

    @Test
    void render_callsLightLlmWithSignAppliedModelAndReturnsMarkdown() {
        LightLlmService llm = mock(LightLlmService.class);
        when(llm.call(org.mockito.ArgumentMatchers.any())).thenReturn("## Assessment\nLooks tight.");

        FinanceReport report = new AssessmentReportProcessor(llm).render(tree(), ReportParams.of(Map.of()), CTX);

        assertThat(report.outputKind()).isEqualTo("markdown");
        assertThat(report.mimeType()).isEqualTo("text/markdown");
        assertThat(report.body()).contains("## Assessment");

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        org.mockito.Mockito.verify(llm).call(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo("finance-report");
        assertThat(req.getTenantId()).isEqualTo("t1");
        String model = String.valueOf(req.getPebbleVars().get("model"));
        // Expense branch reads as negative (sign-applied); income positive.
        assertThat(model).contains("Einnahmen (einnahmen): +12000.00/yr");
        assertThat(model).contains("Ausgaben (ausgaben): -9600.00/yr");
    }

    @Test
    void render_emptyTree_throws() {
        LightLlmService llm = mock(LightLlmService.class);
        assertThatThrownBy(() -> new AssessmentReportProcessor(llm)
                .render(FinanceTreeDocument.empty("x", null), ReportParams.of(Map.of()), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("empty");
    }
}
