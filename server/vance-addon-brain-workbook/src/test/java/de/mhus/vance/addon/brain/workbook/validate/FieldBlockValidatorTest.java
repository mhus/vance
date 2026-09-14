package de.mhus.vance.addon.brain.workbook.validate;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FieldBlockValidator} against the canonical
 * {@link Block.Field} model — no Spring, no Mongo. Duplicate ids are
 * page-level (checked by the validation walk), so this covers the per-block
 * failure paths plus the clean cases, with and without {@code solution}.
 */
class FieldBlockValidatorTest {

    private static final String PAGE = "apps/g/page.workpage.md";
    private final FieldBlockValidator validator = new FieldBlockValidator();

    @Test
    void cleanChoiceFieldWithSolution_producesNoErrors() {
        List<Finding> f = validator.validate(field("q1", "choice", List.of("a", "b"), 0, 1), ctx());
        assertThat(errors(f)).isEmpty();
    }

    @Test
    void fieldWithoutSolution_isValid_plainFormElement() {
        List<Finding> f = validator.validate(field("c1", "multi", List.of("a", "b"), null, List.of(0)), ctx());
        assertThat(errors(f)).isEmpty();
    }

    @Test
    void missingId_isError() {
        List<Finding> f = validator.validate(field("", "text", List.of(), null, "antwort"), ctx());
        assertThat(codes(f)).contains("missing-id");
    }

    @Test
    void unknownType_isError() {
        List<Finding> f = validator.validate(field("q1", "radio", List.of("a", "b"), null, null), ctx());
        assertThat(codes(f)).contains("bad-type");
    }

    @Test
    void closedTypeNeedsTwoOptions_isError() {
        List<Finding> f = validator.validate(field("q1", "choice", List.of("a"), null, null), ctx());
        assertThat(codes(f)).contains("missing-options");
    }

    @Test
    void solutionOutOfBounds_isError() {
        List<Finding> f = validator.validate(field("q1", "choice", List.of("a", "b"), 2, null), ctx());
        assertThat(codes(f)).contains("out-of-bounds-solution");
    }

    @Test
    void multiValueMustBeIndexList_isError() {
        List<Finding> f = validator.validate(field("q1", "multi", List.of("a", "b"), List.of(0), "x"), ctx());
        assertThat(codes(f)).contains("bad-value");
    }

    @Test
    void textValueMustBeString_isError() {
        List<Finding> f = validator.validate(field("q1", "text", List.of(), null, 42), ctx());
        assertThat(codes(f)).contains("bad-value");
    }

    @Test
    void badVerdict_isError() {
        List<Finding> f = validator.validate(field("q1", "choice", List.of("a", "b"), 0, 0, "maybe", null), ctx());
        assertThat(codes(f)).contains("bad-verdict");
    }

    @Test
    void missingQuestion_warnsOnly() {
        List<Finding> f =
                validator.validate(new Block.Field("q1", "text", "", List.of(), null, null, null, null), ctx());
        assertThat(errors(f)).isEmpty();
        assertThat(codes(f)).contains("missing-question");
    }

    // ---- helpers -------------------------------------------------------

    private static ValidationContext ctx() {
        return new ValidationContext(PAGE, PAGE + " (vance-field #1)", new FakeDocRefs());
    }

    private static Block.Field field(
            String id, String type, List<String> options, @Nullable Object solution, @Nullable Object value) {
        return field(id, type, options, solution, value, null, null);
    }

    private static Block.Field field(
            String id,
            String type,
            List<String> options,
            @Nullable Object solution,
            @Nullable Object value,
            @Nullable String verdict,
            @Nullable String feedback) {
        return new Block.Field(id, type, "Frage?", options, solution, value, verdict, feedback);
    }

    private static List<Finding> errors(List<Finding> f) {
        return f.stream().filter(x -> x.level() == Finding.Level.ERROR).toList();
    }

    private static Set<String> codes(List<Finding> f) {
        return f.stream().map(Finding::code).collect(java.util.stream.Collectors.toSet());
    }

    /** In-memory DocRefs — the field validator does no reference checks. */
    private static final class FakeDocRefs implements DocRefs {
        private final Set<String> paths = new HashSet<>();
        private final Map<String, String> kinds = new HashMap<>();

        @Override
        public boolean exists(String path) {
            return paths.contains(path);
        }

        @Override
        public @Nullable String kindOf(String path) {
            return kinds.get(path);
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    }
}
