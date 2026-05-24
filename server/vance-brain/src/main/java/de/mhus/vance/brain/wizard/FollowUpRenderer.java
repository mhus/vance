package de.mhus.vance.brain.wizard;

import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.form.LocalizedTexts;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the trailing LLM-instruction block that suggests follow-up
 * wizards. Extracted from {@link WizardController} so the rendering
 * logic is unit-testable without a Spring context or an HTTP body.
 *
 * <p>Output shape (German example):
 * <pre>
 *
 * Wenn das erfolgreich erledigt ist, biete dem User folgende ...
 * - [Aufsatz schreiben](vance:/wizards/essay-with-recipe?kind=wizard&recipe=my-recipe)
 * </pre>
 *
 * <p>Empty string when the wizard declares no follow-ups, or every
 * condition evaluates falsy. The host concatenates unconditionally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowUpRenderer {

    private final PromptTemplateRenderer templateRenderer;

    /**
     * @param wizardName  source wizard name — only used in log messages
     * @param followUps   declared follow-ups
     * @param ctx         the Pebble context already used for the main promptTemplate
     *                    (form values + lang / user / project)
     * @param lang        resolved language for the intro line
     */
    public String render(
            String wizardName,
            List<WizardFollowUp> followUps,
            Map<String, Object> ctx,
            String lang) {
        if (followUps == null || followUps.isEmpty()) return "";
        List<String> bullets = new ArrayList<>();
        for (WizardFollowUp fu : followUps) {
            if (!evaluateCondition(fu.condition(), ctx, wizardName)) continue;
            String label = LocalizedTexts.resolve(fu.label(), lang);
            if (label.isEmpty()) label = fu.wizard();
            Map<String, String> prefill = renderPrefill(fu, ctx, wizardName);
            String uri = buildWizardUri(fu.wizard(), prefill);
            bullets.add("- [" + label + "](" + uri + ")");
        }
        if (bullets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n").append(intro(lang)).append('\n');
        for (String b : bullets) sb.append(b).append('\n');
        return sb.toString();
    }

    private boolean evaluateCondition(@Nullable String condition, Map<String, Object> ctx, String wizardName) {
        if (condition == null || condition.isBlank()) return true;
        String probe = "{% if " + condition + " %}1{% endif %}";
        try {
            String out = templateRenderer.render(probe, ctx);
            return out != null && !out.isBlank();
        } catch (PromptTemplateException e) {
            log.warn("Wizard '{}' follow-up condition failed: {}", wizardName, e.getMessage());
            return false;
        }
    }

    private Map<String, String> renderPrefill(
            WizardFollowUp fu, Map<String, Object> ctx, String wizardName) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fu.prefill().entrySet()) {
            try {
                String val = templateRenderer.render(e.getValue(), ctx);
                out.put(e.getKey(), val == null ? "" : val);
            } catch (PromptTemplateException ex) {
                log.warn("Wizard '{}' follow-up prefill '{}' failed: {}",
                        wizardName, e.getKey(), ex.getMessage());
                out.put(e.getKey(), "");
            }
        }
        return out;
    }

    private static String buildWizardUri(String wizard, Map<String, String> prefill) {
        StringBuilder sb = new StringBuilder("vance:/wizards/")
                .append(urlEncode(wizard))
                .append("?kind=wizard");
        for (Map.Entry<String, String> e : prefill.entrySet()) {
            sb.append('&').append(urlEncode(e.getKey()))
              .append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String intro(String lang) {
        return switch (lang) {
            case "de" -> "Wenn das erfolgreich erledigt ist, biete dem User folgende Folge-Aktion(en) als Markdown-Link an:";
            default -> "If this succeeded, offer the user the following follow-up action(s) as a markdown link:";
        };
    }
}
