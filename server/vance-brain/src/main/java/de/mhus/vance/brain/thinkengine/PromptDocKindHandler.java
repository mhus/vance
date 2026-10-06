package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-prompt} kind — one engine prompt
 * of the tiered document cascade ({@code _vance/prompts/<engine>-prompt.md}
 * and the Arthur plan-mode variants {@code …-exploring/-planning/-executing},
 * spec {@code specification/public/prompts-and-manuals.md}). Tenants override
 * the bundled prompts by placing matching files in their {@code _vance}
 * project; recipes swap the paths per process.
 *
 * <p><b>Location, not body, is the marker</b> — every document under
 * {@code _vance/prompts/} is a prompt override candidate, whatever engine or
 * recipe points at it. A body detector would claim nothing useful: the body
 * is a Pebble template in Markdown clothing.
 *
 * <p><b>Unlike the theme kinds, this handler validates — because the
 * pipeline is not fail-open.</b> The resolver returns the document text
 * whatever it contains, and the turn renders it with Pebble: a template
 * syntax error fails the <em>turn</em>, not the fallback. A finding here is
 * the difference between an edit-time error message and every chat of the
 * tenant failing at spawn. A blank override is a hint, not an error — the
 * resolver filters blank content and falls back to the bundled prompt, so
 * the document exists but does nothing.
 */
@Service
public class PromptDocKindHandler implements KindHandler {

    public static final String KIND = "vance-prompt";

    /** Document path prefix of the engine prompt cascade. */
    static final String PROMPT_PATH_PREFIX = "_vance/prompts/";

    private final PromptTemplateRenderer templateRenderer;

    public PromptDocKindHandler(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The prompt folder is the marker: every document under
     * {@code _vance/prompts/} is a prompt override candidate.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(PROMPT_PATH_PREFIX);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        if (StringUtils.isBlank(content)) {
            // The resolver filters blank content — the document exists but
            // the bundled prompt keeps serving. Worth saying, not failing.
            return List.of(Finding.warning(
                    target, "prompt-blank", "a blank override is ignored — the bundled prompt keeps serving"));
        }
        List<Finding> findings = new ArrayList<>();
        try {
            templateRenderer.compile(content);
        } catch (RuntimeException e) {
            findings.add(Finding.error(
                    target,
                    "prompt-template",
                    "the prompt is a Pebble template and this one does not compile: " + e.getMessage()));
        }
        return findings;
    }
}
