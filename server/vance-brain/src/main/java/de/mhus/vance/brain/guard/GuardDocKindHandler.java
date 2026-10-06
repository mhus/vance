package de.mhus.vance.brain.guard;

import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-guard} kind — one reusable
 * Shooty guard script under {@code _vance/guards/<name>.js}. Path-based
 * marker: the guard tree is the identity; recipes reference the script by
 * cascade path from their {@code guard:} block, nothing looks at kind tags.
 *
 * <p><b>Validation is the parse-only GraalJS check.</b> A guard script is
 * wired by a recipe and can fire at any point — and the points disagree
 * on failure: STOP/TERMINATE and START are fail-open (a script error is
 * absorbed), but COMMAND is fail-closed (a failing script fails the
 * command). A syntax error is therefore not always absorbed, and even
 * where it is, it turns a configured guard into a silent no-op. The
 * handler delegates to {@link JsValidationService} — the same
 * {@code Source.newBuilder + Context.parse} the executor would run — so
 * an edit-time finding is exactly what the engine would refuse to
 * evaluate. Semantic problems (bindings, runtime errors, loops) stay
 * runtime, as the validator documents.
 */
@Service
public class GuardDocKindHandler implements KindHandler {

    public static final String KIND = "vance-guard";

    /** Guard scripts live here by convention; recipes cite the path verbatim. */
    public static final String GUARD_PATH_ROOT = "_vance/guards/";

    private final JsValidationService jsValidationService;

    public GuardDocKindHandler(JsValidationService jsValidationService) {
        this.jsValidationService = jsValidationService;
    }

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(GUARD_PATH_ROOT);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        List<Finding> findings = new ArrayList<>();
        var result = jsValidationService.validate(content, target);
        for (JsValidationService.JsValidationError error : result.errors()) {
            findings.add(Finding.error(
                    target,
                    "guard-js-syntax",
                    "the guard never runs: " + error.message() + " (line " + error.line() + ", column " + error.column()
                            + ")"));
        }
        return findings;
    }
}
