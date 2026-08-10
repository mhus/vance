package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.ScriptTarget;
import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.brain.command.EngineCommand;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one skill — what callers (Ford,
 * SkillPromptComposer, Arthur's auto-trigger detector) consume. Same
 * shape as {@link BundledSkill} plus the source attribution so the UI
 * can mark "from project / from tenant / from bundled" etc.
 */
public record ResolvedSkill(
        String name,
        String title,
        String description,
        String version,
        List<Trigger> triggers,
        @Nullable String promptExtension,
        List<String> tools,
        List<String> manualPaths,
        List<ReferenceDoc> referenceDocs,
        List<Script> scripts,
        List<String> tags,
        boolean enabled,
        SkillScope source,
        /** Engine commands fired on activation — see planning/engine-commands.md §4. */
        List<EngineCommand> activate,
        /** Engine commands fired on clear (cleanup); never fired for {@link SkillLifecycle#SHOT}. */
        List<EngineCommand> deactivate,
        SkillLifecycle lifecycle,
        /**
         * Whether this skill consumes the invocation's trailing text
         * ({@code /skill <name> <rest…>}) as arguments. Set by an
         * {@code arguments:} frontmatter entry — either {@code true}
         * (raw consume, {@code args.text} / {@code args.words} only) or
         * a list of {@link Argument} declarations. When {@code false}
         * the trailing text is <b>not</b> bound into the template; the
         * activation path injects it as a plain user message instead
         * (see {@code SkillSteerProcessor}). Exactly one side consumes
         * it — never both.
         */
        boolean consumesArgs,
        /**
         * Declared arguments, positionally bound against the trailing
         * text. Empty for {@code arguments: true} (raw consume) and for
         * skills that declare nothing. See {@link SkillArgumentBinder}.
         */
        List<Argument> arguments,
        /**
         * Optional initial prompt fired <b>once</b> on a fresh activation:
         * unlike {@code activate:} (control-plane commands, no model), this
         * triggers a real LLM turn so the skill can kick off work by itself.
         * Runs <b>after</b> the {@code activate:} sequence so the turn sees the
         * new state. Injected into the process's pending queue plus a scheduled
         * lane turn (never inline) to avoid lane re-entrancy — the same path the
         * completion guard uses. See {@code planning/engine-commands.md} §4 and
         * {@code specification/public/skills.md} §2a.
         */
        @Nullable String action,
        /**
         * Where the activation takes effect — inline in the calling
         * process (default) or in a freshly spawned worker. See
         * {@link SkillRun}.
         */
        SkillRun run) {

    /**
     * Backward-compatible constructor for call sites that predate the
     * {@code run:} block — everything acts inline.
     */
    public ResolvedSkill(
            String name,
            String title,
            String description,
            String version,
            List<Trigger> triggers,
            @Nullable String promptExtension,
            List<String> tools,
            List<String> manualPaths,
            List<ReferenceDoc> referenceDocs,
            List<Script> scripts,
            List<String> tags,
            boolean enabled,
            SkillScope source,
            List<EngineCommand> activate,
            List<EngineCommand> deactivate,
            SkillLifecycle lifecycle,
            boolean consumesArgs,
            List<Argument> arguments,
            @Nullable String action) {
        this(name, title, description, version, triggers, promptExtension,
                tools, manualPaths, referenceDocs, scripts, tags, enabled, source,
                activate, deactivate, lifecycle, consumesArgs, arguments, action,
                SkillRun.INLINE);
    }

    /**
     * Backward-compatible constructor for call sites that predate
     * engine-command skills — no {@code activate}/{@code deactivate}
     * sequences, {@link SkillLifecycle#STICKY} lifecycle, no {@code action:}.
     */
    public ResolvedSkill(
            String name,
            String title,
            String description,
            String version,
            List<Trigger> triggers,
            @Nullable String promptExtension,
            List<String> tools,
            List<String> manualPaths,
            List<ReferenceDoc> referenceDocs,
            List<Script> scripts,
            List<String> tags,
            boolean enabled,
            SkillScope source) {
        this(name, title, description, version, triggers, promptExtension,
                tools, manualPaths, referenceDocs, scripts, tags, enabled, source,
                List.of(), List.of(), SkillLifecycle.STICKY,
                /*consumesArgs*/ false, List.of(), null);
    }

    /**
     * One declared invocation argument of a skill. Bound positionally
     * from the trailing text of {@code /skill <name> <rest…>} — the
     * last declared {@code string} argument binds the remainder greedily
     * (shell convention). Same field shape as
     * {@link Script.ScriptParam} so authors only learn one schema.
     */
    public record Argument(
            String name,
            String type,
            @Nullable String description,
            boolean required) {
    }

    public record Trigger(
            SkillTriggerType type,
            @Nullable String pattern,
            List<String> keywords) {
    }

    public record ReferenceDoc(
            String title,
            String content,
            SkillReferenceDocLoadMode loadMode,
            @Nullable String summary) {
    }

    /**
     * A skill-bound script — declared in the SKILL.md frontmatter,
     * with its body loaded from a sibling file on the same cascade
     * tier as the SKILL.md itself (no cross-tier reads).
     *
     * <p>Per {@code specification/skills.md} §13, scripts get mounted
     * as virtual tools named {@code skill_<skillname>__<name>} in the
     * active turn's tool-loop when the skill is active.
     */
    public record Script(
            String name,
            ScriptTarget target,
            @Nullable String description,
            List<ScriptParam> params,
            String body) {

        /**
         * One declared input parameter of a {@link Script}. Rendered
         * into the virtual tool's JSON-Schema by {@code SkillScriptTool}
         * so the LLM gets an explicit, typed parameter contract instead
         * of relying on prose in the skill body. An empty {@code params}
         * list keeps the free-form v1 behaviour.
         */
        public record ScriptParam(
                String name,
                String type,
                @Nullable String description,
                boolean required) {
        }
    }
}
