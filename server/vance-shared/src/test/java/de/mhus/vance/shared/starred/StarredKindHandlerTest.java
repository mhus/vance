package de.mhus.vance.shared.starred;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The two cross-entry defects the handler exists for. Structural per-entry
 * findings are covered by {@link StarredCodecTest}; here the interest is what
 * only becomes visible across the whole list.
 */
class StarredKindHandlerTest {

    private static final String PATH = "_vance/config/starred.yaml";

    private final StarredKindHandler handler = new StarredKindHandler();

    @Test
    void kindName_isTheVanceConfigurationFamilyMember() {
        assertThat(handler.getName()).isEqualTo("vance-starred");
    }

    @Test
    void detects_neverClaimsUntypedContent() {
        // The shape (project/path/title keys) is far too generic to claim, and a
        // loose detector would win over more specific kinds sorting after it.
        assertThat(handler.detects("""
                items:
                  - project: p
                    path: a.md
                """)).isFalse();
    }

    @Test
    void validate_duplicateProjectPath_isAnError() {
        List<Finding> findings = validate("""
                items:
                  - project: p
                    path: a.md
                    kind: text
                  - project: p
                    path: a.md
                    kind: text
                """);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.code()).isEqualTo("vance-starred-duplicate");
            assertThat(f.message()).contains("p/a.md");
        });
    }

    @Test
    void validate_twoResolvableEntriesOfSameType_warnsAndNamesTheWinner() {
        List<Finding> findings = validate("""
                items:
                  - project: p
                    path: first/_app.yaml
                    kind: application
                    type: links
                  - project: p
                    path: second/_app.yaml
                    kind: application
                    type: links
                """);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.WARNING);
            assertThat(f.code()).isEqualTo("vance-starred-ambiguous-type");
            assertThat(f.message()).contains("p/first/_app.yaml");
        });
    }

    @Test
    void validate_hiddenEntryStillCountsTowardsTypeAmbiguity() {
        // A hidden entry is resolvable, so it can win a findByType — the
        // ambiguity is real even though the landing page shows only one.
        List<Finding> findings = validate("""
                items:
                  - project: p
                    path: a/_app.yaml
                    kind: application
                    type: links
                    hidden: true
                  - project: p
                    path: b/_app.yaml
                    kind: application
                    type: links
                """);

        assertThat(findings).anySatisfy(
                f -> assertThat(f.code()).isEqualTo("vance-starred-ambiguous-type"));
    }

    @Test
    void validate_disabledDuplicateType_isNotAConflict() {
        // A disabled entry can never be picked, so it does not compete.
        List<Finding> findings = validate("""
                items:
                  - project: p
                    path: a/_app.yaml
                    kind: application
                    type: links
                    enabled: false
                  - project: p
                    path: b/_app.yaml
                    kind: application
                    type: links
                """);

        assertThat(findings).noneSatisfy(
                f -> assertThat(f.code()).isEqualTo("vance-starred-ambiguous-type"));
    }

    @Test
    void validate_healthyList_hasNoFindings() {
        assertThat(validate("""
                $meta:
                  kind: vance-starred
                items:
                  - project: _user_mhu
                    path: links/_app.yaml
                    kind: application
                    type: links
                  - project: work
                    path: notes/today.md
                    kind: text
                """)).isEmpty();
    }

    private List<Finding> validate(String content) {
        return handler.validate(content, new KindValidationContext(
                "acme", "_user_mhu", PATH, "application/yaml", new NoDocs()));
    }

    /**
     * The handler must not need document access at all — a context whose
     * {@link DocRefs} throws proves it. Existence and drift are
     * {@code reconcile}'s job, because {@code DocRefs} is bound to a single
     * project and this is a cross-project list.
     */
    private static final class NoDocs implements DocRefs {
        @Override public boolean exists(String path) {
            throw new AssertionError("kind validation must not resolve documents");
        }

        @Override public @Nullable String kindOf(String path) {
            throw new AssertionError("kind validation must not resolve documents");
        }

        @Override public @Nullable Map<String, Object> readYaml(String path) {
            throw new AssertionError("kind validation must not resolve documents");
        }
    }
}
