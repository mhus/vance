package de.mhus.vance.brain.magrathea;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;

/**
 * SpEL-based condition evaluator for {@code condition_task} (and
 * future {@code if:} guards). Sandboxed via a
 * {@link SimpleEvaluationContext} (plan §6.5): it exposes <em>only</em>
 * read-only property access — no type references ({@code T(...)}), no
 * constructors ({@code new ...}), no method invocation ({@code .foo()}),
 * and no bean references. Expression authors are project-writing users,
 * so this is the documented-safe way to run untrusted SpEL: unlike the
 * old {@code StandardEvaluationContext}-with-switches approach, the
 * default reflective property navigation ({@code #state.class.classLoader…})
 * is off by construction rather than patched shut. SpEL operators
 * ({@code ==}, {@code in}, {@code matches}, {@code &&}, indexers …) are
 * evaluated in the AST and need no context support.
 *
 * <p>A {@link MapAccessor} is registered so the {@code #}-variables (all
 * {@code Map}s) can be navigated with dotted property syntax as well as
 * the indexer syntax:
 *
 * <pre>
 *   #state.plan_output.risk == 'low'        // dotted (MapAccessor)
 *   #state['plan_output']['risk'] == 'low'  // indexer (built-in)
 *   #params.tier in {'free', 'pro'}
 *   #tasks.plan.output.risk matches 'low|medium'
 * </pre>
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaExpressionEvaluator {

    private final ExpressionParser parser;

    public MagratheaExpressionEvaluator() {
        SpelParserConfiguration cfg = new SpelParserConfiguration(
                org.springframework.expression.spel.SpelCompilerMode.IMMEDIATE,
                this.getClass().getClassLoader(),
                /* autoGrowNullReferences */ false,
                /* autoGrowCollections */    false,
                /* maximumAutoGrowSize */    0);
        this.parser = new SpelExpressionParser(cfg);
    }

    /**
     * Evaluate {@code expression} as a boolean against the supplied
     * variable map. A non-Boolean result is treated as {@code false}
     * with a WARN — the user wrote a non-predicate; we don't want
     * silent truthy-coercion drift.
     *
     * @param tasks Optional map of task-output snapshots, keyed by
     *              state name. Empty for early evaluations.
     */
    public boolean evaluateBoolean(
            String expression,
            Map<String, Object> params,
            Map<String, Object> vars,
            Map<String, Object> tasks) {
        Object raw = evaluate(expression, params, vars, tasks);
        if (raw instanceof Boolean b) return b;
        log.warn("Magrathea expression '{}' returned non-boolean {} — treating as false",
                expression, raw == null ? "null" : raw.getClass().getSimpleName());
        return false;
    }

    /**
     * Evaluate the raw expression result. Used by tests and by callers
     * that want the typed value (e.g. {@code condition_task} doesn't
     * — but future {@code if:} guards on other types might).
     */
    public Object evaluate(
            String expression,
            Map<String, Object> params,
            Map<String, Object> vars,
            Map<String, Object> tasks) {
        EvaluationContext ctx = sandboxedContext(params, vars, tasks);
        try {
            Expression parsed = parser.parseExpression(expression);
            return parsed.getValue(ctx);
        } catch (ExpressionException ex) {
            throw new MagratheaExpressionException(
                    "Cannot evaluate expression '" + expression + "': " + ex.getMessage(), ex);
        }
    }

    private static EvaluationContext sandboxedContext(
            Map<String, Object> params,
            Map<String, Object> vars,
            Map<String, Object> tasks) {
        // Read-only data binding + MapAccessor: no methods, no ctors, no
        // type refs, no bean refs, no assignment — only reading properties
        // and map keys off the three exposed variable maps.
        SimpleEvaluationContext ctx = SimpleEvaluationContext
                .forPropertyAccessors(
                        new MapAccessor(/* allowWrite */ false),
                        DataBindingPropertyAccessor.forReadOnlyAccess())
                .build();
        ctx.setVariable("params", params == null ? Map.of() : params);
        ctx.setVariable("state",  vars   == null ? Map.of() : vars);
        ctx.setVariable("tasks",  tasks  == null ? Map.of() : tasks);
        return ctx;
    }

    /** Surfacing-friendly wrapper for SpEL parse/eval failures. */
    public static class MagratheaExpressionException extends RuntimeException {
        public MagratheaExpressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
