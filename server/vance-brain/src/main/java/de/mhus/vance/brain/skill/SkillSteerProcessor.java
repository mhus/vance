package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Out-of-band skill control for think-processes — activate, clear,
 * clear-all, list. These calls mutate the persisted {@code activeSkills}
 * on the process document; the next chat-turn the user (or Arthur)
 * initiates picks the new skill set up automatically because Ford reads
 * {@code activeSkills} fresh on every turn.
 *
 * <p><b>Exception — the turn-prompt.</b> A skill can fire one LLM turn
 * on a <em>fresh explicit</em> activation: the prompt is appended to the
 * process's pending queue plus a scheduled lane turn (never inline —
 * {@link #fireAction}). Two sources, in this precedence:
 * <ol>
 *   <li>{@code action:} — the explicit initial prompt.</li>
 *   <li>For {@link SkillLifecycle#SHOT} only: the skill <b>body</b>.
 *       A shot skill never registers in {@code activeSkills}, so it can
 *       never contribute to a system prompt — its body <em>is</em> the
 *       turn. That makes a shot skill with a body a prompt macro; a shot
 *       skill with an empty body and {@code activate:} commands stays
 *       the pure configuration macro it always was.</li>
 * </ol>
 * Re-activating an already-active skill does not re-fire, and the
 * auto-trigger path suppresses it (its in-flight turn already covers the
 * work).
 *
 * <p><b>Invocation arguments.</b> {@code /skill <name> <rest…>} carries
 * the trailing text into {@link #activate}. Exactly one side consumes it:
 * a skill that declares {@code arguments:} gets it bound into its
 * template as {@code args} (see {@link SkillArgumentBinder}); any other
 * skill gets it injected as a plain user message, which is what the
 * clients used to do themselves. Sticky skills keep the raw text on their
 * {@link ActiveSkillRefEmbedded} so later turns re-bind it.
 *
 * <p>Recipe-bound skills (those activated by the spawning recipe with
 * {@code fromRecipe=true}) cannot be cleared by the user when the
 * recipe is locked — see {@code specification/skills.md} §7a.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillSteerProcessor {

    /** Sender id stamped on a turn-prompt-injected pending message. */
    static final String ACTION_SENDER = "_skill";

    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;
    private final SkillResolver skillResolver;
    private final SkillCommandRunner skillCommandRunner;
    private final ProcessEventEmitter eventEmitter;
    private final PromptTemplateRenderer templateRenderer;

    /**
     * Explicit activation ({@code /skill <name>} / {@code process-skill}
     * ACTIVATE). Fires the skill's {@code action:} turn on a fresh
     * activation — there is no in-flight turn to cover the work, so the
     * skill kicks it off itself.
     */
    public ActivationResult activate(
            ThinkProcessDocument process, String skillName, boolean oneShot) {
        return activate(process, skillName, oneShot, /*runAction*/ true, null, null);
    }

    /**
     * Explicit activation with the invocation's trailing text. See
     * {@link #activate(ThinkProcessDocument, String, boolean, boolean,
     * String, String)}.
     */
    public ActivationResult activate(
            ThinkProcessDocument process,
            String skillName,
            boolean oneShot,
            @Nullable String rawArgs,
            @Nullable String senderUserId) {
        return activate(process, skillName, oneShot, /*runAction*/ true, rawArgs, senderUserId);
    }

    /**
     * @param runAction whether a fresh activation fires the skill's
     *   {@code action:} turn. The auto-trigger path
     *   ({@link SkillTriggerMatcher}) passes {@code false}: it activates
     *   the skill <em>during</em> an already-running turn, which injects
     *   the body and covers the work this turn — a scheduled action turn
     *   would only duplicate it.
     */
    public ActivationResult activate(
            ThinkProcessDocument process, String skillName, boolean oneShot, boolean runAction) {
        return activate(process, skillName, oneShot, runAction, null, null);
    }

    /**
     * @param rawArgs the invocation's trailing text, unparsed. Bound into
     *   the skill's template when it declares {@code arguments:};
     *   otherwise injected as a plain user message so it still reaches the
     *   model. {@code null} / blank when the invocation carried none.
     * @param senderUserId identity stamped on that fallback user message —
     *   the requesting user, so the turn reads as theirs. Falls back to
     *   {@link #ACTION_SENDER} when unknown (system-driven activation).
     */
    public ActivationResult activate(
            ThinkProcessDocument process,
            String skillName,
            boolean oneShot,
            boolean runAction,
            @Nullable String rawArgs,
            @Nullable String senderUserId) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required for activate");
        }
        java.util.Set<String> whitelist = process.getAllowedSkillsOverride();
        if (whitelist != null && !whitelist.contains(skillName)) {
            throw new SkillNotAllowedByRecipeException(skillName, process.getRecipeName());
        }
        SkillScopeContext scope = scopeFor(process);
        ResolvedSkill skill = skillResolver.resolve(scope, skillName)
                .orElseThrow(() -> new UnknownSkillException(skillName));

        String args = rawArgs == null || rawArgs.isBlank() ? null : rawArgs.strip();
        // Validate up front: a missing required argument must fail the
        // activation, not surface later as an empty placeholder in a
        // rendered prompt. Throws SkillArgumentException.
        SkillArgumentBinder.bind(skill, args);

        if (skill.lifecycle() == SkillLifecycle.SHOT) {
            // Macro: fire the activate sequence once, never persist, no
            // deactivate. With a body (or action:) it is a prompt macro —
            // fireAction below turns that into one scheduled turn; with an
            // empty body it stays a pure configuration macro. See
            // specification/public/skills.md §2a.
            skillCommandRunner.run(process, skill.activate(), "activate", skill.name());
            log.info("Skill activate id='{}' name='{}' lifecycle=shot "
                            + "(fired {} command(s), not persisted)",
                    process.getId(), skill.name(), skill.activate().size());
            if (runAction) {
                fireAction(process, skill, args);
                injectUnconsumedArgs(process, skill, args, senderUserId);
            }
            return new ActivationResult(skill, true, mutableActive(process));
        }

        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        Optional<ActiveSkillRefEmbedded> existing = active.stream()
                .filter(a -> skillName.equals(a.getName()))
                .findFirst();
        if (existing.isPresent()) {
            // Idempotent: keep the existing entry but flip oneShot if
            // the user just asked for sticky.
            ActiveSkillRefEmbedded ref = existing.get();
            boolean dirty = false;
            if (ref.isOneShot() && !oneShot) {
                ref.setOneShot(false);
                dirty = true;
            }
            // Re-invoking with arguments is a parameter update — the
            // natural reading of `/skill x <new args>` on an active skill.
            // The turn-prompt still does not re-fire (fresh-activation
            // only), so this stays a quiet reconfiguration.
            if (skill.consumesArgs() && args != null && !args.equals(ref.getArgs())) {
                ref.setArgs(args);
                dirty = true;
            }
            if (dirty) {
                persist(process, active);
            }
            log.debug("Skill activate id='{}' name='{}' (already active)",
                    process.getId(), skillName);
            if (runAction) {
                injectUnconsumedArgs(process, skill, args, senderUserId);
            }
            return new ActivationResult(skill, false, active);
        }
        ActiveSkillRefEmbedded ref = ActiveSkillRefEmbedded.builder()
                .name(skill.name())
                .resolvedFromScope(skill.source())
                .oneShot(oneShot)
                .fromRecipe(false)
                .activatedAt(Instant.now())
                .args(skill.consumesArgs() ? args : null)
                .build();
        active.add(ref);
        persist(process, active);
        log.info("Skill activate id='{}' name='{}' source={} oneShot={}",
                process.getId(), skill.name(), skill.source(), oneShot);
        // Fire the activate sequence only on a fresh activation — an
        // already-active skill (handled above) must not re-fire.
        skillCommandRunner.run(process, skill.activate(), "activate", skill.name());
        if (runAction) {
            fireAction(process, skill, args);
            injectUnconsumedArgs(process, skill, args, senderUserId);
        }
        return new ActivationResult(skill, true, active);
    }

    /**
     * If the skill carries an {@code action:} prompt, fire it as one LLM
     * turn — appended to the process's own pending queue plus a scheduled
     * lane turn, never run inline. This mirrors the completion guard's
     * injection path and sidesteps lane re-entrancy: skill activation
     * already runs on the process lane (see {@code ProcessSkillHandler} and
     * {@code SkillTriggerMatcher}), so a synchronous turn here would
     * re-enter it. The injected message is stamped with {@link #ACTION_SENDER}
     * so it reads as system-injected in history. Fires <b>after</b> the
     * {@code activate:} sequence so the turn observes the freshly-set state.
     * No-op when {@code action:} is absent/blank.
     */
    private void fireAction(
            ThinkProcessDocument process, ResolvedSkill skill, @Nullable String rawArgs) {
        String template = turnPromptTemplate(skill);
        if (template == null) {
            return;
        }
        String prompt;
        try {
            prompt = templateRenderer.render(template, renderContext(process, skill, rawArgs));
        } catch (PromptTemplateException e) {
            log.warn("Skill turn-prompt id='{}' name='{}' has invalid Pebble — "
                            + "firing unrendered: {}",
                    process.getId(), skill.name(), e.getMessage());
            prompt = template;
        }
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        SteerMessage.UserChatInput injected = new SteerMessage.UserChatInput(
                Instant.now(), null, ACTION_SENDER, prompt);
        thinkProcessService.appendPending(
                process.getId(), SteerMessageCodec.toDocument(injected));
        eventEmitter.scheduleTurn(process.getId());
        log.info("Skill turn-prompt fired id='{}' name='{}' — scheduled turn ({} chars)",
                process.getId(), skill.name(), prompt.length());
    }

    /**
     * The template fired as this skill's turn-prompt, or {@code null}
     * when the skill has none. {@code action:} wins; a
     * {@link SkillLifecycle#SHOT} skill falls back to its body, because a
     * shot skill never registers and so has no other way to say anything.
     * A sticky skill's body is <b>not</b> a turn-prompt — it goes into the
     * system prompt every turn instead.
     */
    private static @Nullable String turnPromptTemplate(ResolvedSkill skill) {
        String action = skill.action();
        if (action != null && !action.isBlank()) {
            return action;
        }
        if (skill.lifecycle() != SkillLifecycle.SHOT) {
            return null;
        }
        String body = skill.promptExtension();
        return body == null || body.isBlank() ? null : body;
    }

    /**
     * Pebble context for a turn-prompt render: the process-derived
     * variables plus this skill's {@code args}.
     *
     * <p>{@code tier} / {@code model} / {@code provider} stay unset — no
     * model is resolved at activation time. That is not a gap: a
     * turn-prompt is a user message, and tier branching belongs in a
     * sticky body, which the engine renders with a full context at turn
     * time.
     */
    private static Map<String, Object> renderContext(
            ThinkProcessDocument process, ResolvedSkill skill, @Nullable String rawArgs) {
        Map<String, Object> ctx = new LinkedHashMap<>(
                PromptContextBuilder.forProcess(process, null).build());
        Map<String, Object> args = SkillArgumentBinder.bind(skill, rawArgs);
        if (!args.isEmpty()) {
            ctx.put("args", args);
        }
        return ctx;
    }

    /**
     * Injects the invocation's trailing text as a plain user message when
     * the skill does <b>not</b> declare {@code arguments:} — the client
     * used to send this itself, which meant the web UI dropped it and only
     * foot sent it. Now the server decides, so exactly one side consumes
     * the text: a declaring skill has it in its template, everyone else
     * gets it as a message.
     *
     * <p>Stamped with the requesting user's id where known so the turn
     * reads as a genuine user turn (mention routing, sender display), and
     * scheduled rather than run inline — the activation already holds the
     * process lane.
     */
    private void injectUnconsumedArgs(
            ThinkProcessDocument process,
            ResolvedSkill skill,
            @Nullable String rawArgs,
            @Nullable String senderUserId) {
        if (rawArgs == null || rawArgs.isBlank() || skill.consumesArgs()) {
            return;
        }
        String sender = senderUserId == null || senderUserId.isBlank()
                ? ACTION_SENDER : senderUserId;
        SteerMessage.UserChatInput injected = new SteerMessage.UserChatInput(
                Instant.now(), null, sender, rawArgs);
        thinkProcessService.appendPending(
                process.getId(), SteerMessageCodec.toDocument(injected));
        eventEmitter.scheduleTurn(process.getId());
        log.info("Skill args passed through as user message id='{}' name='{}' ({} chars) — "
                        + "skill declares no arguments:",
                process.getId(), skill.name(), rawArgs.length());
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
            fireDeactivate(process, skillName);
        }
        return active;
    }

    public List<ActiveSkillRefEmbedded> clearAll(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        List<ActiveSkillRefEmbedded> kept = new ArrayList<>();
        List<String> removedNames = new ArrayList<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.isFromRecipe()) {
                kept.add(ref);
            } else if (ref.getName() != null) {
                removedNames.add(ref.getName());
            }
        }
        if (kept.size() != active.size()) {
            persist(process, kept);
            log.info("Skill clearAll id='{}' kept={} (recipe-bound)",
                    process.getId(), kept.size());
            for (String name : removedNames) {
                fireDeactivate(process, name);
            }
        }
        return kept;
    }

    /**
     * Fires a cleared skill's {@code deactivate:} sequence. Best-effort:
     * a skill that vanished from the cascade (deleted, moved out of
     * scope) just skips its cleanup — the removal from {@code activeSkills}
     * already happened.
     */
    private void fireDeactivate(ThinkProcessDocument process, String skillName) {
        try {
            skillResolver.resolve(scopeFor(process), skillName).ifPresent(skill ->
                    skillCommandRunner.run(
                            process, skill.deactivate(), "deactivate", skill.name()));
        } catch (RuntimeException e) {
            log.warn("Skill deactivate id='{}' name='{}' resolve failed: {}",
                    process.getId(), skillName, e.toString());
        }
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
        return apply(process, command, skillName, oneShot, null, null);
    }

    /** Dispatcher variant carrying the invocation's trailing text. */
    public List<ActiveSkillRefEmbedded> apply(
            ThinkProcessDocument process,
            ProcessSkillCommand command,
            String skillName,
            boolean oneShot,
            @Nullable String rawArgs,
            @Nullable String senderUserId) {
        return switch (command) {
            case ACTIVATE ->
                    activate(process, skillName, oneShot, rawArgs, senderUserId).activeAfter();
            case CLEAR -> clear(process, skillName);
            case CLEAR_ALL -> clearAll(process);
            case LIST -> mutableActive(process);
        };
    }
}
