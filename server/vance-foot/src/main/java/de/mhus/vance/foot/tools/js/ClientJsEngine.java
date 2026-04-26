package de.mhus.vance.foot.tools.js;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Client-side JavaScript evaluator. Mirrors the brain's
 * {@code de.mhus.vance.brain.tools.js.JsEngine}: probes GraalJS
 * (Polyglot) at startup and falls back to Mozilla Rhino, with a
 * graceful no-engine state for stripped runtimes.
 *
 * <p>Sandbox is {@code allowAllAccess(false)} — best-effort, not a
 * hard security boundary. The whole point of {@code client_*} tools
 * is to act on the user's machine, so escapes here are at most a
 * minor risk over a deliberate {@code client_exec_run}.
 */
@Service
@Slf4j
public class ClientJsEngine {

    public enum Mode { GRAAL, RHINO, NONE }

    private Mode mode = Mode.NONE;

    @PostConstruct
    void detect() {
        if (tryGraal()) {
            mode = Mode.GRAAL;
            log.info("ClientJsEngine: using GraalJS (polyglot)");
            return;
        }
        if (tryRhino()) {
            mode = Mode.RHINO;
            log.info("ClientJsEngine: using Rhino (GraalJS unavailable)");
            return;
        }
        log.warn("ClientJsEngine: no JavaScript engine available — "
                + "client_javascript will fail calls");
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Evaluates {@code code} and returns the string form of the last
     * expression's value. Failures come back as {@code "ERROR: …"}
     * strings so the LLM sees the problem in the tool result rather
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
            log.debug("ClientJsEngine: GraalJS unavailable: {}", t.toString());
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
            log.debug("ClientJsEngine: Rhino unavailable: {}", t.toString());
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
