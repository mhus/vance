package de.mhus.vance.brain.skill;

import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What an engine needs from the skill subsystem at turn time, in one
 * bean: resolve the process's active skills through the cascade, compose
 * their system-prompt section (with each skill's invocation arguments),
 * and collect the tools they contribute.
 *
 * <p>Exists because every engine that supports skills needs the same
 * three steps, and each copy is a chance to drift — Ford and Frankie had
 * a private {@code resolveActiveSkills} each, while Arthur and Eddie ran
 * the trigger matcher but never composed anything, so an active skill's
 * body silently never reached the model in the default chat engine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillTurnSupport {

    private final SkillResolver skillResolver;
    private final SkillPromptComposer composer;
    private final SessionService sessionService;

    /**
     * Resolves the process's persisted {@link ActiveSkillRefEmbedded}s
     * into ready-to-use {@link ResolvedSkill}s through the
     * user/project/tenant/bundled cascade. Skills that no longer resolve
     * (e.g. the user deleted their private skill mid-session) are
     * skipped with a warning rather than failing the turn.
     */
    public List<ResolvedSkill> resolveActive(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.getName() == null || ref.getName().isBlank()) continue;
            try {
                skillResolver.resolve(scope, ref.getName())
                        .ifPresentOrElse(out::add, () -> log.warn(
                                "id='{}' active skill '{}' no longer resolves — skipping",
                                process.getId(), ref.getName()));
            } catch (UnknownSkillException e) {
                log.warn("id='{}' active skill '{}' unknown — skipping",
                        process.getId(), ref.getName());
            }
        }
        return out;
    }

    /**
     * Composes the {@code ## Active Skills} system-prompt section for
     * {@code skills}, rendering each body against {@code pebbleContext}
     * plus the arguments that skill was activated with. {@code null} when
     * nothing is active.
     */
    public @Nullable String composeSection(
            ThinkProcessDocument process,
            List<ResolvedSkill> skills,
            Map<String, Object> pebbleContext) {
        return composer.compose(skills, pebbleContext, rawArgsByName(process));
    }

    /** Tool names contributed by the active skills (add-only). */
    public Set<String> mergedTools(List<ResolvedSkill> skills) {
        return composer.mergedTools(skills);
    }

    /**
     * Maps skill name → the raw trailing text it was activated with.
     * Skills activated without arguments are absent from the map.
     */
    public static Map<String, String> rawArgsByName(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.getName() == null || ref.getArgs() == null) continue;
            out.put(ref.getName(), ref.getArgs());
        }
        return out;
    }

    /**
     * Builds the cascade lookup scope from the process's session —
     * tenant plus the session's user and project, so a user's private
     * skill overrides the project's and the project's the tenant's.
     */
    public SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElse(null);
        String userId = session != null && session.getUserId() != null
                && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && session.getProjectId() != null
                && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }
}
