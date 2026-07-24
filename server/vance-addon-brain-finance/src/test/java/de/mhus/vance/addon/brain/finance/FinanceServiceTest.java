package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FinanceServiceTest {

    private static final String YAML = "application/yaml";

    private DocumentService documentService;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        service = new FinanceService(documentService, contextFactory);
    }

    /** Root project with a recurring rent (no validity, always active). */
    private static FinanceTreeDocument sample() {
        FinanceValue rent = new FinanceValue(800, ValueMode.RECURRING,
                new Period(1, PeriodUnit.MONTH), null, null, null, null);
        FinanceNode root = new FinanceNode("projekt", "Projekt", null, null, 1, null, null,
                List.of(rent), List.of());
        return new FinanceTreeDocument(1, "Plan", null, root);
    }

    private DocumentDocument docWithTree(FinanceTreeDocument tree) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id1");
        when(doc.getMimeType()).thenReturn(YAML);
        when(doc.getPath()).thenReturn("plan.finance-tree.yaml");
        when(doc.getTitle()).thenReturn("Plan");
        String body = FinanceTreeCodec.serialize(tree, YAML);
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(documentService.update(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        return doc;
    }

    private String capturedBody() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(documentService).update(
                eq("id1"), any(), any(), cap.capture(), any(), any(), any(), any(), any(),
                any(), any());
        return cap.getValue();
    }

    @Test
    void recalculate_writesComputedSnapshotBack() {
        DocumentDocument doc = docWithTree(sample());

        FinanceComputed computed = service.recalculate(doc, "alice");

        NodeSnapshot root = computed.nodes().get(0);
        assertThat(root.name()).isEqualTo("projekt");
        assertThat(root.perYear()).isCloseTo(9600.0, within(1e-6));

        String body = capturedBody();
        assertThat(body).contains("$computed");
        assertThat(body).contains("perYear");
    }
}
