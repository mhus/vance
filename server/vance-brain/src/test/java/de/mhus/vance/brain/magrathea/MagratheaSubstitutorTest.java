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

    @Test
    void substituteSpec_deepResolvesStringsInMapsAndLists() {
        Map<String, Object> spec = Map.of(
                "command", "review ${params.pr_url}",
                "args", List.of("--summary", "${state.review_summary}"),
                "nested", Map.of("k", "${state.score}"),
                "count", 42);

        Map<String, Object> out = subst.substituteSpec(spec);

        assertThat(out.get("command")).isEqualTo("review https://x/pull/1");
        assertThat(out.get("args")).isEqualTo(List.of("--summary", "LGTM"));
        assertThat(((Map<?, ?>) out.get("nested")).get("k")).isEqualTo("7");
        assertThat(out.get("count")).isEqualTo(42); // non-string untouched
    }
}
