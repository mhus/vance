package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RecordsKindHandlerTest {

    private final RecordsKindHandler handler = new RecordsKindHandler();

    private static KindValidationContext ctx() {
        return new KindValidationContext("t", "p", "notes.records.md", "text/markdown", NO_REFS);
    }

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    @Test
    void validRecords_hasNoFindings() {
        String md = "---\nkind: records\nschema: name, age\n---\n- Alice, 30\n- Bob, 25\n";
        List<Finding> findings = handler.validate(md, ctx());
        assertThat(findings).isEmpty();
    }

    @Test
    void missingSchema_isAnError() {
        String md = "---\nkind: records\n---\n- Alice\n";
        List<Finding> findings = handler.validate(md, ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("records-parse");
    }

    @Test
    void rowWithSurplusValues_warnsOverflow() {
        String md = "---\nkind: records\nschema: a, b\n---\n- 1, 2, 3\n";
        List<Finding> findings = handler.validate(md, ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.WARNING);
        assertThat(findings.get(0).code()).isEqualTo("records-overflow");
    }
}
