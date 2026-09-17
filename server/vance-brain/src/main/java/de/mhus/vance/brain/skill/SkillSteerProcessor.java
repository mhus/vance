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
 * Out-of-band skill control for think-processes — activate, fire, clear,
 * clear-all, list. Activation calls mutate the persisted {@code activeSkills}
 * on the process document; the next chat-turn the user (or Arthur)
 * initiates picks the new skill set up automatically because Ford reads
 * {@code activeSkills} fresh on every turn.
 *
 * <p><b>Who is activating matters — {@link ActivationRoute}.</b> Whether a
 * fresh activation fires the skill's {@code action:} turn, and whether it
 * may start a worker for a {@code run.target: spawn} skill, is a property
 * of the caller, not of the skill: the user surface activates <em>and</em>
 * kicks off; the agent tool only switches instructions on and fires via
 * {@link #fire} when it wants the kick-off — a scheduled turn mid-flight
 * would only duplicate the work the agent is already doing; the implicit
 * routes (auto-trigger, guard scripts) neither fire nor spawn. Re-activating
 * an already-active skill does not re-fire.
 *
 * <p><b>The turn-prompt.</b> What a fire produces, in this precedence:
 * <ol>
 *   <li>{@code action:} — the explicit initial prompt.</li>
 *   <li>For {@link SkillLifecycle#SHOT} only: the skill <b>body</b>. A shot
 *       skill never registers in {@code activeSkills}, so it can never
 *       contribute to a system prompt — its body <em>is</em> the turn. That
 *       makes a shot skill with a body a prompt macro; a shot skill with an
 *       empty body and {@code activate:} commands stays the pure
 *       configuration macro it always was.</li>
 * </ol>
 * The prompt is appended to the process's pending queue plus a scheduled
 * lane turn (never inline — {@link #fireAction}), so no lane re-entrancy.
 *
 * <p><b>Invocation arguments.</b> {@code /skill <name> <rest…>} carries
 * the trailing text into {@link #activate}. Exactly one side consumes it:
 * a skill that declares {@code arguments:} gets it bound into its
 * template as {@code args} (see {@link SkillArgumentBinder}); any other
 * skill gets it injected as a plain user message, which is what the
 * clients used to do themselves. Sticky skills keep the raw text on their
 * {@link ActiveSkillRefEmbedded} so later turns re-bind it.
 *
 * <p><b>Exception — {@code run.target: spawn}.</b> A skill can declare
 * that its work belongs in a fresh worker rather than here (review-style
 * tasks, where inheriting the chat history biases the verdict). Such an
 * activation registers nothing on the calling process: {@link SkillSpawnRunner}
 * creates the child and the very same {@code activate} runs again on the
 * child's lane, with route {@link ActivationRoute#WORKER}, where the skill
 * is an ordinary sticky one. See
 * {@code specification/public/skills.md} §2c.
 *
 * <p>Recipe-bound skills (those activated by the spawning recipe with
 * {@code fromRecipe=true}) cannot be cleared by the user when the
 * recipe is locked — see {@code specification/public/skills.md} §7a.
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
    private final SkillSpawnRunner skillSpawnRunner;

    /**
     * Explicit activation, user route ({@code /skill <name>} /
     * {@code process-skill} ACTIVATE): a fresh activation fires the skill's
     * {@code action:} turn — there is no in-flight turn to cover the work,
     * so the skill kicks it off itself.
     */
    public ActivationResult activate(ThinkProcessDocument process, String skillName, boolean oneShot) {
        return activate(process, skillName, oneShot, ActivationRoute.USER, null, null);
    }

    /**
     * Explicit activation with the invocation's trailing text, user route.
     * See {@link #activate(ThinkProcessDocument, String, boolean,
     * ActivationRoute, String, String)}.
     */
    public ActivationResult activate(
            ThinkProcessDocument process,
            String skillName,
            boolean oneShot,
            @Nullable String rawArgs,
            @Nullable String senderUserId) {
        return activate(process, skillName, oneShot, ActivationRoute.USER, rawArgs, senderUserId);
    }

    /**
     * The activation funnel — one shared gate sequence, one behavior matrix.
     * Every route resolves through the same cascade, enforces the recipe
     * {@code allowedSkills} lock, refuses disabled skills, validates declared
     * arguments up front and treats re-activation idempotently. What differs
     * is named by {@link ActivationRoute}: whether a fresh activation fires
     * the skill's {@code action:} turn, and whether a
     * {@code run.target: spawn} skill may start its worker from here.
     *
     * @param rawArgs the invocation's trailing text, unparsed. Bound into
     *   the skill's template when it declares {@code arguments:}; otherwise
     *   injected as a plain user message so it still reaches the model.
     *   {@code null} / blank when the invocation carried none.
     * @param senderUserId identity stamped on that fallback user message —
     *   the requesting user, so the turn reads as theirs. Falls back to
     *   {@link #ACTION_SENDER} when unknown (system-driven activation).
     */
    public ActivationResult activate(
            ThinkProcessDocument process,
            String skillName,
            boolean oneShot,
            ActivationRoute route,
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
        ResolvedSkill skill =
                skillResolver.resolve(scope, skillName).orElseThrow(() -> new UnknownSkillException(skillName));
        // Disabled is an activation gate, not a visibility one — the name
        // resolved, so refusing with the "what to do" answer beats an
        // "unknown skill" that would send the user hunting for a typo.
        // Pins the spec line "weder explizit noch implizit aktivierbar":
        // the implicit side is SkillTriggerMatcher's enabled check.
        if (!skill.enabled()) {
            throw new DisabledSkillException(skill.name());
        }

        String args = rawArgs == null || rawArgs.isBlank() ? null : rawArgs.strip();
        // Validate up front: a missing required argument must fail the
        // activation, not surface later as an empty placeholder in a
        // rendered prompt. Throws SkillArgumentException.
        SkillArgumentBinder.bind(skill, args);

        // isActive: not only the recursion guard. A process that already
        // carries the skill is the worker itself being re-invoked (the
        // public entry point allows spawning), and it has nothing to spawn
        // — pinned by SkillSteerProcessorSpawnTest.
        if (skill.run().spawns() && !isActive(process, skillName)) {
            if (route.allowSpawn()) {
                return spawnAndActivate(process, skill, oneShot, rawArgs, args, senderUserId);
            }
            if (!route.registerInlineWhenSpawnBlocked()) {
                log.debug(
                        "Skill activate id='{}' name='{}' run=spawn suppressed — route '{}' "
                                + "starts no worker and registers nothing here",
                        process.getId(),
                        skill.name(),
                        route);
                return new ActivationResult(skill, false, mutableActive(process));
            }
            // WORKER: fall through — this process *is* the worker, the spawn
            // branch must not re-enter (no grandchild); the skill registers
            // inline below, pinned by SkillSteerProcessorSpawnTest.
        }

        if (skill.lifecycle() == SkillLifecycle.SHOT) {
            // Macro: fire the activate sequence once, never persist, no
            // deactivate. With a body (or action:) it is a prompt macro —
            // fireAction below turns that into one scheduled turn; with an
            // empty body it stays a pure configuration macro. See
            // specification/public/skills.md §2a.
            skillCommandRunner.run(process, skill.activate(), "activate", skill.name());
            log.info(
                    "Skill activate id='{}' name='{}' lifecycle=shot " + "(fired {} command(s), not persisted)",
                    process.getId(),
                    skill.name(),
                    skill.activate().size());
            if (route.fireActionTurn()) {
                fireAction(process, skill, args);
                injectUnconsumedArgs(process, skill, args, senderUserId);
            }
            return new ActivationResult(skill, true, mutableActive(process));
        }

        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        Optional<ActiveSkillRefEmbedded> existing =
                active.stream().filter(a -> skillName.equals(a.getName())).findFirst();
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
            log.debug("Skill activate id='{}' name='{}' (already active)", process.getId(), skillName);
            if (route.fireActionTurn()) {
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
        log.info(
                "Skill activate id='{}' name='{}' source={} oneShot={}",
                process.getId(),
                skill.name(),
                skill.source(),
                oneShot);
        // Fire the activate sequence only on a fresh activation — an
        // already-active skill (handled above) must not re-fire.
        skillCommandRunner.run(process, skill.activate(), "activate", skill.name());
        if (route.fireActionTurn()) {
            fireAction(process, skill, args);
            injectUnconsumedArgs(process, skill, args, senderUserId);
        }
        return new ActivationResult(skill, true, active);
    }

    /**
     * {@code run.target: spawn} — the skill does its work in a fresh
     * worker instead of here. Nothing is registered on the calling
     * process: it keeps no active skill, gets no injected message, and
     * has nothing to clear afterwards. The worker reports back through
     * the regular parent-notification path when it terminates.
     *
     * <p>Suppressed on the implicit routes ({@link ActivationRoute#IMPLICIT}):
     * those activations happen inside an in-flight turn, and starting a
     * worker as a side effect of a keyword match or a guard script would
     * be both expensive and surprising — {@code SkillLoader} warns about
     * triggers on a spawn skill.
     *
     * @param oneShot the caller's {@code --once}, passed on: {@code run}
     *   decides the <em>place</em>, {@code --once} the <em>duration</em>,
     *   and the two are orthogonal ({@code SkillRun}). Dropping it here
     *   registered the skill sticky on the child even though the caller
     *   asked for a single use.
     * @param rawArgs the unstripped trailing text, passed on so the child
     *   activation applies the same consume rules (bound into the
     *   template, or injected as a user message on the <em>child</em>)
     * @param args the stripped form, for logging only
     */
    private ActivationResult spawnAndActivate(
            ThinkProcessDocument process,
            ResolvedSkill skill,
            boolean oneShot,
            @Nullable String rawArgs,
            @Nullable String args,
            @Nullable String senderUserId) {
        String childId = skillSpawnRunner.spawn(
                process,
                skill,
                child -> activate(child, skill.name(), oneShot, ActivationRoute.WORKER, rawArgs, senderUserId));
        log.info(
                "Skill activate id='{}' name='{}' run=spawn recipe='{}' → child id='{}'" + " (caller unchanged{})",
                process.getId(),
                skill.name(),
                skill.run().recipe(),
                childId,
                args == null ? "" : ", args passed to child");
        return new ActivationResult(skill, true, mutableActive(process));
    }

    private static boolean isActive(ThinkProcessDocument process, String skillName) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null) return false;
        return active.stream().anyMatch(ref -> skillName.equals(ref.getName()));
    }

    /**
     * If the skill carries a turn-prompt ({@code action:}, or the body
     * for a {@link SkillLifecycle#SHOT} skill), fire it as one LLM turn —
     * appended to the process's own pending queue plus a scheduled lane
     * turn, never run inline. This mirrors the completion guard's injection
     * path and sidesteps lane re-entrancy: skill activation and fire both
     * run on the process lane (see {@code ProcessSkillHandler},
     * {@code SkillTriggerMatcher} and {@code SkillFireTool}), so a
     * synchronous turn here would re-enter it. The injected message is
     * stamped with {@link #ACTION_SENDER} so it reads as system-injected in
     * history. In the activation funnel this fires <b>after</b> the
     * {@code activate:} sequence so the turn observes the freshly-set state.
     *
     * @return whether a prompt was actually scheduled — {@code false} when
     *   the skill carries no turn-prompt or the rendered prompt is blank
     */
    private boolean fireAction(ThinkProcessDocument process, ResolvedSkill skill, @Nullable String rawArgs) {
        String template = turnPromptTemplate(skill);
        if (template == null) {
            return false;
        }
        String prompt;
        try {
            prompt = templateRenderer.render(template, renderContext(process, skill, rawArgs));
        } catch (PromptTemplateException e) {
            log.warn(
                    "Skill turn-prompt id='{}' name='{}' has invalid Pebble — " + "firing unrendered: {}",
                    process.getId(),
                    skill.name(),
                    e.getMessage());
            prompt = template;
        }
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        SteerMessage.UserChatInput injected =
                new SteerMessage.UserChatInput(Instant.now(), null, ACTION_SENDER, prompt);
        thinkProcessService.appendPending(process.getId(), SteerMessageCodec.toDocument(injected));
        eventEmitter.scheduleTurn(process.getId());
        log.info(
                "Skill turn-prompt fired id='{}' name='{}' — scheduled turn ({} chars)",
                process.getId(),
                skill.name(),
                prompt.length());
        return true;
    }

    /**
     * Result of a {@link #fire} call — facts, not prose: the tool composes
     * the sentence the model reads. Never-active and spawn cases are
     * outcomes, not errors, mirroring {@code skill_clear}'s honesty rule.
     */
    public record FireResult(ResolvedSkill skill, Outcome outcome) {

        /** Why the fire did (or did not) schedule a turn. */
        public enum Outcome {
            /** The turn-prompt was scheduled; it runs after the current turn. */
            FIRED,
            /** The skill is not active in this process — activate it first. */
            NOT_ACTIVE,
            /** Neither an {@code action:} nor a shot body — nothing to fire. */
            NO_TURN_PROMPT,
            /** {@code run.target: spawn} skill — its work happens in a fresh worker. */
            SPAWN_SKILL
        }
    }

    /**
     * Fires a skill's turn-prompt without activating anything — the agent
     * twin of the user surface's auto-fire on fresh activation
     * ({@link ActivationRoute#USER} vs {@link ActivationRoute#AGENT}). For
     * the agent, {@code skill_activate} only switches instructions on; this
     * is the explicit kick-off. Same gates as activation: recipe
     * {@code allowedSkills} lock, disabled refusal, argument validation.
     * Never runs {@code activate:} — command sequences belong to
     * activation, and re-running idempotent setters on a fire would blur
     * the two planes.
     *
     * <p>Sticky skills must already be active (the fire renders with the
     * invocation's args, falling back to the args the skill was activated
     * with); a {@link SkillLifecycle#SHOT} skill fires directly — that is
     * the prompt-macro execution path. Nothing is registered, so there is
     * nothing to clear afterwards.
     *
     * @param rawArgs optional trailing text for this fire — bound into the
     *   template when the skill declares {@code arguments:}; otherwise
     *   injected as a user message alongside the turn, the same rule the
     *   activation path applies
     * @param senderUserId identity stamped on that fallback user message
     */
    public FireResult fire(
            ThinkProcessDocument process, String skillName, @Nullable String rawArgs, @Nullable String senderUserId) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required for fire");
        }
        java.util.Set<String> whitelist = process.getAllowedSkillsOverride();
        if (whitelist != null && !whitelist.contains(skillName)) {
            throw new SkillNotAllowedByRecipeException(skillName, process.getRecipeName());
        }
        ResolvedSkill skill = skillResolver
                .resolve(scopeFor(process), skillName)
                .orElseThrow(() -> new UnknownSkillException(skillName));
        if (!skill.enabled()) {
            throw new DisabledSkillException(skill.name());
        }
        String args = rawArgs == null || rawArgs.isBlank() ? null : rawArgs.strip();
        if (skill.run().spawns()) {
            // Its action: belongs to the worker (mandatory there, §2c); the
            // caller has nothing to fire and nothing registered.
            return new FireResult(skill, FireResult.Outcome.SPAWN_SKILL);
        }
        if (skill.lifecycle() == SkillLifecycle.SHOT) {
            // Same up-front validation as activation: a missing required
            // argument must fail the fire, not surface as an empty
            // placeholder in the rendered turn-prompt.
            SkillArgumentBinder.bind(skill, args);
            boolean fired = fireAction(process, skill, args);
            if (fired) {
                injectUnconsumedArgs(process, skill, args, senderUserId);
            }
            return new FireResult(skill, fired ? FireResult.Outcome.FIRED : FireResult.Outcome.NO_TURN_PROMPT);
        }

        ActiveSkillRefEmbedded ref = process.getActiveSkills() == null
                ? null
                : process.getActiveSkills().stream()
                        .filter(a -> skillName.equals(a.getName()))
                        .findFirst()
                        .orElse(null);
        if (ref == null) {
            return new FireResult(skill, FireResult.Outcome.NOT_ACTIVE);
        }
        // A fire without its own args re-renders with the activation's —
        // the stored args are part of the skill's configuration.
        String effectiveArgs = args != null ? args : ref.getArgs();
        // Same up-front validation as activation — here against the args
        // that will actually render (the stored ones when the fire carries
        // none), so a sticky fire without arguments does not trip over
        // required declarations the activation already satisfied.
        SkillArgumentBinder.bind(skill, effectiveArgs);
        boolean fired = fireAction(process, skill, effectiveArgs);
        if (fired) {
            injectUnconsumedArgs(process, skill, args, senderUserId);
        }
        return new FireResult(skill, fired ? FireResult.Outcome.FIRED : FireResult.Outcome.NO_TURN_PROMPT);
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
        String sender = senderUserId == null || senderUserId.isBlank() ? ACTION_SENDER : senderUserId;
        SteerMessage.UserChatInput injected = new SteerMessage.UserChatInput(Instant.now(), null, sender, rawArgs);
        thinkProcessService.appendPending(process.getId(), SteerMessageCodec.toDocument(injected));
        eventEmitter.scheduleTurn(process.getId());
        log.info(
                "Skill args passed through as user message id='{}' name='{}' ({} chars) — "
                        + "skill declares no arguments:",
                process.getId(),
                skill.name(),
                rawArgs.length());
    }

    public List<ActiveSkillRefEmbedded> clear(ThinkProcessDocument process, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required for clear");
        }
        List<ActiveSkillRefEmbedded> active = mutableActive(process);
        boolean removed = active.removeIf(ref -> {
            if (!skillName.equals(ref.getName())) return false;
            if (ref.isFromRecipe()) {
                log.warn("Skill clear id='{}' name='{}' rejected — recipe-bound", process.getId(), skillName);
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
            log.info("Skill clearAll id='{}' kept={} (recipe-bound)", process.getId(), kept.size());
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
            skillResolver
                    .resolve(scopeFor(process), skillName)
                    .ifPresent(
                            skill -> skillCommandRunner.run(process, skill.deactivate(), "deactivate", skill.name()));
        } catch (RuntimeException e) {
            log.warn("Skill deactivate id='{}' name='{}' resolve failed: {}", process.getId(), skillName, e.toString());
        }
    }

    public List<ResolvedSkill> listAvailable(ThinkProcessDocument process) {
        return skillResolver.listAvailable(scopeFor(process));
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session =
                sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && !session.getProjectId().isBlank() ? session.getProjectId() : null;
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
            ResolvedSkill skill, boolean newlyActivated, List<ActiveSkillRefEmbedded> activeAfter) {}

    /** Convenience dispatcher used by the WebSocket handler. */
    public List<ActiveSkillRefEmbedded> apply(
            ThinkProcessDocument process, ProcessSkillCommand command, String skillName, boolean oneShot) {
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
