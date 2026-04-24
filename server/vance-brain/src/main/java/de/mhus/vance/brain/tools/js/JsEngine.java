package de.mhus.vance.brain.tools.js;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * JavaScript evaluator with GraalJS as primary and Mozilla Rhino as
 * fallback. Ported from the ai-assistant prototype.
 *
 * <p>The engine is detected at startup: GraalJS is tried first via the
 * Polyglot API, and if it can't initialise (Polyglot missing, no JS
 * language on the classpath, or the JVM can't host it) Rhino takes
 * over. Both attempts swallow their own failures — an evaluator that
 * can't start must not crash the whole brain pod.
 *
 * <p>GraalJS runs with {@code allowAllAccess(false)}. The sandbox is
 * not a hard security boundary — untrusted input should still be
 * validated upstream — but it prevents accidental Java interop.
 */
@Service
@Slf4j
public class JsEngine {

    public enum Mode { GRAAL, RHINO, NONE }

    private Mode mode = Mode.NONE;

    @PostConstruct
    void detect() {
        if (tryGraal()) {
            mode = Mode.GRAAL;
            log.info("JsEngine: using GraalJS (polyglot)");
            return;
        }
        if (tryRhino()) {
            mode = Mode.RHINO;
            log.info("JsEngine: using Rhino (GraalJS unavailable)");
            return;
        }
        log.warn("JsEngine: no JavaScript engine available — javascript tool will fail calls");
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Evaluates {@code code} and returns the string form of the last
     * expression's value. Returns a {@code "ERROR: …"} string on any
     * failure so the LLM sees the problem in the tool result rather
     * than crashing the turn.
     */
    public String eval(String code) {
        return switch (mode) {
            case GRAAL -> evalGraal(code);
            case RHINO -> evalRhino(code);
            case NONE -> "ERROR: no JavaScript engine available";
        };
    }

    private boolean tryGraal() {
        try {
            Class.forName("org.graalvm.polyglot.Context");
            try (org.graalvm.polyglot.Context ctx =
                    org.graalvm.polyglot.Context.newBuilder("js").build()) {
                ctx.eval("js", "1+1");
            }
            return true;
        } catch (Throwable t) {
            log.debug("JsEngine: GraalJS unavailable: {}", t.toString());
            return false;
        }
    }

    private boolean tryRhino() {
        try {
            Class.forName("org.mozilla.javascript.engine.RhinoScriptEngineFactory");
            javax.script.ScriptEngine e =
                    new org.mozilla.javascript.engine.RhinoScriptEngineFactory().getScriptEngine();
            e.eval("1+1");
            return true;
        } catch (Throwable t) {
            log.debug("JsEngine: Rhino unavailable: {}", t.toString());
            return false;
        }
    }

    private String evalGraal(String code) {
        try (org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("js")
                .allowAllAccess(false)
                .build()) {
            org.graalvm.polyglot.Value value = ctx.eval("js", code);
            return value == null ? "null" : value.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private String evalRhino(String code) {
        try {
            javax.script.ScriptEngine engine =
                    new org.mozilla.javascript.engine.RhinoScriptEngineFactory().getScriptEngine();
            Object result = engine.eval(code);
            return String.valueOf(result);
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
