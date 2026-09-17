package de.mhus.vance.shared.ursascheduler;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-scheduler} kind — a Ursa
 * scheduler definition. Second member of the {@code vance-*} kind family
 * after {@code vance-workflow}, typing Vance's own configuration documents
 * (later: {@code vance-recipe}, …).
 *
 * <p><b>Kind and location are independent.</b> A document carrying this
 * kind is a scheduler definition wherever it lives; only a document under
 * {@code _vance/scheduler/} is also <em>active</em>, i.e. registered with
 * the {@code TaskScheduler} by {@code UrsaSchedulerService} and firing.
 * Drafts and copies elsewhere in the project are the same kind and get the
 * same validation — the path is not part of the type.
 *
 * <p>Validation delegates to the canonical parser
 * ({@link UrsaSchedulerLoader#parseValidated}), so a finding here means
 * exactly what the bootstrap or {@code scheduler_set} would reject: a
 * missing or ambiguous trigger ({@code cron}/{@code at}), an invalid cron
 * expression, no trigger target ({@code recipe}/{@code workflow}/{@code
 * script}). Deliberately a static call, not the loader bean — the parse
 * never touches {@code documentService}, and the kind must stay checkable
 * in every context that loads {@code vance-shared}.
 */
@Service
public class UrsaSchedulerKindHandler implements KindHandler {

    public static final String KIND = UrsaSchedulerLoader.KIND;

    /** Fallback name for the parse-error message when the path is unknown. */
    private static final String ANONYMOUS = "scheduler";

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        try {
            UrsaSchedulerLoader.parseValidated(schedulerName(ctx.docPath()), content);
            return List.of();
        } catch (UrsaSchedulerLoader.SchedulerParseException e) {
            return List.of(Finding.error(target, "vance-scheduler-parse", e.getMessage()));
        }
    }

    /**
     * The scheduler name the parser reports in its messages: the file stem,
     * the same derivation {@code UrsaSchedulerLoader} applies to an active
     * document's path. Blank / directory-only paths fall back to a
     * placeholder — the name never affects whether the body validates.
     */
    private static String schedulerName(String docPath) {
        if (StringUtils.isBlank(docPath)) return ANONYMOUS;
        String stem = StringUtils.substringAfterLast(docPath, "/");
        if (stem.isEmpty()) stem = docPath;
        stem = StringUtils.removeEnd(stem, UrsaSchedulerLoader.SCHEDULER_PATH_SUFFIX);
        return stem.isBlank() ? ANONYMOUS : stem;
    }
}
