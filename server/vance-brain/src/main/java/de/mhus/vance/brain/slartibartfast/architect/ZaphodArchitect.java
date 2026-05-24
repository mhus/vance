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
 * Zaphod council-recipe architect. Produces recipes with
 * {@code engine: zaphod}, a non-empty
 * {@code params.heads} list (each with name + recipe + persona),
 * and a {@code params.synthesisPrompt} for the aggregator turn.
 *
 * <p>Validation gate: the full recipe parses via
 * {@link ZaphodHeadsParser#parseRecipe} — mirrors the engine's
 * own start-up validation, so a recipe that passes here will
 * also pass {@code ZaphodEngine.buildInitialState}.
 *
 * <p>See {@code specification/zaphod-engine.md} for the council
 * lifecycle.
 */
@Component
@Slf4j
public class ZaphodArchitect implements SchemaArchitect {

    public static final String RULE_ZAPHOD_RECIPE_PARSES =
            "zaphod-recipe-heads-parse";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce an
            executable recipe for the Zaphod engine — a council
            that drives N heads (each a sub-process) sequentially
            against the same goal, then synthesises their outputs.

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
                    "params.heads.0.persona": "<sg-id>",
                    "params.synthesisPrompt": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this council shape — 1-2 sentences>"
                }

            ── YAML structure (mandatory) ──

                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - name: <unique kebab-case head-name>
                      recipe: <recipe-name from the available list>
                      persona: |
                        <1-3 sentences describing this head's
                        perspective / bias / role>
                    - name: ...
                      recipe: ...
                      persona: |
                        ...
                  synthesisPrompt: |
                    <instruction for the synthesizer turn — describes
                    the content/shape of the consolidated synthesis,
                    NOT how to persist it (that is engine business)>

            ── HEADS-SHAPE rules ──

            - 2-5 heads is the sweet spot. Below 2 there is nothing
              to synthesise; above 5 the council becomes noisy.
              The hard cap is 7 — exceeding it fails validation.
            - Head names MUST be unique within the council.
              Convention: kebab-case role-words (e.g.
              `security-reviewer`, `cost-optimist`).
            - Each head's `persona` should make the perspective
              *distinct*: name a viewpoint, bias, or expert role
              that produces meaningfully different framing.
              Personae like "a careful thinker" or "a general
              assistant" are useless — the heads collapse to the
              same answer.
            - `recipe` references an existing recipe in the
              project (typically `ford` or a ford-style variant
              listed in the "Available head recipes" block).
              Inventing names ("council-head", "perspective-x")
              fails at execution time.

            ── SYNTHESIS PROMPT rules ──

            - Tell the synthesizer what to *do* with the heads'
              outputs: prioritise / contrast / consolidate.
              "Synthesise the outputs" alone is too vague.
            - Reference the heads by their `name` so the
              synthesizer can ground each strand.
            - Optional but recommended: name the deliverable
              shape (one paragraph, a bulleted decision matrix,
              a recommendation with caveats).

            ── CRITICAL: synthesizer has NO TOOLS ──

            The synthesizer is a direct LLM call with structured
            JSON output — it does NOT have access to
            `doc_create_text`, `doc_write_text`, `doc_create_kind`
            or any other file-writing tool. The Zaphod engine
            persists the synthesis document deterministically from
            the LLM's structured reply.

            DO NOT write a synthesisPrompt that asks the
            synthesizer to "create a document", "save the answer
            as a file", "write to <path>", or anything similar.
            Those instructions cause the LLM to hallucinate
            pseudo-tool-call text ("doc_create_kind(path=…)") in
            its reply body, which the engine then has to recover
            from.

            DO write a synthesisPrompt that describes the CONTENT
            and SHAPE of the synthesis text itself. Persistence is
            an engine concern — head replies AND the final synthesis
            are written to `_zaphod-drafts/<processId>/` by the
            engine; you do NOT configure output paths.

            ── EXAMPLE ──

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

            ── justifications map (mandatory) ──

            Every constraint-key you set in the YAML MUST point
            here to an sg-id that exists in subgoals. Convention:
            - "name" for the recipe name
            - "params.heads.<idx>.persona" for each head's
              perspective choice
            - "params.synthesisPrompt" for the synthesizer shape

            confidence:
            - 1.0 minus the speculative share = a coarse heuristic
            - VALIDATING will check the council shape.

            shapeRationale: WHY this count and combination of
            heads. Refers to the council composition, not
            individual personae.

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
                + "conversational recipe):\n");
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
        // doc_write_text in heads/persona or synthesisPrompt by
        // design). Until a council-specific path check exists, we
        // skip it for ZAPHOD_RECIPE.
        return false;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected recipe YAML as a JSON object "
                + "with a valid name, engine: zaphod, "
                + "params.pattern: COUNCIL, a non-empty "
                + "params.heads list (each with name + recipe + "
                + "persona), params.synthesisPrompt, and "
                + "justifications all pointing to existing "
                + "sg-ids from the list above.";
    }

    // ──────────────────── helpers ────────────────────

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
