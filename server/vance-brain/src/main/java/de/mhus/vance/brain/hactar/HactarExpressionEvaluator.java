package de.mhus.vance.brain.hactar;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

/**
 * SpEL-based condition evaluator for {@code condition_task} (and
 * future {@code if:} guards). Sandboxed via three switches on the
 * evaluation context (plan §6.5):
 *
 * <ul>
 *   <li>{@code TypeLocator} throws — blocks {@code T(...)} expressions.</li>
 *   <li>{@code ConstructorResolvers} empty — blocks {@code new ...}.</li>
 *   <li>{@code MethodResolvers} empty — blocks arbitrary method calls.
 *       Operators ({@code ==}, {@code in}, {@code matches}, {@code &&},
 *       …) are handled inside SpEL and need no resolver.</li>
 * </ul>
 *
 * <p>Variables in expressions are accessed with the SpEL {@code #}-syntax:
 *
 * <pre>
 *   #state.plan_output.risk == 'low'
 *   #params.tier in {'free', 'pro'}
 *   #tasks.plan.output.risk matches 'low|medium'
 * </pre>
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class HactarExpressionEvaluator {

    private final ExpressionParser parser;

    public HactarExpressionEvaluator() {
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
        log.warn("Hactar expression '{}' returned non-boolean {} — treating as false",
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
        StandardEvaluationContext ctx = sandboxedContext(params, vars, tasks);
        try {
            Expression parsed = parser.parseExpression(expression);
            return parsed.getValue(ctx);
        } catch (ExpressionException ex) {
            throw new HactarExpressionException(
                    "Cannot evaluate expression '" + expression + "': " + ex.getMessage(), ex);
        }
    }

    private static StandardEvaluationContext sandboxedContext(
            Map<String, Object> params,
            Map<String, Object> vars,
            Map<String, Object> tasks) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("params", params == null ? Map.of() : params);
        ctx.setVariable("state",  vars   == null ? Map.of() : vars);
        ctx.setVariable("tasks",  tasks  == null ? Map.of() : tasks);

        // Block T(...) reflection on classes.
        ctx.setTypeLocator(name -> {
            throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, name);
        });
        // Block new ... constructor invocation.
        ctx.setConstructorResolvers(List.of());
        // Block arbitrary method calls. SpEL operators (==, in, matches,
        // &&, ||, !, <, ≥) are evaluated inside SpEL without resolvers,
        // so this only blocks .foo() style invocations.
        ctx.setMethodResolvers(List.of());
        return ctx;
    }

    /** Surfacing-friendly wrapper for SpEL parse/eval failures. */
    public static class HactarExpressionException extends RuntimeException {
        public HactarExpressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
