package de.mhus.vance.shared.magrathea;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-workflow} kind — a Magrathea
 * workflow definition. First member of the {@code vance-*} kind family that
 * types Vance's own configuration documents (later: {@code vance-recipe},
 * {@code vance-scheduler}, …).
 *
 * <p><b>Kind and location are independent.</b> A document carrying this kind
 * is a workflow definition wherever it lives; only a document under
 * {@code _vance/workflows/} is also <em>active</em>, i.e. resolvable by name
 * through {@link MagratheaWorkflowLoader} and startable. Drafts, templates
 * and archived copies elsewhere in the project are the same kind and get the
 * same validation — the path is not part of the type.
 *
 * <p>Validation delegates to the canonical parser
 * ({@link MagratheaWorkflowLoader#parseYaml}), so a finding here means exactly
 * what a {@code start()} would reject: missing {@code start:}/{@code states:},
 * unknown task type or error kind, a transition pointing at a state that is
 * not declared. Deliberately a static call, not the loader bean — the bean is
 * gated on {@code vance.services.magrathea} and the kind must stay known and
 * checkable even where the engine is switched off.
 */
@Service
public class WorkflowKindHandler implements KindHandler {

    public static final String KIND = "vance-workflow";

    /** Fallback name for the parse-error message when the path is unknown. */
    private static final String ANONYMOUS = "workflow";

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        try {
            MagratheaWorkflowLoader.parseYaml(workflowName(ctx.docPath()), content);
            return List.of();
        } catch (MagratheaWorkflowParseException e) {
            return List.of(Finding.error(target, "vance-workflow-parse", e.getMessage()));
        }
    }

    /**
     * The workflow name the parser reports in its messages: the file stem, the
     * same derivation {@link MagratheaWorkflowLoader} applies to an active
     * document's path. Blank / directory-only paths fall back to a placeholder
     * — the name never affects whether the body validates.
     */
    private static String workflowName(String docPath) {
        if (StringUtils.isBlank(docPath)) return ANONYMOUS;
        String stem = StringUtils.substringAfterLast(docPath, "/");
        if (stem.isEmpty()) stem = docPath;
        stem = StringUtils.removeEnd(stem, MagratheaWorkflowLoader.WORKFLOW_PATH_SUFFIX);
        return stem.isBlank() ? ANONYMOUS : stem;
    }
}
