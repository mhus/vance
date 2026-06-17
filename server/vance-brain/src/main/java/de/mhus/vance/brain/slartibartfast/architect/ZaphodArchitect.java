package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.zaphod.ZaphodHeadsParser;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Zaphod recipe architect. Produces recipes with
 * {@code engine: zaphod}, a non-empty {@code params.heads} list
 * (each with name + recipe + persona), and a
 * {@code params.synthesisPrompt} for the aggregator turn. Supports
 * both {@code pattern: COUNCIL} (single-shot multi-perspective) and
 * {@code pattern: DEBATE} (multi-round with consensus-stop).
 *
 * <p>Validation gate: the full recipe parses via
 * {@link ZaphodHeadsParser#parseRecipe} — mirrors the engine's
 * own start-up validation, so a recipe that passes here will
 * also pass {@code ZaphodEngine.buildInitialState}.
 *
 * <p>See {@code specification/zaphod-engine.md} for the engine
 * lifecycle and the council vs. debate distinction.
 */
@Component
@Slf4j
public class ZaphodArchitect implements SchemaArchitect {

    public static final String RULE_ZAPHOD_RECIPE_PARSES =
            "zaphod-recipe-heads-parse";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce an
            executable recipe for the Zaphod engine — a multi-head
            orchestrator that drives N heads (each a sub-process)
            sequentially against the same goal, then synthesises
            their outputs.

            Zaphod has TWO patterns; you MUST pick one before
            writing the YAML:

            ── PATTERN CHOICE: COUNCIL vs DEBATE ──

            `pattern: COUNCIL` — single-shot multi-perspective.
              Each head answers ONCE; the synthesizer combines
              the answers. The heads do NOT see each other.
              Pick this when:
                * The question wants several perspectives
                  TALLIED, not RESOLVED.
                * The heads are expert lenses (security, perf,
                  cost, UX) reporting from their own lane.
                * The heads have nothing to argue about — they
                  cover orthogonal axes.

            `pattern: DEBATE` — multi-round with consensus-stop.
              Each head sees the OTHER heads' last-round replies
              and reacts. Between rounds a small consensus-check
              runs; the loop ends on consensus OR `maxRounds`.
              The synthesizer sees only the FINAL round plus a
              `consensusReached` flag.
              Pick this when:
                * The heads have OPPOSING positions that could
                  realistically shift under pressure (pro/contra,
                  attacker/defender, optimist-vs-skeptic on a
                  yes/no decision).
                * The user wants to SEE the disagreement resolve
                  (or made explicit), not just collected.
                * The decision benefits from heads pushing back
                  on each other.

            If unsure: prefer COUNCIL. Debate is more expensive
            (N × rounds + consensus checks) and only pays off when
            positions can actually move.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML, see structure below>",
                  "justifications": {
                    "<constraint-key>": "<sg-id>",
                    "params.pattern": "<sg-id>",
                    "params.heads.0.persona": "<sg-id>",
                    "params.synthesisPrompt": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this pattern AND head shape — 2-3 sentences>"
                }

            ── YAML structure (mandatory) ──

                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: zaphod
                params:
                  pattern: COUNCIL              # OR DEBATE
                  maxRounds: 3                   # DEBATE ONLY — see rules below
                  heads:
                    - name: <unique kebab-case head-name>
                      recipe: <recipe-name from the available list>
                      persona: |
                        <1-3 sentences — see persona rules below>
                    - name: ...
                      recipe: ...
                      persona: |
                        ...
                  synthesisPrompt: |
                    <instruction for the synthesizer turn — describes
                    the content/shape of the consolidated synthesis,
                    NOT how to persist it (that is engine business)>

            For COUNCIL: OMIT the `maxRounds` line entirely. The
            engine forces 1.

            ── HEADS-SHAPE rules ──

            - COUNCIL: 2-5 heads is the sweet spot.
            - DEBATE: 2-3 heads is the sweet spot — 2 is the
              hard MINIMUM. More than 3 makes the consensus check
              noisy (each new head adds an axis along which the
              "did they all agree" decision can split).
            - Hard cap at 7 across both patterns; exceeding fails
              validation.
            - Head names MUST be unique. Convention: kebab-case
              role-words (`security-reviewer`, `cost-optimist`,
              `pro`, `contra`).
            - `recipe` references an existing recipe in the
              project (typically `ford` or a ford-style variant
              listed in the "Available head recipes" block).
              Inventing names ("council-head", "perspective-x")
              fails at execution time.

            ── PERSONA SHAPE rules (pattern-dependent!) ──

            COUNCIL personae are DESCRIPTIVE — they name a lens
            or expert role:
                "You evaluate refactors purely for security
                 impact — attack surface, secrets, auth paths.
                 Ignore performance and cost."

            DEBATE personae are POSITIONAL — they take a stance
            the head can revise under pressure. A DEBATE persona
            MUST:
                * Name the position the head DEFENDS (e.g.
                  "you argue FOR …", "you argue AGAINST …").
                * Explicitly allow position changes WHEN the
                  counter-argument is objectively stronger.
                * Forbid stylistic concessions that don't
                  reflect a real update (no "yes, you're right"
                  without substance).

            A debate persona written as a descriptive lens ("you
            focus on risks") collapses the debate into a slow
            council — the head never pushes back across rounds.
            That is a validation-failing shape error even if the
            YAML parses.

            Personae like "a careful thinker" or "a general
            assistant" are useless in BOTH patterns — the heads
            collapse to the same answer.

            ── maxRounds rules (DEBATE ONLY) ──

            - Field is OMITTED for COUNCIL (engine ignores any
              value supplied; emit nothing to keep the YAML
              clean).
            - DEBATE default is 3. Pick 2 for cheap "first
              reaction" debates; 3 for the default; 4-5 for
              genuinely contested questions where positions take
              longer to settle. Above 5 is almost always wrong
              — the question is unfocused, not the budget.
            - Hard cap is 10; exceeding fails validation.

            ── SYNTHESIS PROMPT rules ──

            - Tell the synthesizer what to *do* with the heads'
              outputs: prioritise / contrast / consolidate /
              decide. "Synthesise the outputs" alone is too vague.
            - Reference the heads by their `name` so the
              synthesizer can ground each strand.
            - Name the deliverable shape (one paragraph, a
              bulleted decision matrix, a recommendation with
              caveats).

            For DEBATE synthesis prompts specifically:
            - The synthesizer sees ONLY the FINAL round of
              replies plus a `consensusReached` flag — NOT the
              full debate transcript. Don't ask it to
              "summarise the debate"; ask it to consolidate the
              FINAL positions.
            - If the deliverable should acknowledge unresolved
              dissent ("after 3 rounds, pro and contra still
              differ on X"), instruct the synthesizer to lean on
              the consensus flag — the engine wires this in
              automatically via Pebble vars.

            ── CRITICAL: synthesizer has NO TOOLS ──

            The synthesizer is a direct LLM call with structured
            JSON output — it does NOT have access to `doc_create`
            or any other file-writing tool. The Zaphod engine
            persists the synthesis document deterministically from
            the LLM's structured reply.

            DO NOT write a synthesisPrompt that asks the
            synthesizer to "create a document", "save the answer
            as a file", "write to <path>", or anything similar.
            Those instructions cause the LLM to hallucinate
            pseudo-tool-call text ("doc_create(path=…)") in its
            reply body, which the engine then has to recover from.

            DO write a synthesisPrompt that describes the CONTENT
            and SHAPE of the synthesis text itself. Persistence
            is an engine concern — head replies AND the final
            synthesis are written under
            `_zaphod-drafts/<processId>/` by the engine; you do
            NOT configure output paths.

            ── EXAMPLE A: COUNCIL ──

                name: refactor-impact-council
                description: |
                  Three perspectives on a refactor proposal, then a
                  consolidated recommendation.
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - name: security-reviewer
                      recipe: ford
                      persona: |
                        You evaluate refactors purely for security
                        impact: new attack surface, weakened
                        authentication paths, secret exposure.
                        Ignore performance and cost.
                    - name: performance-reviewer
                      recipe: ford
                      persona: |
                        You evaluate refactors for runtime
                        performance and resource cost. Ignore
                        security and team ergonomics.
                    - name: maintainability-reviewer
                      recipe: ford
                      persona: |
                        You evaluate refactors for long-term
                        code health: readability, test coverage,
                        coupling. Ignore security and performance.
                  synthesisPrompt: |
                    Combine the security, performance, and
                    maintainability reviews into ONE
                    recommendation: ship-as-is, ship-with-fixes
                    (list the fixes), or defer. Reference each
                    reviewer by name when you cite a concern.

            ── EXAMPLE B: DEBATE ──

                name: ship-now-vs-wait-debate
                description: |
                  Pro/contra debate over shipping a release before
                  the security audit completes. Two heads push back
                  on each other for up to 3 rounds; synthesis
                  reflects whether they converged.
                engine: zaphod
                params:
                  pattern: DEBATE
                  maxRounds: 3
                  heads:
                    - name: pro
                      recipe: ford
                      persona: |
                        You argue FOR shipping the release on the
                        original date. Strongest arguments: time
                        pressure, market window, the audit can
                        run in parallel.
                        Respond to counter-arguments concretely;
                        change your position ONLY when the
                        counter is objectively stronger than your
                        original reasoning — never as a courtesy.
                    - name: contra
                      recipe: ford
                      persona: |
                        You argue AGAINST shipping before the
                        audit completes. Strongest arguments:
                        unknown vulnerabilities, customer trust,
                        cost of post-hoc fixes.
                        Respond to counter-arguments concretely;
                        change your position ONLY when the
                        counter is objectively stronger than your
                        original reasoning — never as a courtesy.
                  synthesisPrompt: |
                    Consolidate the FINAL positions of pro and
                    contra. Structure:
                    1. Did they agree on a path forward, or does
                       material dissent remain after the rounds?
                    2. Which arguments were decisive (or where
                       does the disagreement still lie)?
                    3. One concrete recommendation with reasoning.
                       If consensus was not reached, take an
                       explicit position and name the residual
                       trade-off.

            ── justifications map (mandatory) ──

            Every constraint-key you set in the YAML MUST point
            here to an sg-id that exists in subgoals. Convention:
            - "name" for the recipe name
            - "params.pattern" for the COUNCIL-vs-DEBATE choice
            - "params.maxRounds" if you set it (DEBATE only)
            - "params.heads.<idx>.persona" for each head's
              perspective choice
            - "params.synthesisPrompt" for the synthesizer shape

            confidence:
            - 1.0 minus the speculative share = a coarse heuristic
            - VALIDATING will check the shape.

            shapeRationale: WHY this PATTERN (council vs. debate)
            and WHY this head composition. The pattern choice is
            the single most important decision — name it.

            Language: persona and synthesisPrompt are read by
            downstream LLMs as orchestration code — write them
            in English. The user-facing content language is
            carried separately by the goal text.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.ZAPHOD_RECIPE;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        return true;
    }

    @Override
    public void appendProposingContext(
            StringBuilder sb, ArchitectState state,
            List<ResolvedRecipe> availableRecipes) {
        sb.append("Available head recipes in the project (each "
                + "head's `recipe` field must reference one of "
                + "these — typically `ford` or a ford-style "
                + "conversational recipe). The same recipe list "
                + "applies to BOTH council and debate heads — "
                + "what differs is the persona shape, not the "
                + "underlying worker:\n");
        if (availableRecipes.isEmpty()) {
            sb.append("  (none listed — use the bundled `ford` "
                    + "recipe for every head; it is always "
                    + "available)\n\n");
        } else {
            for (ResolvedRecipe r : availableRecipes) {
                sb.append("  - ").append(r.name())
                        .append(" [engine=").append(r.engine())
                        .append("]: ")
                        .append(abbrev(r.description(), 100))
                        .append("\n");
            }
            sb.append("\nPick the simplest recipe that fits each "
                    + "head's role. The persona block carries the "
                    + "perspective; the recipe carries the "
                    + "execution shape. `ford` is the safe default "
                    + "when no specialised recipe matches.\n\n");
        }
    }

    @Override
    public String expectedEngineName() {
        return "zaphod";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        try {
            ZaphodHeadsParser.parseRecipe(draft.getYaml(),
                    "slartibartfast/" + draft.getName());
            report.add(ValidationCheck.builder()
                    .rule(RULE_ZAPHOD_RECIPE_PARSES).passed(true)
                    .message("Zaphod council recipe parses cleanly")
                    .build());
            return null;
        } catch (RuntimeException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_ZAPHOD_RECIPE_PARSES).passed(false)
                    .message("ZaphodHeadsParser rejected the recipe: "
                            + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }
    }

    @Override
    public boolean wantsPathPersistenceCheck() {
        // Zaphod outputs are written by spawned head sub-processes
        // and the synthesizer turn — not by tool calls embedded in
        // the Slart-emitted recipe yaml. The generic substring-based
        // path check would always fail by construction (no
        // doc_create in heads/persona or synthesisPrompt by
        // design). Until a council-specific path check exists, we
        // skip it for ZAPHOD_RECIPE.
        return false;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected recipe YAML as a JSON object "
                + "with a valid name, engine: zaphod, "
                + "params.pattern: COUNCIL or DEBATE, a non-empty "
                + "params.heads list (each with name + recipe + "
                + "persona; DEBATE needs >= 2 heads), and — for "
                + "DEBATE only — an optional params.maxRounds in "
                + "[1..10] (default 3). Include "
                + "params.synthesisPrompt and justifications "
                + "pointing to existing sg-ids from the list "
                + "above. If the validator complained about the "
                + "pattern choice, re-read the rules: COUNCIL = "
                + "single-shot tallying of orthogonal lenses; "
                + "DEBATE = multi-round between positional "
                + "opponents (pro/contra-style personae, not "
                + "expert-lens descriptions).";
    }

    // ──────────────────── helpers ────────────────────

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
