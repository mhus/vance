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
}
