package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.brain.ai.ModelSize;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateRendererTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void render_passesLiteralStringsThrough() {
        String out = renderer.render(
                "You are a helpful assistant.",
                Map.of("tier", "small"));
        assertThat(out).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void render_substitutesSimpleVariable() {
        String out = renderer.render(
                "Hello {{ name }}!",
                Map.of("name", "Arthur"));
        assertThat(out).isEqualTo("Hello Arthur!");
    }

    @Test
    void render_picksSmallBranchWhenTierIsSmall() {
        String tpl = "{% if tier == \"small\" %}SMALL{% else %}LARGE{% endif %}";
        assertThat(renderer.render(tpl, Map.of("tier", "small"))).isEqualTo("SMALL");
        assertThat(renderer.render(tpl, Map.of("tier", "large"))).isEqualTo("LARGE");
    }

    @Test
    void render_jinjaCompatMatchingTestWorks() {
        String tpl = "{% if model is matching(\"gemini-.*flash.*\") %}flash{% else %}other{% endif %}";
        assertThat(renderer.render(tpl, Map.of("model", "gemini-2.5-flash")))
                .isEqualTo("flash");
        assertThat(renderer.render(tpl, Map.of("model", "claude-opus-4-7")))
                .isEqualTo("other");
    }

    @Test
    void render_undefinedVariableRendersEmptyInLenientMode() {
        String out = renderer.render("X={{ missing }}Y", Map.of());
        assertThat(out).isEqualTo("X=Y");
    }

    @Test
    void render_combinedTierAndModeBranches() {
        String tpl =
                "{% if mode == \"EXPLORING\" %}exploring{% elseif tier == \"small\" %}"
                        + "small-normal{% else %}large-normal{% endif %}";
        assertThat(renderer.render(tpl, Map.of("mode", "EXPLORING", "tier", "small")))
                .isEqualTo("exploring");
        assertThat(renderer.render(tpl, Map.of("mode", "NORMAL", "tier", "small")))
                .isEqualTo("small-normal");
        assertThat(renderer.render(tpl, Map.of("mode", "NORMAL", "tier", "large")))
                .isEqualTo("large-normal");
    }

    @Test
    void render_paramMapAccessWorks() {
        String out = renderer.render(
                "max={{ params.maxIterations }}",
                Map.of("params", Map.of("maxIterations", 6)));
        assertThat(out).isEqualTo("max=6");
    }

    @Test
    void render_returnsNullForNullInput() {
        assertThat(renderer.render(null, Map.of())).isNull();
    }

    @Test
    void render_emptyTemplateReturnsEmpty() {
        assertThat(renderer.render("", Map.of())).isEmpty();
    }

    @Test
    void render_propagatesPebbleSyntaxErrors() {
        assertThatThrownBy(() -> renderer.render("{% if unterminated", Map.of()))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("Pebble");
    }

    @Test
    void render_propagatesInvalidRegexFromMatchingTest() {
        assertThatThrownBy(() ->
                renderer.render(
                        "{% if model is matching(\"[invalid\") %}x{% endif %}",
                        Map.of("model", "anything")))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("invalid regex");
    }

    @Test
    void compile_acceptsValidTemplate() {
        renderer.compile("{% if tier == \"small\" %}x{% endif %}");
        // no exception → success
    }

    @Test
    void compile_throwsOnSyntaxError() {
        assertThatThrownBy(() -> renderer.compile("{% if %}"))
                .isInstanceOf(PromptTemplateException.class);
    }

    @Test
    void compile_skipsNullAndEmpty() {
        renderer.compile(null);
        renderer.compile("");
        // no exception → success
    }

    @Test
    void contextBuilder_exposesAllStandardKeys() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.SMALL)
                .model("claude-sonnet-4-6")
                .provider("anthropic")
                .mode(ProcessMode.EXPLORING)
                .profile("foot")
                .recipe("default")
                .engine("ford")
                .lang("de")
                .params(Map.of("maxIterations", 6))
                .build();

        assertThat(ctx)
                .containsEntry("tier", "small")
                .containsEntry("model", "claude-sonnet-4-6")
                .containsEntry("provider", "anthropic")
                .containsEntry("mode", "EXPLORING")
                .containsEntry("profile", "foot")
                .containsEntry("recipe", "default")
                .containsEntry("engine", "ford")
                .containsEntry("lang", "de");
        assertThat(ctx.get("params")).isInstanceOf(Map.class);
    }

    @Test
    void contextBuilder_skipsNullsSoTemplatesSeeUndefined() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier((ModelSize) null)
                .model(null)
                .lang("")
                .build();

        assertThat(ctx).isEmpty();
    }

    @Test
    void contextBuilder_renderRoundTripWithModelSizeEnum() {
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.SMALL)
                .build();

        assertThat(renderer.render(
                "{% if tier == \"small\" %}small{% endif %}", ctx))
                .isEqualTo("small");
    }

    // ── active-app block: map-valued context var guarded by presence ──
    // Regression for the 2026-07-02 crash: `{% if activeApp %}` on a
    // Map throws "Unsupported value type LinkedHashMap" — the block
    // must guard on `is not null`, not on the map's truthiness.

    @Test
    void render_activeAppBlock_rendersWhenMapPresent() {
        String tpl = "{% if activeApp is not null %}App: {{ activeApp.app }}"
                + " @ {{ activeApp.folder }}{% endif %}";
        Map<String, Object> ctx = Map.of(
                "activeApp", Map.of("app", "calendar", "folder", "calendars/q3"));

        assertThat(renderer.render(tpl, ctx)).isEqualTo("App: calendar @ calendars/q3");
    }

    @Test
    void render_activeAppBlock_omittedWhenAbsent() {
        String tpl = "X{% if activeApp is not null %}App: {{ activeApp.app }}{% endif %}Y";
        assertThat(renderer.render(tpl, Map.of())).isEqualTo("XY");
    }

    @Test
    void render_bareIfOnMap_throws_documentingWhyPresenceCheckIsNeeded() {
        // This is the shape that crashed in the prompt templates; kept as
        // an executable note so nobody "simplifies" the guard back.
        assertThatThrownBy(() -> renderer.render(
                "{% if activeApp %}x{% endif %}",
                Map.of("activeApp", Map.of("app", "calendar"))))
                .isInstanceOf(PromptTemplateException.class);
    }

    // ── security contract: templates are untrusted, no method access (F5) ──

    @Test
    void render_reflectionEscapeIsBlocked() {
        // The classic SSTI→RCE entrypoint fails closed: getClass() access is
        // denied and the render throws rather than exposing the Class.
        assertThatThrownBy(() -> renderer.render("{{ s.getClass() }}", Map.of("s", "x")))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void render_deniesArbitraryBeanGetter() {
        // Deny-all method access: the getter getSecret() may not be invoked,
        // so a reachable bean cannot leak its state — fail closed.
        assertThatThrownBy(() -> renderer.render(
                "{{ probe.secret }}", Map.of("probe", new Probe())))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void render_deniesExplicitBeanMethodCall() {
        assertThatThrownBy(() -> renderer.render(
                "{{ probe.compute() }}", Map.of("probe", new Probe())))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void render_treatsVariableValuesAsDataNotTemplate() {
        // A value that looks like a template must NOT be re-evaluated.
        assertThat(renderer.render("{{ v }}", Map.of("v", "{{ 7 * 7 }}")))
                .isEqualTo("{{ 7 * 7 }}");
    }

    /** Public bean used to prove getter/method access is denied. */
    public static final class Probe {
        public String getSecret() {
            return "leaked";
        }

        public String compute() {
            return "computed";
        }
    }
}
