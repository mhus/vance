package de.mhus.vance.brain.wizard;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowUpRendererTest {

    private final PromptTemplateRenderer templateRenderer = new PromptTemplateRenderer();
    private final FollowUpRenderer renderer = new FollowUpRenderer(templateRenderer);

    @Test
    void empty_followUps_produce_empty_string() {
        assertThat(renderer.render("src", List.of(), Map.of(), "de")).isEmpty();
        assertThat(renderer.render("src", null, Map.of(), "de")).isEmpty();
    }

    @Test
    void single_followUp_renders_link() {
        WizardFollowUp fu = new WizardFollowUp(
                "essay-with-recipe",
                Map.of("de", "Aufsatz schreiben", "en", "Write an essay"),
                Map.of("recipe", "{{ outputName }}"),
                null);

        String out = renderer.render(
                "create-essay-recipe",
                List.of(fu),
                Map.of("outputName", "my-essay-recipe"),
                "de");

        assertThat(out)
                .contains("Wenn das erfolgreich erledigt ist")
                .contains("[Aufsatz schreiben]")
                .contains("vance:/wizards/essay-with-recipe?kind=wizard&recipe=my-essay-recipe");
    }

    @Test
    void condition_falsy_omits_followUp() {
        WizardFollowUp fu = new WizardFollowUp(
                "x", Map.of("en", "X"), Map.of(), "shouldShow == \"true\"");

        String out = renderer.render(
                "src",
                List.of(fu),
                Map.of("shouldShow", "false"),
                "en");

        assertThat(out).isEmpty();
    }

    @Test
    void condition_truthy_includes_followUp() {
        WizardFollowUp fu = new WizardFollowUp(
                "x", Map.of("en", "X"), Map.of(), "shouldShow == \"true\"");

        String out = renderer.render(
                "src",
                List.of(fu),
                Map.of("shouldShow", "true"),
                "en");

        assertThat(out).contains("[X]");
    }

    @Test
    void prefill_values_get_url_encoded() {
        WizardFollowUp fu = new WizardFollowUp(
                "x",
                Map.of("en", "X"),
                Map.of("title", "{{ title }}"),
                null);

        String out = renderer.render(
                "src",
                List.of(fu),
                Map.of("title", "hello world & friends"),
                "en");

        // URLEncoder uses '+' for spaces and percent-encodes '&'.
        assertThat(out).contains("title=hello+world+%26+friends");
    }

    @Test
    void multiple_followUps_each_become_one_bullet() {
        WizardFollowUp a = new WizardFollowUp(
                "wa", Map.of("en", "A"), Map.of(), null);
        WizardFollowUp b = new WizardFollowUp(
                "wb", Map.of("en", "B"), Map.of(), null);

        String out = renderer.render("src", List.of(a, b), Map.of(), "en");

        assertThat(out)
                .contains("- [A](vance:/wizards/wa?kind=wizard)")
                .contains("- [B](vance:/wizards/wb?kind=wizard)");
    }

    @Test
    void de_intro_used_for_de_lang() {
        WizardFollowUp fu = new WizardFollowUp("x", Map.of("de", "X"), Map.of(), null);
        String out = renderer.render("src", List.of(fu), Map.of(), "de");
        assertThat(out).contains("Wenn das erfolgreich erledigt ist");
    }

    @Test
    void en_intro_used_for_unknown_lang() {
        WizardFollowUp fu = new WizardFollowUp("x", Map.of("en", "X"), Map.of(), null);
        String out = renderer.render("src", List.of(fu), Map.of(), "fr");
        assertThat(out).contains("If this succeeded");
    }

    @Test
    void empty_label_falls_back_to_wizard_name() {
        WizardFollowUp fu = new WizardFollowUp(
                "fallback-wizard", Map.of(), Map.of(), null);

        String out = renderer.render("src", List.of(fu), Map.of(), "en");

        assertThat(out).contains("[fallback-wizard]");
    }
}
