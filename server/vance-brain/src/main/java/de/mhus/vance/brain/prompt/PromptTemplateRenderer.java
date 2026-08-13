package de.mhus.vance.brain.prompt;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.loader.StringLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Renders Pebble templates against a context map. Used by recipes,
 * engine-default prompts, and profile-block appends to compute the
 * final system-prompt text once tier/mode/profile are known.
 *
 * <p>Engine setup:
 * <ul>
 *   <li>{@link StringLoader} — templates are passed as raw strings,
 *       not file paths. Pebble's internal LRU cache keys compiled
 *       templates by the source string itself, so identical inputs
 *       across turns hit the cache without further bookkeeping.</li>
 *   <li>{@code strictVariables=false} — undefined variables render
 *       as empty strings. Suited to prompts where optional context
 *       (e.g. {@code lang} before the language settings land) should
 *       be silently absent rather than blow up the turn.</li>
 *   <li>{@code autoEscaping=false} — these are LLM prompts, not HTML.
 *       Escaping {@code <} into {@code &lt;} would break code blocks
 *       and structured instructions.</li>
 *   <li>{@link JinjaCompatExtension} — registers the {@code is matching(...)}
 *       test so Jinja2-trained LLMs produce templates that work without
 *       hand-translation.</li>
 * </ul>
 *
 * <p>Regex matches use the Jinja2-style test:
 * {@code {% if model is matching("gemini-.*flash.*") %}}. Pebble has no
 * native {@code matches} operator (that's a Twig idiom in PHP-land) —
 * if a template author writes one, they get a parse error. Conditions
 * use {@code elseif} (not Jinja2's {@code elif}); the Slartibartfast
 * teaching prompt summarises this for LLM-authored recipes.
 */
@Service
public class PromptTemplateRenderer {

    private final PebbleEngine engine;
    private final PebbleEngine structuredEngine;

    public PromptTemplateRenderer() {
        this.engine = buildEngine(/*trimNewlineAfterTag*/ true);
        this.structuredEngine = buildEngine(/*trimNewlineAfterTag*/ false);
    }

    private static PebbleEngine buildEngine(boolean trimNewlineAfterTag) {
        return new PebbleEngine.Builder()
                .loader(new StringLoader())
                .strictVariables(false)
                .autoEscaping(false)
                // Pebble's default: the newline directly after a tag is
                // swallowed. Right for prose — `{% if %}` blocks would
                // otherwise leave a blank line in every prompt. Fatal for
                // structured output, where `title: {{ topic }}` on its own
                // line silently glues the next line onto the value. See
                // #renderStructured.
                .newLineTrimming(trimNewlineAfterTag)
                // Template bodies are effectively untrusted (any DB document
                // author can supply them). Deny ALL method access so a
                // {{ x.getClass()… }} reflection escape or any getter/method
                // call on a context object is impossible, regardless of what
                // the render context holds (code-review F5). Map/List access
                // and filters resolve through separate paths and keep working.
                .methodAccessValidator(new DenyMethodAccessValidator())
                .extension(new JinjaCompatExtension())
                .build();
    }

    /**
     * Renders {@code template} against {@code context}. Returns
     * {@code null} when {@code template} is {@code null} (callers
     * usually want to fall through to the engine default). Empty
     * templates are returned verbatim — Pebble would also produce an
     * empty string, but skipping the engine round-trip is cheaper for
     * the common no-override case.
     *
     * @throws PromptTemplateException on Pebble compile or render error.
     *         The message preserves Pebble's line-number diagnostic.
     */
    public @Nullable String render(@Nullable String template, Map<String, Object> context) {
        return render(template, context, engine);
    }

    /**
     * Like {@link #render} but keeps the newline that follows a tag.
     *
     * <p>For output whose <em>line structure carries meaning</em> — a YAML
     * document, markdown front-matter — the prose default is destructive:
     * {@code title: {{ topic }}} on its own line renders as
     * {@code title: Sprint Review---}, because the newline after the tag is
     * gone and the next line runs on. The result is not ugly, it is invalid.
     *
     * <p>Keeping the newline costs a blank line where a block tag sat alone
     * on a line, which YAML and markdown both ignore. Trading a cosmetic
     * blank line for a syntax error is the right way round; prompts keep the
     * trimming default because there the trade goes the other way.
     */
    public @Nullable String renderStructured(
            @Nullable String template, Map<String, Object> context) {
        return render(template, context, structuredEngine);
    }

    private @Nullable String render(
            @Nullable String template, Map<String, Object> context, PebbleEngine pebble) {
        if (template == null) return null;
        if (template.isEmpty()) return template;
        try {
            PebbleTemplate compiled = pebble.getTemplate(template);
            // Bounded output: template bodies are untrusted (any DB document
            // author supplies them), so a runaway construct like
            // {% for i in range(0, 10^9) %}x{% endfor %} must not buffer the
            // pod out of memory. The cap aborts the render deterministically
            // once output exceeds MAX_RENDER_CHARS (F5 residual). Far above any
            // legitimate prompt.
            BoundedWriter out = new BoundedWriter(template.length() + 256, MAX_RENDER_CHARS);
            compiled.evaluate(out, context);
            return out.result();
        } catch (PebbleException e) {
            throw new PromptTemplateException(
                    "Pebble render failed: " + collapseMessages(e), e);
        } catch (IOException e) {
            // Only the BoundedWriter's cap throws here now.
            throw new PromptTemplateException("Render aborted: " + e.getMessage(), e);
        }
    }

    /** Max characters a single template render may emit (~2 MB of text). */
    static final int MAX_RENDER_CHARS = 1_000_000;

    /**
     * Accumulating writer that throws once more than {@code max} chars are
     * written, so an untrusted template can't OOM the pod by emitting an
     * unbounded body. Overriding {@code write(char[], off, len)} covers every
     * {@link java.io.Writer} write path.
     */
    private static final class BoundedWriter extends java.io.Writer {
        private final StringBuilder sb;
        private final int max;
        private int count;

        BoundedWriter(int initialCapacity, int max) {
            this.sb = new StringBuilder(Math.max(16, initialCapacity));
            this.max = max;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            count += len;
            if (count > max) {
                throw new IOException(
                        "rendered output exceeds max of " + max + " characters");
            }
            sb.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            // no-op — in-memory buffer
        }

        @Override
        public void close() {
            // no-op — in-memory buffer
        }

        String result() {
            return sb.toString();
        }
    }

    /**
     * Compiles {@code template} without rendering, to surface syntax
     * errors at recipe-load or recipe-create time (fail-fast). No-op
     * for {@code null} or empty input — those carry no syntax to
     * validate, and the recipe schema treats them as "no override".
     *
     * @throws PromptTemplateException on Pebble compile error.
     */
    public void compile(@Nullable String template) {
        if (template == null || template.isEmpty()) return;
        try {
            engine.getTemplate(template);
        } catch (PebbleException e) {
            throw new PromptTemplateException(
                    "Pebble compile failed: " + collapseMessages(e), e);
        }
    }

    /**
     * Walks the exception chain and joins distinct messages with " — ".
     * Pebble wraps inner errors (e.g. our {@code matching()} regex
     * failure) under a generic {@code "Wrong operand(s) type"} outer
     * exception; the root cause carries the actually-useful diagnostic.
     */
    private static String collapseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        String last = null;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null || msg.isBlank() || msg.equals(last)) continue;
            if (sb.length() > 0) sb.append(" — ");
            sb.append(msg);
            last = msg;
        }
        return sb.length() == 0 ? t.getClass().getSimpleName() : sb.toString();
    }
}
