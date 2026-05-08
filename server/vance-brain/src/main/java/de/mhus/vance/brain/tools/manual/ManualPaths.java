package de.mhus.vance.brain.tools.manual;

import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the effective folder list {@link ManualListTool} and
 * {@link ManualReadTool} union over for a running process. Two sources
 * are merged, recipe-first then skills:
 *
 * <ol>
 *   <li>{@code process.engineParams.manualPaths} — recipe-configured
 *       paths. Always come first so the recipe author retains control
 *       over precedence.</li>
 *   <li>{@code skill.manualPaths} for every active skill on the
 *       process. Skills are <em>additive</em> — they can extend the
 *       reachable manual surface but never override the recipe's
 *       ordering.</li>
 * </ol>
 *
 * <p>Path normalisation: every entry is forced to end with
 * {@code "/"} (folder semantics) and de-duplicated while preserving
 * order. Empty / missing on both sides yields an empty result and the
 * calling tool surfaces "no manuals configured" rather than
 * fabricating a default.
 */
final class ManualPaths {

    static final String PARAM_KEY = "manualPaths";

    private static final Logger log = LoggerFactory.getLogger(ManualPaths.class);

    private ManualPaths() {}

    /**
     * Reads the effective path list for {@code ctx}. Requires
     * {@code processId} — manuals are always configured per running
     * process, never at tenant scope.
     */
    static List<String> readFor(
            ToolInvocationContext ctx,
            ThinkProcessService thinkProcessService,
            SkillResolver skillResolver,
            SessionService sessionService) {
        if (ctx == null || ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("manual tools require a process scope");
        }
        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElseThrow(() -> new ToolException(
                        "Process " + ctx.processId() + " not found"));

        Set<String> seen = new LinkedHashSet<>();
        addRecipePaths(process, seen);
        addSkillPaths(process, skillResolver, sessionService, seen);
        return List.copyOf(seen);
    }

    private static void addRecipePaths(ThinkProcessDocument process, Set<String> out) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return;
        Object raw = params.get(PARAM_KEY);
        if (raw == null) return;
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("'" + PARAM_KEY
                    + "' on process must be a list of folder paths");
        }
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) continue;
            out.add(normalise(s));
        }
    }

    private static void addSkillPaths(
            ThinkProcessDocument process,
            SkillResolver skillResolver,
            SessionService sessionService,
            Set<String> out) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return;
        SkillScopeContext scope = scopeFor(process, sessionService);
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref == null || ref.getName() == null) continue;
            try {
                skillResolver.resolve(scope, ref.getName())
                        .ifPresent(skill -> appendSkillPaths(skill, out));
            } catch (UnknownSkillException e) {
                log.warn("ManualPaths: active skill '{}' on process '{}' "
                        + "no longer resolves — skipping",
                        ref.getName(), process.getId());
            }
        }
    }

    private static void appendSkillPaths(ResolvedSkill skill, Set<String> out) {
        if (skill.manualPaths() == null) return;
        for (String p : skill.manualPaths()) {
            if (p == null || p.isBlank()) continue;
            out.add(normalise(p));
        }
    }

    private static String normalise(String raw) {
        String norm = raw.trim();
        return norm.endsWith("/") ? norm : norm + "/";
    }

    private static SkillScopeContext scopeFor(
            ThinkProcessDocument process, SessionService sessionService) {
        SessionDocument session = process.getSessionId() == null
                ? null
                : sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null && session.getUserId() != null
                && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && session.getProjectId() != null
                && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }
}
