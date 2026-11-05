package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the ${params.X}/${state.X} spec interpolation (code-review Phase 2
 * HIGH #2): the task-to-task dataflow that was documented but never
 * implemented.
 */
class MagratheaSubstitutorTest {

    private final MagratheaSubstitutor subst = new MagratheaSubstitutor(
            Map.of("pr_url", "https://x/pull/1", "repo", Map.of("owner", "acme")),
            Map.of("review_summary", "LGTM", "score", 7));

    @Test
    void resolvesParamsAndState() {
        assertThat(subst.apply("PR ${params.pr_url} — ${state.review_summary}"))
                .isEqualTo("PR https://x/pull/1 — LGTM");
    }

    @Test
    void resolvesNestedParam() {
        assertThat(subst.apply("owner=${params.repo.owner}")).isEqualTo("owner=acme");
    }

    @Test
    void missingKeyResolvesToEmpty() {
        assertThat(subst.apply("[${params.nope}][${state.gone}]")).isEqualTo("[][]");
    }

    @Test
    void nonPlaceholderStringPassesThroughUnchanged() {
        // A SpEL gate expression has no ${} — must not be touched.
        assertThat(subst.apply("#state.score > 5")).isEqualTo("#state.score > 5");
    }

    private final MagratheaSubstitutor typedSubst = new MagratheaSubstitutor(
            Map.of("tickets", java.util.List.of(Map.of("id", "A-1"), Map.of("id", "B-2")), "limit", 5),
            Map.of("list", java.util.List.of("x", "y", "z"), "obj", Map.of("a", 1), "flag", true));

    @Test
    void aWholePlaceholderKeepsItsTypeUnderATypedKey() {
        Map<String, Object> out = typedSubst.substituteSpec(
                Map.of(
                        "params",
                        Map.of(
                                "list", "${state.list}",
                                "obj", "${state.obj}",
                                "flag", "${state.flag}",
                                "limit", "${params.limit}")),
                java.util.Set.of("params"));

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) out.get("params");
        assertThat(params.get("list")).isEqualTo(java.util.List.of("x", "y", "z"));
        assertThat(params.get("obj")).isEqualTo(Map.of("a", 1));
        assertThat(params.get("flag")).isEqualTo(true);
        assertThat(params.get("limit")).isEqualTo(5);
    }

    @Test
    void theSameValueIsTextUnderAnyOtherKey() {
        // A prompt, a title, a body: consumers read strings, so nothing changes for them.
        Map<String, Object> out = typedSubst.substituteSpec(
                Map.of("body", "${state.obj}", "params", Map.of("list", "${state.list}")), java.util.Set.of("params"));

        assertThat(out.get("body")).isEqualTo("{a=1}");
    }

    @Test
    void aPlaceholderWithTextAroundItIsTextEvenUnderATypedKey() {
        Map<String, Object> out = typedSubst.substituteSpec(
                Map.of("params", Map.of("note", "tickets: ${state.list}")), java.util.Set.of("params"));

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) out.get("params");
        assertThat(params.get("note")).isEqualTo("tickets: [x, y, z]");
    }

    @Test
    void aMissingWholePlaceholderUnderATypedKeyIsEmptyText() {
        Map<String, Object> out =
                typedSubst.substituteSpec(Map.of("items", "${state.nope}"), java.util.Set.of("items"));

        assertThat(out.get("items")).isEqualTo("");
    }

    @Test
    void anElementOfAListIsReachedByAFixedIndex() {
        assertThat(typedSubst.apply("${state.list[0]}-${state.list[2]}")).isEqualTo("x-z");
        assertThat(typedSubst.apply("${params.tickets[1].id}")).isEqualTo("B-2");

        Map<String, Object> out = typedSubst.substituteSpec(
                Map.of("params", Map.of("first", "${params.tickets[0]}")), java.util.Set.of("params"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) out.get("params");
        assertThat(params.get("first")).isEqualTo(Map.of("id", "A-1"));
    }

    @Test
    void anIndexOutsideTheListOrOnSomethingThatIsNotAListIsEmpty() {
        assertThat(typedSubst.apply("[${state.list[3]}][${state.list[-1]}][${state.obj[0]}][${state.flag[0]}]"))
                .isEqualTo("[][][][]");
    }

    @Test
    void aReferenceThatIsNotOfTheShapeIsEmptyRatherThanAnError() {
        assertThat(typedSubst.apply("[${state.list[x]}][${state.list[0}][${state.list[0]x}][${state..list}]"))
                .isEqualTo("[][][][]");
    }

    @Test
    void aDynamicIndexIsNotEvaluated() {
        assertThat(typedSubst.apply("${state.list[${state.flag}]}")).isEqualTo("]}");
    }

    @Test
    void substituteSpec_deepResolvesStringsInMapsAndLists() {
        Map<String, Object> spec = Map.of(
                "command",
                "review ${params.pr_url}",
                "args",
                List.of("--summary", "${state.review_summary}"),
                "nested",
                Map.of("k", "${state.score}"),
                "count",
                42);

        Map<String, Object> out = subst.substituteSpec(spec);

        assertThat(out.get("command")).isEqualTo("review https://x/pull/1");
        assertThat(out.get("args")).isEqualTo(List.of("--summary", "LGTM"));
        assertThat(((Map<?, ?>) out.get("nested")).get("k")).isEqualTo("7");
        assertThat(out.get("count")).isEqualTo(42); // non-string untouched
    }
}
