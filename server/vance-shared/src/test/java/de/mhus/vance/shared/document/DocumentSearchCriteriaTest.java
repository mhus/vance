package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Regression tests for {@link DocumentService#searchPathCriterion} — the
 * combined path/query criterion for project document search.
 *
 * <p>Guards the bug class that bit twice: assembling the search query
 * with both a {@code pathPrefix} and a {@code query} produced two
 * top-level criteria sharing a key (two {@code path}, or {@code $and} +
 * {@code $or} both keyed {@code null}), which Spring Data rejects with
 * {@code InvalidMongoDbApiUsageException}. The criterion must always be
 * a single combinable unit, for every flag combination.
 */
class DocumentSearchCriteriaTest {

    /** Assemble a Query the way searchProjectDocuments does. */
    private static Query assemble(String pathPrefix, String query) {
        return new Query()
                .addCriteria(Criteria.where("tenantId").is("t"))
                .addCriteria(Criteria.where("projectId").is("p"))
                .addCriteria(Criteria.where("status").is("ACTIVE"))
                .addCriteria(DocumentService.searchPathCriterion(pathPrefix, query));
    }

    @Test
    void noPrefixNoQuery_buildsWithoutCollision() {
        assertThatCode(() -> assemble(null, null).getQueryObject())
                .doesNotThrowAnyException();
    }

    @Test
    void prefixOnly_buildsWithoutCollision() {
        assertThatCode(() -> assemble("studium-ws26/", null).getQueryObject())
                .doesNotThrowAnyException();
    }

    @Test
    void queryOnly_buildsWithoutCollision() {
        assertThatCode(() -> assemble(null, "noten").getQueryObject())
                .doesNotThrowAnyException();
    }

    @Test
    void prefixAndQuery_buildsWithoutCollision_andCombinesUnderAnd() {
        // This is the combination that previously threw.
        assertThatCode(() -> assemble("studium-ws26/", "noten").getQueryObject())
                .doesNotThrowAnyException();
        assertThat(assemble("studium-ws26/", "noten").getQueryObject())
                .containsKey("$and");
    }

    // ── searchProjectDocumentsMeta helpers (scoreOf / buildSnippet) ──

    private static DocumentDocument doc(String title, String summary) {
        return DocumentDocument.builder().title(title).summary(summary).build();
    }

    @Test
    void scoreOf_titleMatchOutranksSummaryMatch() {
        assertThat(DocumentService.scoreOf(doc("Dentist visit", "went well"), "dentist"))
                .isEqualTo(2);
        assertThat(DocumentService.scoreOf(doc("Monday", "saw the dentist today"), "dentist"))
                .isEqualTo(1);
        assertThat(DocumentService.scoreOf(doc("Dentist", "dentist again"), "dentist"))
                .isEqualTo(3);
    }

    @Test
    void scoreOf_noNeedle_isZero() {
        assertThat(DocumentService.scoreOf(doc("anything", "anything"), "")).isZero();
    }

    @Test
    void buildSnippet_windowsAroundTheHitInSummary() {
        String summary = "Started the morning with coffee and a slow walk to the office, then a "
                + "long meeting about the quarterly budget, and afterwards a long stretch of "
                + "focused work, some running in the park, dinner, and finally reading a book.";
        String snippet = DocumentService.buildSnippet(doc("Tuesday", summary), "budget");
        assertThat(snippet).contains("budget").startsWith("…").endsWith("…");
    }

    @Test
    void buildSnippet_fallsBackToSummaryHeadThenTitle() {
        assertThat(DocumentService.buildSnippet(doc("T", "short summary"), "absent"))
                .isEqualTo("short summary");
        assertThat(DocumentService.buildSnippet(doc("Only title", null), "x"))
                .isEqualTo("Only title");
    }
}
