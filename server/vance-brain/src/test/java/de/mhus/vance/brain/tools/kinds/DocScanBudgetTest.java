package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two stop axes of {@link DocScanBudget} (planning/doc-file-tool-parity.md
 * §3.3): the maxScannedDocs cap and the total byte budget — whichever hits
 * first stops the scan with {@code exhausted()} + warning, while oversized
 * documents are skipped outright (never claimed, never counted against the
 * doc cap). Budget checks run on the {@code size} metadata, so the fixtures
 * here claim virtual sizes without allocating anything.
 */
class DocScanBudgetTest {

    @Test
    void docCountCap_stopsWithWarning() {
        DocScanBudget budget = DocScanBudget.fromParams(params("maxScannedDocs", 2));

        assertThat(budget.tryClaim(10)).isTrue();
        assertThat(budget.tryClaim(10)).isTrue();
        assertThat(budget.tryClaim(10)).isFalse();
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.scannedDocs()).isEqualTo(2);
        assertThat(budget.totalBytes()).isEqualTo(20L);

        String warning = budget.warning(5, "documents/");
        assertThat(warning)
                .contains("maxScannedDocs cap of 2")
                .contains("after 2 of 5 candidate documents")
                .contains("narrow the pathPrefix");
    }

    @Test
    void byteBudget_stopsEvenBelowTheDocCap() {
        DocScanBudget budget = DocScanBudget.fromParams(params("maxScannedDocs", 500));

        // 25 claims of exactly MAX_DOC_BYTES fill the 50-MB byte budget
        // (per-doc cap respected) — the 26th stops the scan on
        // BYTE_BUDGET, long before the 500-doc cap.
        for (int i = 0; i < 25; i++) {
            assertThat(budget.tryClaim(DocScanBudget.MAX_DOC_BYTES)).isTrue();
        }
        assertThat(budget.tryClaim(DocScanBudget.MAX_DOC_BYTES)).isFalse();
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.scannedDocs()).isEqualTo(25);
        assertThat(budget.totalBytes()).isEqualTo(25 * DocScanBudget.MAX_DOC_BYTES);

        String warning = budget.warning(30, "*");
        assertThat(warning).contains("reached the 50 MB scan budget").contains("after 25 of 30 candidate documents");
    }

    @Test
    void oversizedDocs_areSkippedWithoutClaimingOrStopping() {
        DocScanBudget budget = DocScanBudget.fromParams(params("maxScannedDocs", 1));

        // Above the per-doc cap: skipped, and the loop keeps scanning instead
        // of treating the skip as an exhausted budget.
        assertThat(budget.tryClaim(DocScanBudget.MAX_DOC_BYTES + 1)).isFalse();
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.skippedOversized()).isEqualTo(1);

        // The regular claim still works afterwards and its size feeds the
        // byte aggregate.
        assertThat(budget.tryClaim(100)).isTrue();
        assertThat(budget.skippedOversized()).isEqualTo(1);
        assertThat(budget.totalBytes()).isEqualTo(100L);
    }

    @Test
    void fromParams_clampsAndDefaults() {
        assertThat(DocScanBudget.fromParams(null).tryClaim(1)).isTrue();
        assertThat(DocScanBudget.fromParams(new HashMap<>()).scannedDocs()).isEqualTo(0);

        // Above the hard param cap → clamped to 2000; below 1 → clamped to 1.
        // Above the hard param cap → clamped to 2000: 2000 claims fit,
        // the 2001st stops the scan.
        DocScanBudget capped = DocScanBudget.fromParams(params("maxScannedDocs", 99_999));
        for (int i = 0; i < DocScanBudget.MAX_SCANNED_DOCS_PARAM_CAP; i++) {
            assertThat(capped.tryClaim(0)).isTrue();
        }
        assertThat(capped.tryClaim(0)).isFalse();
        assertThat(capped.exhausted()).isTrue();

        DocScanBudget floor = DocScanBudget.fromParams(params("maxScannedDocs", 0));
        assertThat(floor.tryClaim(1)).isTrue();
        assertThat(floor.tryClaim(1)).isFalse(); // cap 1 reached
        assertThat(floor.exhausted()).isTrue();
    }

    @Test
    void completedScan_hasNoWarning() {
        DocScanBudget budget = DocScanBudget.fromParams(params("maxScannedDocs", 2));
        assertThat(budget.tryClaim(10)).isTrue();
        assertThat(budget.tryClaim(10)).isTrue();

        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.warning(2, "documents/")).isNull();
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }
}
