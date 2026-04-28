package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Out-of-band skill control for think-processes — activate, clear,
 * clear-all, list. Unlike {@code process-steer}, these calls do not
 * trigger an LLM turn; they only mutate the persisted
 * {@code activeSkills} on the process document. The next chat-turn
 * the user (or Arthur) initiates will pick the new skill set up
 * automatically because Ford reads {@code activeSkills} fresh on every
 * turn.
 *
 * <p>Recipe-bound skills (those activated by the spawning recipe with
 * {@code fromRecipe=true}) cannot be cleared by the user when the
 * recipe is locked — see {@code specification/skills.md} §7a.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillSteerProcessor {

    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;
    private final SkillResolver skillResolver;

    public ActivationResult activate(
            ThinkProcessDocument process, String skillName, boolean oneShot) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required for activate");
        }
        SkillScopeContext scope = scopeFor(process);
        ResolvedSkill skill = skillResolver.resolve(scope, skillName)
                .orElseThrow(() -> new UnknownSkillException(skillName));

        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        Optional<ActiveSkillRefEmbedded> existing = active.stream()
                .filter(a -> skillName.equals(a.getName()))
                .findFirst();
        if (existing.isPresent()) {
            // Idempotent: keep the existing entry but flip oneShot if
            // the user just asked for sticky.
            ActiveSkillRefEmbedded ref = existing.get();
            if (ref.isOneShot() && !oneShot) {
                ref.setOneShot(false);
                persist(process, active);
            }
            log.debug("Skill activate id='{}' name='{}' (already active)",
                    process.getId(), skillName);
            return new ActivationResult(skill, false, active);
        }
        ActiveSkillRefEmbedded ref = ActiveSkillRefEmbedded.builder()
                .name(skill.name())
                .resolvedFromScope(skill.source())
                .oneShot(oneShot)
                .fromRecipe(false)
                .activatedAt(Instant.now())
                .build();
        active.add(ref);
        persist(process, active);
        log.info("Skill activate id='{}' name='{}' source={} oneShot={}",
                process.getId(), skill.name(), skill.source(), oneShot);
        return new ActivationResult(skill, true, active);
    }

    public List<ActiveSkillRefEmbedded> clear(ThinkProcessDocument process, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required for clear");
        }
        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        boolean removed = active.removeIf(ref -> {
            if (!skillName.equals(ref.getName())) return false;
            if (ref.isFromRecipe()) {
                log.warn("Skill clear id='{}' name='{}' rejected — recipe-bound",
                        process.getId(), skillName);
                return false;
            }
            return true;
        });
        if (removed) {
            persist(process, active);
            log.info("Skill clear id='{}' name='{}'", process.getId(), skillName);
        }
        return active;
    }

    public List<ActiveSkillRefEmbedded> clearAll(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        List<ActiveSkillRefEmbedded> kept = new ArrayList<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.isFromRecipe()) {
                kept.add(ref);
            }
        }
        if (kept.size() != active.size()) {
            persist(process, kept);
            log.info("Skill clearAll id='{}' kept={} (recipe-bound)",
                    process.getId(), kept.size());
        }
        return kept;
    }

    public List<ResolvedSkill> listAvailable(ThinkProcessDocument process) {
        return skillResolver.listAvailable(scopeFor(process));
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElse(null);
        String userId = session != null && !session.getUserId().isBlank()
                ? session.getUserId() : null;
        String projectId = session != null && !session.getProjectId().isBlank()
                ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    private static List<ActiveSkillRefEmbedded> mutableActive(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        return active == null ? new ArrayList<>() : new ArrayList<>(active);
    }

    private void persist(ThinkProcessDocument process, List<ActiveSkillRefEmbedded> active) {
        process.setActiveSkills(active);
        thinkProcessService.replaceActiveSkills(process.getId(), active);
    }

    /** Result of an {@code ACTIVATE} call. */
    public record ActivationResult(
            ResolvedSkill skill,
            boolean newlyActivated,
            List<ActiveSkillRefEmbedded> activeAfter) {
    }

    /** Convenience dispatcher used by the WebSocket handler. */
    public List<ActiveSkillRefEmbedded> apply(
            ThinkProcessDocument process,
            ProcessSkillCommand command,
            String skillName,
            boolean oneShot) {
        return switch (command) {
            case ACTIVATE -> activate(process, skillName, oneShot).activeAfter();
            case CLEAR -> clear(process, skillName);
            case CLEAR_ALL -> clearAll(process);
            case LIST -> mutableActive(process);
        };
    }
}
