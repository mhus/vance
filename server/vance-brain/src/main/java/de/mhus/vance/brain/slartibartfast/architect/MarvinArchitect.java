package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Marvin-recipe architect. Produces recipes with
 * {@code engine: marvin}, a non-blank top-level
 * {@code promptPrefix} (the PLAN-LLM instruction), and a
 * {@code params} block carrying the Marvin runtime constraints
 * (allowedSubTaskRecipes, recipesOnlyViaExpand,
 * allowedExpandDocumentRefPaths, disallowedTaskKinds,
 * defaultExecutionMode, maxPlanCorrections).
 *
 * <p>Status note: as documented in
 * {@code specification/slartibartfast-engine.md} §4, the
 * Marvin-recipe path ships skeleton-only today. The system prompt
 * and validators below are production-shaped — the open work is
 * promoting PROPOSING from placeholder to fully-driven Marvin
 * output. Carrying this code as its own bean isolates that work
 * from Vogon and Zaphod when it lands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinArchitect implements SchemaArchitect {

    public static final String RULE_MARVIN_RECIPE_SHAPE =
            "marvin-recipe-shape";
    public static final String RULE_MARVIN_PROMPT_PREFIX =
            "marvin-recipe-prompt-prefix-present";
    public static final String RULE_PROMPT_PREFIX_TEMPLATE_VALID =
            "recipe-prompt-prefix-pebble-template-valid";
    public static final String RULE_MARVIN_RECIPES_EXIST =
            "marvin-recipe-allowed-recipes-exist";
    public static final String RULE_MARVIN_TASKKINDS_VALID =
            "marvin-recipe-prompt-prefix-taskkinds-valid";
    public static final String RULE_MARVIN_NO_ROOT_POSTACTIONS =
            "marvin-recipe-no-root-postactions";
    public static final String RULE_MARVIN_POSTACTION_VARS =
            "marvin-recipe-postaction-variables-valid";

    /** Classpath location of the bundled Marvin recipe templates.
     *  Each template is a Pebble file with a {@code params}-shaped
     *  context plus literal {@code {% verbatim %}} blocks around
     *  any inner Pebble that should reach Marvin's runtime. */
    private static final String TEMPLATE_PREFIX =
            "vance-defaults/manuals/slartibartfast/marvin-architect/templates/";

    /** Whitelist of supported template IDs. The LLM must pick one
     *  via the {@code templateId} field; anything else is rejected
     *  at extraction time. New templates are added here. */
    private static final Set<String> SUPPORTED_TEMPLATE_IDS = Set.of(
            "research-aggregate-write",
            "doc-driven-chapters",
            "decide-with-user-input");

    /** Path segments that postAction args.path must NEVER target.
     *  System-reserved buckets owned by engines / Slart. */
    private static final List<String> RESERVED_PATH_PREFIXES = List.of(
            "recipes/", "_user/", "_vance/", "_slart/", "_tenant/",
            "_zaphod-drafts/", "_vogon-drafts/");

    /** Template-source cache. Loaded once per JVM. */
    private final Map<String, String> templateSourceCache = new ConcurrentHashMap<>();

    /** Dedicated Pebble engine for the YAML recipe templates with
     *  {@code newLineTrimming} disabled — Pebble's default eats one
     *  newline after each tag, which collapses YAML structure when
     *  rendering an entire recipe (top-level keys end up on the
     *  same line). The shared {@link PromptTemplateRenderer} keeps
     *  the default behaviour because Slart's other Pebble usage
     *  (promptPrefix conditionals) was authored against it. */
    private final io.pebbletemplates.pebble.PebbleEngine templateEngine =
            new io.pebbletemplates.pebble.PebbleEngine.Builder()
                    .loader(new io.pebbletemplates.pebble.loader.StringLoader())
                    .strictVariables(false)
                    .autoEscaping(false)
                    .newLineTrimming(false)
                    .extension(new de.mhus.vance.brain.prompt.JinjaCompatExtension())
                    .build();

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            You build a Marvin recipe from a framed goal, subgoals
            and available sub-recipes.

            IMPORTANT — you do NOT write YAML. You select a bundled
            template and fill in its parameters as JSON. The engine
            then renders the template into a guaranteed-valid Marvin
            recipe. Your job is parameter selection, not YAML
            authoring.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Top-level schema:
                {
                  "name":            "<kebab-case recipe name>",
                  "templateId":      "<one of the supported templates below>",
                  "params":          { <template-specific params> },
                  "justifications":  { "<field-path>": "<sg-id>", ... },
                  "confidence":      <0.0..1.0>,
                  "shapeRationale":  "<why this template + these params, 1-2 sentences>"
                }

            ── Supported templates ──

            Three templates ship today. Pick the one whose tree
            shape matches the user's request — not the one whose
            name sounds closest.

            template "research-aggregate-write" — the default for
            "research a topic and produce a report" missions. The
            engine renders a Marvin recipe of this exact shape:
              * N parallel WORKER children, each running a chosen
                gatherer recipe (web-research, analyze, …) on one
                aspect of the topic;
              * one AGGREGATE child that synthesizes the gathered
                material into a final text result;
              * one postAction on the AGGREGATE that writes the
                synthesized text to the requested output path.
            File persistence is engine-side and deterministic — you
            do NOT need to plan a separate "save" worker.

            Params shape for "research-aggregate-write":
                {
                  "name":              "<same as top-level name>",
                  "description":       "<one-line recipe description>",
                  "gathererRecipe":    "<name of a real project recipe>",
                  "aspects": [
                    { "role": "<short label>",
                      "goal": "<what this aspect researches, plain text>" },
                    ...
                  ],
                  "synthesisPrompt":   "<instruction for the AGGREGATE LLM>",
                  "language":          "<ISO 639-1, e.g. \\"de\\" or \\"en\\">",
                  "reportLengthWords": "<optional, e.g. \\"1500-2000\\">",
                  "maxOutputChars":    <optional integer, default 15000>,
                  "outputPathTpl":     "<path with optional Pebble: research/{{ process.goal | slug }}/report.md>",
                  "outputTitleTpl":    "<optional title with Pebble allowed>",
                  "processGoalLabel":  "<optional short label like \\"topic\\" or \\"question\\">"
                }

            template "doc-driven-chapters" — for "write a multi-
            chapter document where the chapter count comes from an
            outline" missions (essays, multi-section reports). The
            engine renders this shape:
              * one WORKER (marvin-worker) that writes an outline
                document to `outlinePath`;
              * one EXPAND_FROM_DOC that iterates the outline and
                spawns one marvin-worker per item to write a
                chapter file under `chaptersDir/<slug>.md`;
              * optional AGGREGATE that consolidates all chapters
                into one final document at `finalPath`.

            Params shape for "doc-driven-chapters":
                {
                  "name":              "<same as top-level name>",
                  "description":       "<one-line recipe description>",
                  "outlinePrompt":     "<instruction for the outline-writing WORKER>",
                  "outlinePath":       "<path of the outline doc, e.g. essays/{{ process.goal | slug }}/outline.md>",
                  "chaptersDir":      "<directory for chapter files, e.g. essays/{{ process.goal | slug }}/chapters>",
                  "chapterPromptTpl": "<chapter-worker goal — may reference {{ item.text }} from the outline iteration>",
                  "consolidate":      <boolean — true to add a final consolidation step>,
                  "consolidatePrompt":"<required when consolidate=true; AGGREGATE prompt>",
                  "finalPath":         "<required when consolidate=true; e.g. essays/{{ process.goal | slug }}/final.md>",
                  "outputTitleTpl":    "<optional title for the final doc>",
                  "language":          "<ISO 639-1>",
                  "maxOutputChars":    <optional integer, default 20000>,
                  "processGoalLabel":  "<optional short label>"
                }

            template "decide-with-user-input" — for "help me decide
            X — ask me what you need to know first" missions. The
            engine renders this shape:
              * N USER_INPUT children (one per clarification
                question) — Marvin parks waiting for the user's
                inbox answer for each;
              * one WORKER (marvin-worker) that synthesizes the
                user's answers + the original goal into a decision
                document and writes it via a postAction.

            Params shape for "decide-with-user-input":
                {
                  "name":              "<same as top-level name>",
                  "description":       "<one-line recipe description>",
                  "questions": [
                    {
                      "role":         "<short label, e.g. 'skill-level'>",
                      "title":        "<short question shown in the inbox>",
                      "body":         "<longer explanation if needed>",
                      "type":         "DECISION" | "FEEDBACK" | "APPROVAL",
                      "criticality":  "LOW" | "NORMAL" | "HIGH" | "URGENT",
                      "options":      [ "<option 1>", "<option 2>", ... ]   <-- optional, only for DECISION
                    },
                    ...
                  ],
                  "decisionPrompt":    "<worker instruction that synthesizes answers + goal into a decision>",
                  "outputPathTpl":     "<where the decision doc is written>",
                  "outputTitleTpl":    "<optional title>",
                  "language":          "<ISO 639-1>",
                  "processGoalLabel":  "<optional short label>"
                }

            ── Aspect design ──

            Each aspect is one WORKER child. The runtime PLAN spawns
            them in declared order. Choose 3-7 aspects that together
            cover the user's request; fewer than 3 is usually too
            shallow, more than 7 is bloat. Aspects should be
            topic-agnostic — they describe WHAT to research, not the
            topic itself (the topic comes from the process goal at
            runtime). Bad: "Recherchiere die Geschichte der Atomkraft."
            Good: "Geschichte und politischer Kontext."

            ── Recipe selection ──

            "gathererRecipe" must be one of the actual project
            recipes listed in the user prompt. The recipe must be a
            single-shot summary-in-reply worker — typical choices
            are `web-research`, `analyze`, `code-read`. If you need
            file I/O capability, choose `marvin-worker`. Do NOT
            invent recipe names.

            ── Path templates ──

            `outputPathTpl` is a Pebble string emitted verbatim into
            the rendered recipe. You may use:
              {{ process.goal | slug }}  → URL-safe slug of the topic
              {{ process.goal }}         → raw topic text (NOT for paths)
            Reserved prefixes you must NEVER use:
              recipes/, _user/, _vance/, _slart/, _tenant/,
              _zaphod-drafts/, _vogon-drafts/
            Pick a fresh, descriptive folder under e.g. `research/`,
            `reports/`, `documents/`.

            ── justifications map ──

            Every params-field you set MUST point to an sg-id that
            exists in the subgoal list. The justification keys are
            field paths into params, e.g.:
              "params.aspects":         "sg1"
              "params.synthesisPrompt": "sg3"
              "params.outputPathTpl":   "sg4"

            ── Worked example ──

                {
                  "name": "deep-research",
                  "templateId": "research-aggregate-write",
                  "params": {
                    "name": "deep-research",
                    "description": "Systematic web research with synthesized report.",
                    "gathererRecipe": "web-research",
                    "aspects": [
                      {"role":"history",  "goal":"History and political context."},
                      {"role":"tech",     "goal":"Current technology and state of the art."},
                      {"role":"pros",     "goal":"Pro arguments and economic perspectives."},
                      {"role":"cons",     "goal":"Cons, risks and counter-arguments."},
                      {"role":"future",   "goal":"Future outlook and research trends."}
                    ],
                    "synthesisPrompt": "Verdichte die Recherche zu einem strukturierten Bericht mit Einleitung, Pro-/Contra-Hauptteil und Fazit. Belege Aussagen mit eingebetteten Quellenreferenzen.",
                    "language": "de",
                    "reportLengthWords": "1500-2000",
                    "outputPathTpl": "research/{{ process.goal | slug }}/report.md",
                    "outputTitleTpl": "Research Report — {{ process.goal }}",
                    "processGoalLabel": "research topic"
                  },
                  "justifications": {
                    "params.aspects":         "sg1",
                    "params.synthesisPrompt": "sg3",
                    "params.outputPathTpl":   "sg4"
                  },
                  "confidence": 0.95,
                  "shapeRationale": "Five-aspect research pipeline with AGGREGATE synthesis and final-file postAction matches the user's 'research a topic and write a report' intent."
                }

            If you violate this contract the validator rejects your
            output and asks you to correct it.
            """;

    private static final String SYSTEM_PROMPT_LEGACY = """
            (deprecated, kept for reference only — the current Marvin
            architect emits template parameters, not free-form YAML)
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal, the subgoals, and the list of
            available sub-recipes, you produce a Marvin recipe.
            Marvin's PLAN validator enforces your constraints at
            runtime — if you omit them the runtime PLAN-LLM takes
            shortcuts and the pipeline does not run through.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML>",
                  "justifications": {
                    "params.allowedSubTaskRecipes": "<sg-id>",
                    "promptPrefix":                 "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            ── COMPLETENESS REQUIREMENT ──

            Your recipe MUST drive the user's request to a final
            deliverable, not just one stage of it. If the user
            asks for an essay, the recipe MUST run outline →
            chapter writing → aggregation (final consolidation).
            Producing only the outline phase, only chapters, or
            stopping before aggregation is a hard failure: the
            user gets no usable result and the validator will
            reject the recipe.

            When the available sub-recipes include outline-style,
            chapter-style, AND aggregator-style entries, you MUST
            wire all three into allowedSubTaskRecipes and the
            promptPrefix. Pick fewer phases ONLY when the user's
            request truly needs less (e.g. they explicitly asked
            for an outline only).

            ── MANDATORY CONSTRAINTS (set when applicable) ──

            These constraints are NOT optional when the inputs
            motivate them. Slartibartfast detects motivated
            constraints from the subgoals + the available sub-recipes:

            **allowedSubTaskRecipes** — REQUIRED whenever the
              subgoals map to concrete sub-recipes. Inspect the
              "Available sub-recipes" block in the user prompt. A
              subgoal that "writes plot/cast/outline" maps to an
              outline-style recipe; "writes chapters" to a
              chapter-style recipe; "consolidates/aggregates" to an
              aggregator-style recipe. List EXACTLY the recipes
              your plan phases will use.

            **recipesOnlyViaExpand** — REQUIRED when a subgoal
              iterates "per item in a document". List the recipes
              that may appear ONLY inside an EXPAND_FROM_DOC
              childTemplate (typical: chapter-loop for
              chapter-per-outline-item).

            **allowedExpandDocumentRefPaths** — REQUIRED when you
              set recipesOnlyViaExpand. List the document paths the
              EXPAND_FROM_DOC iterates over (e.g.
              "essay/outline.md").

            **disallowedTaskKinds** — Set [AGGREGATE] when your
              plan shape needs a WORKER aggregator (instead of
              Marvin's built-in AGGREGATE summary). Standard for
              pipelines with an aggregator-style recipe.

            **defaultExecutionMode: SEQUENTIAL** — Default when the
              plan phases build on each other (outline → chapters →
              aggregator). Use PARALLEL only when phases are
              independent.

            **maxPlanCorrections: 2** — Default. Omit only for
              extremely conservative use cases.

            ── promptPrefix CONTRACT (the runtime PLAN-LLM reads it) ──

            **CRITICAL — KIND-block parity rule:**
              The number of KIND blocks in your promptPrefix MUST
              equal the size of allowedSubTaskRecipes (and the
              number of children Marvin's PLAN should emit).
              EVERY recipe in allowedSubTaskRecipes MUST have
              exactly one KIND block referencing it (either as a
              direct WORKER taskSpec.recipe, or — for entries in
              recipesOnlyViaExpand — as the EXPAND_FROM_DOC
              childTemplate.recipe). If you list 3 recipes you MUST
              write 3 KIND blocks; the runtime LLM otherwise drops
              the trailing ones.

            **JSON skeleton rule:** Each KIND block MUST contain a
              concrete JSON skeleton for that child (literal
              taskKind / goal / taskSpec). Plain prose without a
              JSON sample lets the LLM omit the child.

            **Order rule:** The KIND blocks MUST appear in
              execution order (the order Marvin will use under
              SEQUENTIAL). The order also reflects the
              data-dependency chain (a phase that consumes
              `essay/outline.md` comes after the phase that
              produces it).

            **No manual fan-out:** "Iterate per item in a document"
              ALWAYS means EXPAND_FROM_DOC with documentRef +
              childTemplate. Never enumerate items by hand.

            ── YAML structure ──

                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - <recipe1-name>
                    - <recipe2-name>
                  recipesOnlyViaExpand:
                    - <chapter-loop-name>
                  allowedExpandDocumentRefPaths:
                    - <e.g. essay/outline.md>
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the `<name>` PLAN node. Emit EXACTLY N
                  children in this exact order. N MUST equal the
                  number of recipes in allowedSubTaskRecipes.

                  KIND 1 — <description matching subgoal sg1>
                  <one-line literal JSON skeleton for child 1>

                  KIND 2 — <description matching sg2>
                  <one-line literal JSON skeleton for child 2>

                  ...

                  Output contract — ONLY these N children:
                      {"children": [<KIND 1>, <KIND 2>, ...]}

                  Do not omit any KIND. Do not add extras. The
                  number of children MUST be exactly N.

            ── EXAMPLE (essay-style pipeline, N=3) ──

                name: my-essay-pipeline
                description: |
                  Produces an essay through outline → chapters → aggregation.
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - outline_loop
                    - chapter_loop
                    - aggregator_run
                  recipesOnlyViaExpand:
                    - chapter_loop
                  allowedExpandDocumentRefPaths:
                    - essay/outline.md
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the my-essay-pipeline PLAN node. Emit
                  EXACTLY 3 children, one per recipe in
                  allowedSubTaskRecipes [outline_loop, chapter_loop,
                  aggregator_run], in this exact order:

                  KIND 1 — WORKER outline_loop (produces essay/outline.md):
                  {"taskKind":"WORKER","goal":"Draft plot, cast, and outline.","taskSpec":{"recipe":"outline_loop"}}

                  KIND 2 — EXPAND_FROM_DOC over outline (one chapter per item):
                  {"taskKind":"EXPAND_FROM_DOC","goal":"Run one chapter_loop per outline item.",
                   "taskSpec":{"documentRef":{"path":"essay/outline.md"},
                               "treeMode":"FLAT",
                               "childTemplate":{"taskKind":"WORKER","recipe":"chapter_loop","goal":"Write the chapter."}}}

                  KIND 3 — WORKER aggregator_run (consolidates chapters → final-essay.md):
                  {"taskKind":"WORKER","goal":"Consolidate chapters into the final essay and post the inbox notification.","taskSpec":{"recipe":"aggregator_run"}}

                  Output contract — EXACTLY these 3 children, no fewer:
                      {"children":[<KIND 1>,<KIND 2>,<KIND 3>]}

                  Do not omit KIND 3. The number of children MUST be 3.

            ── Language ──

            The promptPrefix you generate MUST be in English (the
            runtime PLAN-LLM reads it as orchestration code). The
            user-facing content language is carried separately by
            the goal text and is not your concern here.

            ── promptPrefix is a Pebble template ──

            promptPrefix is rendered through Pebble before the
            PLAN-LLM sees it, so plain prose passes through verbatim.
            If you need a tier-aware variant (rare for PLAN nodes),
            you may use:
                {% if tier == "small" %}…{% else %}…{% endif %}
            with `elseif` (NOT `elif`). Available variables:
            tier, model, provider, mode, profile, recipe, engine,
            params. Avoid Pebble syntax unless you actually need it
            — plain text is the safer default. Anything that looks
            like {{ … }} or {% … %} but isn't intended as a
            template will be parsed as Pebble; escape with
            {% raw %}…{% endraw %} if you must include braces
            literally.

            ── justifications map ──

            Every constraint-key you set in the YAML (params.X or
            promptPrefix) MUST point to an sg-id that exists in
            the subgoal list.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final RecipeLoader recipeLoader;
    private final PromptTemplateRenderer promptTemplateRenderer;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.MARVIN_RECIPE;
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
        sb.append("Available sub-recipes in the project (excluding "
                + "your own _slart/* generated bucket):\n");
        if (availableRecipes.isEmpty()) {
            sb.append("  (none)\n\n")
                    .append("Because there are NO project sub-recipes:\n")
                    .append("  - DO NOT set params.allowedSubTaskRecipes "
                            + "(omit the field entirely).\n")
                    .append("  - DO NOT set params.recipesOnlyViaExpand.\n")
                    .append("  - Inventing recipe names ('web-research', "
                            + "'analyze', 'marvin-worker', etc.) will be "
                            + "rejected by the validator — those names "
                            + "do not resolve at runtime.\n")
                    .append("  - Drive the plan via the promptPrefix "
                            + "alone; let Marvin's PLAN-LLM pick task "
                            + "kinds (WORKER without recipe = generic "
                            + "ford worker, EXPAND_FROM_DOC, etc.) at "
                            + "runtime.\n\n");
        } else {
            for (ResolvedRecipe r : availableRecipes) {
                sb.append("  - ").append(r.name())
                        .append(" [engine=").append(r.engine())
                        .append("]: ")
                        .append(abbrev(r.description(), 100))
                        .append("\n");
            }
            sb.append("\nIf your subgoals map to any of these recipes, "
                    + "set allowedSubTaskRecipes to the matching subset "
                    + "and reference each recipe in the promptPrefix as "
                    + "`taskSpec.recipe`. Remember the KIND-block parity "
                    + "rule: the number of KIND blocks MUST equal the "
                    + "size of allowedSubTaskRecipes. Use ONLY the names "
                    + "listed above — every name must resolve to a real "
                    + "project recipe.\n\n");
        }
    }

    @Override
    public String expectedEngineName() {
        return "marvin";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        Object pp = recipeMap.get("promptPrefix");
        if (!(pp instanceof String ppStr) || ppStr.isBlank()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_MARVIN_PROMPT_PREFIX).passed(false)
                    .message("MARVIN_RECIPE must declare a non-blank "
                            + "top-level 'promptPrefix' (the PLAN-LLM "
                            + "instruction)")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_PROMPT_PREFIX).passed(true)
                .message("promptPrefix present (" + ppStr.length()
                        + " chars)").build());

        // Recipes carry promptPrefix as a Pebble template (tier /
        // mode / model conditions live inside the body). Compile
        // it now so a syntax slip surfaces at validation time, not
        // at first turn.
        try {
            promptTemplateRenderer.compile(ppStr);
            report.add(ValidationCheck.builder()
                    .rule(RULE_PROMPT_PREFIX_TEMPLATE_VALID).passed(true)
                    .message("promptPrefix is a valid Pebble template")
                    .build());
        } catch (PromptTemplateException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_PROMPT_PREFIX_TEMPLATE_VALID).passed(false)
                    .message("promptPrefix is not a valid Pebble template: "
                            + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }

        Object params = recipeMap.get("params");
        if (!(params instanceof Map<?, ?>)) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPE_SHAPE).passed(false)
                    .message("MARVIN_RECIPE must declare a 'params' map "
                            + "(may be empty for a default Marvin run)")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_RECIPE_SHAPE).passed(true)
                .message("params block present").build());

        // Reject postActions anywhere in the recipe YAML — empirical:
        // PROPOSING keeps inventing new nesting levels for them
        // (root, params.postActions, …). Marvin's runtime ONLY reads
        // node.taskSpec.postActions, and those live inside the
        // KIND-block JSON skeletons embedded as strings in
        // promptPrefix — never as YAML keys. So any postActions key
        // we find walking the recipeMap is by definition misplaced.
        java.util.List<String> misplacedPostActionPaths = new java.util.ArrayList<>();
        scanForMisplacedPostActions(recipeMap, "", misplacedPostActionPaths);
        if (!misplacedPostActionPaths.isEmpty()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_MARVIN_NO_ROOT_POSTACTIONS).passed(false)
                    .message("MARVIN_RECIPE has 'postActions' at YAML "
                            + "location(s) " + misplacedPostActionPaths
                            + " — Marvin only reads postActions from "
                            + "node.taskSpec.postActions, and the only "
                            + "valid place to declare them is INSIDE "
                            + "the KIND-block JSON skeleton in "
                            + "promptPrefix (as `\"taskSpec\":{ ..., "
                            + "\"postActions\":[...] }`). Remove the "
                            + "YAML-level postActions block(s) and "
                            + "embed the postActions into the taskSpec "
                            + "of the node whose output should be "
                            + "persisted (typically the AGGREGATE "
                            + "child or the final WORKER). YAML-level "
                            + "postActions are silently dropped at "
                            + "runtime.")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_NO_ROOT_POSTACTIONS).passed(true)
                .message("no misplaced YAML-level postActions (correct)")
                .build());

        // Scan promptPrefix for invented TaskKinds. PROPOSING tends
        // to interpolate plausible-but-fake kinds like
        // EXPAND_FROM_PROMPT from EXPAND_FROM_DOC. Marvin's runtime
        // accepts only PLAN, EXPAND_FROM_DOC, WORKER, USER_INPUT,
        // AGGREGATE — anything else fails the PLAN-LLM's first turn
        // hours later. Catch it now.
        ValidationCheck taskKindCheck =
                checkPromptPrefixTaskKinds(ppStr);
        report.add(taskKindCheck);
        if (!taskKindCheck.isPassed()) return taskKindCheck;

        // Scan postAction template strings (embedded in the
        // promptPrefix as KIND-block JSON) for Pebble {{ var.… }}
        // references with unknown root identifiers. Live 2026-05-24:
        // PROPOSING wrote {{ process.recipe.yaml }} and similar
        // hallucinated paths that render to "" at runtime and
        // produce empty files. Better to reject at recipe-load with
        // the allow-list spelled out.
        ValidationCheck postActionVarCheck =
                checkPostActionVariables(ppStr);
        report.add(postActionVarCheck);
        if (!postActionVarCheck.isPassed()) return postActionVarCheck;

        // MARVIN_RECIPE: every entry in allowedSubTaskRecipes /
        // recipesOnlyViaExpand MUST be a real project recipe and
        // must NOT be a reserved engine name. Empirical:
        // PROPOSING fabricates plausible-sounding names ('ford',
        // 'web-research', 'marvin-worker') when the project has
        // no real sub-recipes — Marvin's runtime PLAN-validator
        // rejects those, and the whole pipeline aborts. Catching
        // it here saves a wallclock cycle through Marvin.
        @SuppressWarnings("unchecked")
        ValidationCheck recipeCheck = checkAllowedSubTaskRecipes(
                (Map<String, Object>) params, process);
        report.add(recipeCheck);
        return recipeCheck.isPassed() ? null : recipeCheck;
    }

    /**
     * Walks the recipe YAML and collects any {@code postActions}
     * key paths found at the recipe-data level. The ONLY valid
     * location for postActions is inside a KIND-block JSON
     * skeleton embedded as a string inside {@code promptPrefix} —
     * so any postActions key we hit by walking the parsed
     * YAML structure is misplaced and silently dropped at runtime.
     *
     * <p>Path strings are dotted for readable diagnostics
     * ({@code "params.postActions"}, {@code "postActions"}).
     */
    @SuppressWarnings("unchecked")
    private static void scanForMisplacedPostActions(
            Object node, String path, java.util.List<String> hits) {
        if (node instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if ("postActions".equals(key)) {
                    hits.add(childPath);
                }
                scanForMisplacedPostActions(e.getValue(), childPath, hits);
            }
        } else if (node instanceof java.util.List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanForMisplacedPostActions(
                        list.get(i), path + "[" + i + "]", hits);
            }
        }
    }

    /**
     * Scans the promptPrefix for Pebble {@code {{ var.subvar }}}
     * references and flags any whose root identifier is not in
     * the Marvin postAction context allow-list. Catches LLM
     * hallucinations like {@code {{ process.recipe.yaml }}} or
     * {@code {{ aggregate.result.text }}} that render to "" at
     * runtime and silently produce empty files.
     *
     * <p>Allowed roots: {@code node}, {@code result} (alias for
     * node), {@code process}, plus the universal Pebble built-ins
     * that engines configure (e.g. {@code item} inside an
     * EXPAND_FROM_DOC childTemplate). The check is conservative —
     * we only flag clearly-foreign roots like
     * {@code aggregate.…} / {@code process.recipe.…}.
     */
    private static ValidationCheck checkPostActionVariables(
            String promptPrefix) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)"
                        + "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"
                        + "(?:\\s*\\|\\s*[A-Za-z_][A-Za-z0-9_]*)?"
                        + "\\s*\\}\\}");
        java.util.regex.Matcher m = p.matcher(promptPrefix);
        // Tight allow-list. Generic names like 'input' / 'topic' /
        // 'goal' were removed: the LLM was using them as roots
        // ('{{input.topic}}') and they slipped through, rendering
        // to empty at runtime. The canonical accessors are
        // `process.params.topic` (or `process.goal`) and `node.X`.
        java.util.Set<String> allowedRoots = java.util.Set.of(
                "node",          // canonical postAction root
                "result",        // alias for node (LLM-friendly)
                "process",       // process.goal / process.id / process.params.*
                "item",          // EXPAND_FROM_DOC childTemplate iteration var
                // Pebble template stdlib roots used by promptPrefix
                // conditionals (tier-/mode-/provider-checks):
                "tier", "model", "provider", "mode", "profile",
                "recipe", "engine", "lang", "params");
        java.util.Set<String> bad = new java.util.LinkedHashSet<>();
        // Also collect the full reference for the error message
        // so the LLM can see what it wrote.
        java.util.Map<String, String> badFullRef = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern fullRefP = java.util.regex.Pattern.compile(
                "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)");
        while (m.find()) {
            String root = m.group(1);
            if (!allowedRoots.contains(root)) {
                bad.add(root);
                // Pull the full ref for context
                String matched = m.group();
                java.util.regex.Matcher refM = fullRefP.matcher(matched);
                if (refM.find()) badFullRef.putIfAbsent(root, refM.group(1));
            }
        }
        if (bad.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_POSTACTION_VARS).passed(true)
                    .message("postAction template variables look ok")
                    .build();
        }
        StringBuilder msg = new StringBuilder();
        msg.append("promptPrefix references unknown template root(s): ");
        boolean first = true;
        for (String r : bad) {
            if (!first) msg.append(", ");
            first = false;
            msg.append("'").append(badFullRef.getOrDefault(r, r)).append("'");
        }
        msg.append(". For postAction templates, use only these roots: ")
                .append("node.result / node.summary / node.goal, ")
                .append("process.goal / process.id / process.params.<key>, ")
                .append("or 'result.<x>' (alias for node.<x>). ")
                .append("`process.recipe.<x>`, `aggregate.<x>`, ")
                .append("`worker.<x>` etc. are NOT valid — they are ")
                .append("plausible-sounding but render to empty strings ")
                .append("at runtime, producing empty files.");
        return ValidationCheck.builder()
                .rule(RULE_MARVIN_POSTACTION_VARS).passed(false)
                .message(msg.toString())
                .build();
    }

    /**
     * Scans the promptPrefix for {@code "taskKind"} JSON-style
     * mentions and flags any that aren't one of the runtime-
     * accepted values. The matched names live inside the embedded
     * KIND-block JSON skeletons in the prompt body — Marvin's PLAN
     * runtime parses them as the literal TaskKind enum. A
     * fabricated name (live 2026-05-24: {@code EXPAND_FROM_PROMPT})
     * goes through validation, fails at first PLAN turn, costs an
     * LLM round-trip.
     *
     * <p>Pattern is lenient: any token after {@code "taskKind"} and a
     * quote-or-colon-or-whitespace prefix. Empty / commented-out
     * mentions are ignored.
     */
    private static ValidationCheck checkPromptPrefixTaskKinds(
            String promptPrefix) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "[\"']taskKind[\"']\\s*[:=]\\s*[\"']([A-Z_]+)[\"']");
        java.util.regex.Matcher m = p.matcher(promptPrefix);
        java.util.Set<String> bad = new java.util.LinkedHashSet<>();
        java.util.Set<String> validKinds = java.util.Set.of(
                "PLAN", "EXPAND_FROM_DOC", "WORKER",
                "USER_INPUT", "AGGREGATE");
        while (m.find()) {
            String kind = m.group(1);
            if (!validKinds.contains(kind)) bad.add(kind);
        }
        if (bad.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_TASKKINDS_VALID).passed(true)
                    .message("promptPrefix references only valid TaskKinds")
                    .build();
        }
        return ValidationCheck.builder()
                .rule(RULE_MARVIN_TASKKINDS_VALID).passed(false)
                .message("promptPrefix references invented TaskKind(s): "
                        + String.join(", ", bad)
                        + ". Valid TaskKinds are: "
                        + String.join(", ", validKinds)
                        + ". Rewrite the KIND blocks with one of those.")
                .build();
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        // Same protection for recipe names as for sg-ids: echo
        // the actual project recipe inventory so the LLM can't
        // fabricate plausible-sounding names. allowedSubTaskRecipes
        // and recipesOnlyViaExpand take recipe names, never engine
        // labels.
        List<String> available = listAvailableRecipeNames(process);
        StringBuilder sb = new StringBuilder();
        sb.append("\nValid recipe names (use ONLY these in "
                + "allowedSubTaskRecipes / recipesOnlyViaExpand): ");
        if (available.isEmpty()) {
            sb.append("(none — leave allowedSubTaskRecipes "
                    + "and recipesOnlyViaExpand absent).\n");
        } else {
            sb.append(String.join(", ", available)).append(".\n");
        }
        sb.append("\nEmit a corrected recipe YAML as a JSON object "
                + "with a valid name, engine: marvin, "
                + "params.allowedSubTaskRecipes / "
                + "params.recipesOnlyViaExpand / "
                + "params.allowedExpandDocumentRefPaths / "
                + "params.disallowedTaskKinds set per the "
                + "system-prompt rules, a non-empty promptPrefix "
                + "with one KIND block per recipe, and "
                + "justifications all pointing to existing "
                + "sg-ids from the list above.");
        return sb.toString();
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Validates that every entry in {@code params.allowedSubTaskRecipes}
     * and {@code params.recipesOnlyViaExpand} is a real project
     * recipe — both fields hold recipe names (NOT engine names), so
     * a name that doesn't resolve via the recipe-cascade is by
     * definition wrong (whether the LLM hallucinated it freshly or
     * mis-typed an engine label is irrelevant). Also catches
     * duplicate entries.
     */
    private ValidationCheck checkAllowedSubTaskRecipes(
            Map<String, Object> params, ThinkProcessDocument process) {
        List<String> allowed = readStringList(params.get("allowedSubTaskRecipes"));
        List<String> onlyViaExpand = readStringList(params.get("recipesOnlyViaExpand"));

        if (allowed.isEmpty() && onlyViaExpand.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPES_EXIST).passed(true)
                    .message("no allowedSubTaskRecipes / recipesOnlyViaExpand "
                            + "constraints — no names to validate")
                    .build();
        }

        Set<String> available = new LinkedHashSet<>();
        try {
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")) {
                    available.add(r.name());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' VALIDATING failed listing recipes: {}",
                    process.getId(), e.toString());
        }

        List<String> unknown = new ArrayList<>();
        for (String name : allowed) {
            if (!available.contains(name)) unknown.add(name);
        }
        for (String name : onlyViaExpand) {
            if (!available.contains(name) && !unknown.contains(name)) {
                unknown.add(name);
            }
        }

        Set<String> seen = new HashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        for (String name : allowed) {
            if (!seen.add(name)) dupes.add(name);
        }

        if (!unknown.isEmpty() || !dupes.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            if (!unknown.isEmpty()) {
                msg.append("recipe(s) not found in project: ")
                        .append(String.join(", ", unknown)).append(". ");
            }
            if (!dupes.isEmpty()) {
                msg.append("duplicate recipe name(s) in "
                                + "allowedSubTaskRecipes: ")
                        .append(String.join(", ", dupes)).append(". ");
            }
            if (available.isEmpty()) {
                msg.append("Project has no available recipes — drop the "
                        + "allowedSubTaskRecipes constraint entirely.");
            } else {
                msg.append("Available: ")
                        .append(String.join(", ", available)).append(".");
            }
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPES_EXIST).passed(false)
                    .message(msg.toString().trim())
                    .build();
        }

        return ValidationCheck.builder()
                .rule(RULE_MARVIN_RECIPES_EXIST).passed(true)
                .message("all " + (allowed.size() + onlyViaExpand.size())
                        + " recipe name(s) resolve to project recipes")
                .build();
    }

    private List<String> listAvailableRecipeNames(ThinkProcessDocument process) {
        try {
            List<String> names = new ArrayList<>();
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")) names.add(r.name());
            }
            return names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ──────────────────── Template-driven YAML extraction ────────────────────

    /**
     * Marvin's recipe-YAML is not authored by the LLM. The LLM
     * emits {@code templateId} + {@code params} as JSON; this
     * method validates the params, picks the matching bundled
     * Pebble template, and renders the final recipe YAML
     * deterministically. Failures throw with a message
     * ProposingPhase converts into a re-prompt hint.
     */
    @Override
    public String extractRecipeYaml(Map<String, Object> jsonRoot) {
        Object templateIdObj = jsonRoot.get("templateId");
        if (!(templateIdObj instanceof String templateId)
                || templateId.isBlank()) {
            throw new IllegalArgumentException(
                    "required field 'templateId' missing or blank. "
                            + "Pick one of: " + SUPPORTED_TEMPLATE_IDS);
        }
        String tplId = templateId.trim();
        if (!SUPPORTED_TEMPLATE_IDS.contains(tplId)) {
            throw new IllegalArgumentException(
                    "templateId '" + tplId + "' is not supported. "
                            + "Pick one of: " + SUPPORTED_TEMPLATE_IDS);
        }

        Object paramsObj = jsonRoot.get("params");
        if (!(paramsObj instanceof Map<?, ?> paramsRaw)) {
            throw new IllegalArgumentException(
                    "required field 'params' missing or not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) paramsRaw;

        // Template-specific param validation.
        switch (tplId) {
            case "research-aggregate-write" ->
                    validateResearchAggregateWriteParams(params);
            case "doc-driven-chapters" ->
                    validateDocDrivenChaptersParams(params);
            case "decide-with-user-input" ->
                    validateDecideWithUserInputParams(params);
            default ->
                    throw new IllegalArgumentException(
                            "no validator wired for templateId '" + tplId + "'");
        }

        String templateSource = loadTemplate(tplId);
        Map<String, Object> renderCtx = new LinkedHashMap<>();
        renderCtx.put("params", params);
        try {
            io.pebbletemplates.pebble.template.PebbleTemplate compiled =
                    templateEngine.getTemplate(templateSource);
            java.io.StringWriter out =
                    new java.io.StringWriter(templateSource.length() + 256);
            compiled.evaluate(out, renderCtx);
            String yaml = out.toString();
            if (yaml.isBlank()) {
                throw new IllegalStateException(
                        "template '" + tplId + "' rendered to empty output");
            }
            return yaml;
        } catch (io.pebbletemplates.pebble.error.PebbleException
                | java.io.IOException e) {
            throw new IllegalStateException(
                    "template '" + tplId + "' render failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validates the params block for {@code research-aggregate-write}.
     * Throws with explicit guidance for any missing / wrong-typed
     * field so PROPOSING can re-prompt with a useful hint.
     */
    @SuppressWarnings("unchecked")
    private static void validateResearchAggregateWriteParams(
            Map<String, Object> params) {
        requireNonBlankString(params, "name");
        requireNonBlankString(params, "description");
        requireNonBlankString(params, "gathererRecipe");
        requireNonBlankString(params, "synthesisPrompt");
        requireNonBlankString(params, "language");
        requireNonBlankString(params, "outputPathTpl");

        String outputPath = ((String) params.get("outputPathTpl")).trim();
        for (String reserved : RESERVED_PATH_PREFIXES) {
            if (outputPath.startsWith(reserved)) {
                throw new IllegalArgumentException(
                        "params.outputPathTpl '" + outputPath
                                + "' starts with reserved prefix '"
                                + reserved + "'. Reserved buckets are "
                                + "owned by engines and must not be "
                                + "overwritten by recipes. Use a fresh "
                                + "folder like research/, reports/, "
                                + "documents/.");
            }
        }

        Object aspectsObj = params.get("aspects");
        if (!(aspectsObj instanceof List<?> aspectsList)
                || aspectsList.isEmpty()) {
            throw new IllegalArgumentException(
                    "params.aspects must be a non-empty list of "
                            + "{role, goal} objects");
        }
        if (aspectsList.size() > 10) {
            throw new IllegalArgumentException(
                    "params.aspects has " + aspectsList.size()
                            + " entries — keep it to 3-7 (10 is the hard cap)");
        }
        for (int i = 0; i < aspectsList.size(); i++) {
            Object a = aspectsList.get(i);
            if (!(a instanceof Map<?, ?> aMap)) {
                throw new IllegalArgumentException(
                        "params.aspects[" + i + "] is not an object — "
                                + "every aspect must be {role, goal}");
            }
            Object role = aMap.get("role");
            Object goal = aMap.get("goal");
            if (!(role instanceof String rs) || rs.isBlank()) {
                throw new IllegalArgumentException(
                        "params.aspects[" + i + "].role missing or blank");
            }
            if (!(goal instanceof String gs) || gs.isBlank()) {
                throw new IllegalArgumentException(
                        "params.aspects[" + i + "].goal missing or blank");
            }
        }

        // Optional fields — type-check only when present.
        Object maxChars = params.get("maxOutputChars");
        if (maxChars != null && !(maxChars instanceof Number)) {
            throw new IllegalArgumentException(
                    "params.maxOutputChars must be an integer when set");
        }
    }

    /**
     * Validates the params block for {@code doc-driven-chapters}.
     * Reusable for any "1 outline → N chapters via EXPAND_FROM_DOC
     * → optional final consolidation" pipeline.
     */
    private static void validateDocDrivenChaptersParams(
            Map<String, Object> params) {
        requireNonBlankString(params, "name");
        requireNonBlankString(params, "description");
        requireNonBlankString(params, "outlinePrompt");
        requireNonBlankString(params, "outlinePath");
        requireNonBlankString(params, "chaptersDir");
        requireNonBlankString(params, "chapterPromptTpl");
        requireNonBlankString(params, "language");

        checkReservedPath(params, "outlinePath");
        checkReservedPath(params, "chaptersDir");

        Object consolidate = params.get("consolidate");
        boolean consolidateOn = consolidate instanceof Boolean b ? b : false;
        if (consolidateOn) {
            requireNonBlankString(params, "consolidatePrompt");
            requireNonBlankString(params, "finalPath");
            checkReservedPath(params, "finalPath");
        }

        Object maxChars = params.get("maxOutputChars");
        if (maxChars != null && !(maxChars instanceof Number)) {
            throw new IllegalArgumentException(
                    "params.maxOutputChars must be an integer when set");
        }
    }

    /**
     * Validates the params block for {@code decide-with-user-input}.
     * Reusable for any "N USER_INPUT clarifications → 1 worker
     * synthesizes the decision" pipeline.
     */
    @SuppressWarnings("unchecked")
    private static void validateDecideWithUserInputParams(
            Map<String, Object> params) {
        requireNonBlankString(params, "name");
        requireNonBlankString(params, "description");
        requireNonBlankString(params, "decisionPrompt");
        requireNonBlankString(params, "outputPathTpl");

        checkReservedPath(params, "outputPathTpl");

        Object qObj = params.get("questions");
        if (!(qObj instanceof List<?> qList) || qList.isEmpty()) {
            throw new IllegalArgumentException(
                    "params.questions must be a non-empty list of "
                            + "{title, body, type, ...} objects");
        }
        if (qList.size() > 10) {
            throw new IllegalArgumentException(
                    "params.questions has " + qList.size()
                            + " entries — keep it to 1-7 (10 is the hard cap)");
        }
        java.util.Set<String> validTypes = java.util.Set.of(
                "DECISION", "FEEDBACK", "APPROVAL");
        java.util.Set<String> validCrit = java.util.Set.of(
                "LOW", "NORMAL", "HIGH", "URGENT");
        for (int i = 0; i < qList.size(); i++) {
            Object q = qList.get(i);
            if (!(q instanceof Map<?, ?> qMap)) {
                throw new IllegalArgumentException(
                        "params.questions[" + i + "] must be an object "
                                + "with title + body");
            }
            Object title = qMap.get("title");
            Object body = qMap.get("body");
            if (!(title instanceof String ts) || ts.isBlank()) {
                throw new IllegalArgumentException(
                        "params.questions[" + i + "].title missing or blank");
            }
            if (!(body instanceof String bs) || bs.isBlank()) {
                throw new IllegalArgumentException(
                        "params.questions[" + i + "].body missing or blank");
            }
            Object type = qMap.get("type");
            if (type instanceof String tsv && !tsv.isBlank()
                    && !validTypes.contains(tsv)) {
                throw new IllegalArgumentException(
                        "params.questions[" + i + "].type '" + tsv
                                + "' invalid — pick one of " + validTypes);
            }
            Object crit = qMap.get("criticality");
            if (crit instanceof String cs && !cs.isBlank()
                    && !validCrit.contains(cs)) {
                throw new IllegalArgumentException(
                        "params.questions[" + i + "].criticality '" + cs
                                + "' invalid — pick one of " + validCrit);
            }
        }
    }

    private static void checkReservedPath(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (!(v instanceof String s) || s.isBlank()) return;
        String path = s.trim();
        for (String reserved : RESERVED_PATH_PREFIXES) {
            if (path.startsWith(reserved)) {
                throw new IllegalArgumentException(
                        "params." + key + " '" + path
                                + "' starts with reserved prefix '"
                                + reserved + "'. Reserved buckets are "
                                + "owned by engines and must not be "
                                + "overwritten by recipes. Use a fresh "
                                + "folder like research/, essays/, "
                                + "decisions/, reports/, documents/.");
            }
        }
    }

    private static void requireNonBlankString(
            Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "params." + key + " missing or blank");
        }
    }

    /**
     * Reads a template file from the classpath. Cached in memory
     * because templates are immutable per-deployment and
     * {@code extractRecipeYaml} runs in the LLM hot path.
     */
    private String loadTemplate(String templateId) {
        return templateSourceCache.computeIfAbsent(
                templateId, this::loadTemplateImpl);
    }

    private String loadTemplateImpl(String templateId) {
        String path = TEMPLATE_PREFIX + templateId + ".yaml.tpl";
        ClassPathResource res = new ClassPathResource(path);
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Marvin template '" + templateId
                            + "' could not be loaded from '" + path
                            + "': " + e.getMessage(), e);
        }
    }
}
