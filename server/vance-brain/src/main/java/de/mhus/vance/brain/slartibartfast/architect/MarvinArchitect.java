package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
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
 * Marvin v2 recipe architect. Produces recipes with
 * {@code engine: marvin}, a non-blank top-level
 * {@code promptPrefix} (additional goal context for the root
 * worker), and a {@code params.availableRecipes} whitelist for
 * CALL_RECIPE.
 *
 * <p>Marvin v2 nodes are autonomous workers — the recipe does NOT
 * encode KIND-block skeletons or pre-decomposed plans. The
 * worker's SCOPE/REFLECT/CONCLUDE phases decide the actual tree
 * shape at runtime. Templates here therefore produce recipes
 * with simple narrative promptPrefix, not procedural plans.
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
            "marvin-recipe-available-recipes-exist";
    public static final String RULE_MARVIN_POSTACTION_VARS =
            "marvin-recipe-postaction-variables-valid";

    /** Classpath location of the bundled Marvin recipe templates. */
    private static final String TEMPLATE_PREFIX =
            "vance-defaults/_vance/manuals/slartibartfast/marvin-architect/templates/";

    /** Whitelist of supported template IDs. */
    private static final Set<String> SUPPORTED_TEMPLATE_IDS = Set.of(
            "research-aggregate-write",
            "doc-driven-chapters",
            "decide-with-user-input");

    /** Path segments that postAction args.path must NEVER target. */
    private static final List<String> RESERVED_PATH_PREFIXES = List.of(
            "recipes/", "_user/", "_vance/", "_slart/", "_tenant/",
            "_zaphod-drafts/", "_vogon-drafts/", "_marvin-drafts/");

    private final Map<String, String> templateSourceCache = new ConcurrentHashMap<>();

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
            You build a Marvin v2 recipe from a framed goal,
            subgoals and available sub-recipes.

            Marvin v2 nodes are autonomous workers running a
            5-phase state-machine (SCOPE → REFLECT → POST_CHILDREN
            → CONCLUDE → VALIDATE). They decide for themselves
            when to call recipes, decompose, or conclude. Your
            recipe therefore does NOT prescribe a fixed tree — it
            supplies (a) a narrative promptPrefix that tells the
            root worker WHAT to achieve and (b) a whitelist of
            specialist recipes the worker may invoke via
            CALL_RECIPE.

            ## Output schema

            Emit a SINGLE JSON object with FOUR top-level fields:
              {
                "templateId":     "<one of: research-aggregate-write,
                                    doc-driven-chapters,
                                    decide-with-user-input>",
                "params":         { ...template-specific... },
                "justifications": { "<constraint-key>": "<sg-id>", ... },
                "shapeRationale": "<1-2 sentences explaining why this template fits>"
              }

            **All four fields are required.** `justifications` maps
            constraint keys (e.g. "templateId", "params.aspects",
            "params.outputPathTpl") to the matching `sg-id` from the
            subgoals list. `shapeRationale` is a 1-2 sentence
            explanation of why this template + params fit the
            framed goal. Both are validated by the PROPOSING parser
            — a missing or malformed one triggers a re-prompt.

            Pick the templateId that best fits the goal:
              - research-aggregate-write — gather information from
                multiple aspects, then synthesise & write to a file.
              - doc-driven-chapters — write a structured document
                (outline → chapter files → optional consolidation).
              - decide-with-user-input — ask the user one or more
                questions, then decide & write the decision.

            ## Common params

            All templates require these params:
              - name (kebab-case identifier, unique)
              - description (one-paragraph human description)
              - language ("de" or "en" — matches the user's setting)
              - availableRecipes (list of recipe names the worker
                may call via CALL_RECIPE; pick ONLY from the
                "Available sub-recipes" list shown below in the
                user message — never invent names)

            ## Template-specific params

            research-aggregate-write:
              - aspects: list of {role, goal}, 2-7 entries
              - synthesisPrompt (string)
              - outputPathTpl (string, e.g.
                  "research/{{ process.goal | slug }}/report.md")
              - reportLengthWords (optional, e.g. "1500-2000")
              - outputTitleTpl (optional)

            doc-driven-chapters:
              - outlinePrompt (string)
              - outlinePath (string, e.g.
                  "essays/{{ process.goal | slug }}/outline.md")
              - chaptersDir (string)
              - chapterPromptTpl (string)
              - consolidate (boolean)
              - consolidatePrompt + finalPath (required if consolidate=true)

            decide-with-user-input:
              - questions: list of {title, body, type, options}
                where type ∈ {DECISION, FEEDBACK, APPROVAL}
              - decisionPrompt (string)
              - outputPathTpl (string)

            ## postActions paths

            outputPathTpl, outlinePath, chaptersDir, finalPath
            must NOT start with any of these reserved prefixes:
              recipes/, _user/, _vance/, _slart/, _tenant/,
              _zaphod-drafts/, _vogon-drafts/, _marvin-drafts/.
            Use fresh folders like research/, essays/, decisions/,
            reports/, documents/.

            ## Pebble template variables

            The path strings render with {{ process.goal }} and
            {{ process.goal | slug }} (URL-safe slug). Use those
            verbatim in your params; the template engine inlines
            them at recipe-eval time.
            """;

    private final RecipeLoader recipeLoader;
    private final SystemPromptComposer composer;

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
                    .append("  - leave params.availableRecipes empty\n")
                    .append("  - the worker will need to answer directly "
                            + "via PROCEED_TO_CONCLUDE without CALL_RECIPE.\n");
        } else {
            for (ResolvedRecipe r : availableRecipes) {
                sb.append("  - ").append(r.name())
                        .append(" [engine=").append(r.engine())
                        .append("]: ")
                        .append(abbrev(r.description(), 100))
                        .append("\n");
            }
            sb.append("\nWhen you set params.availableRecipes, use ONLY "
                    + "names from this list. Recipes whose engine is "
                    + "'marvin' are NOT allowed here — CALL_RECIPE on a "
                    + "Marvin recipe is blocked in v1.\n");
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
                            + "top-level 'promptPrefix' (narrative goal "
                            + "context for the root worker)")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_PROMPT_PREFIX).passed(true)
                .message("promptPrefix present (" + ppStr.length()
                        + " chars)").build());

        try {
            composer.compile(ppStr);
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
                    .message("MARVIN_RECIPE must declare a 'params' map")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_RECIPE_SHAPE).passed(true)
                .message("params block present").build());

        // Validate Pebble template variables embedded in the prompt
        // (postActions blocks reference {{ node.result }} etc.).
        ValidationCheck postActionVarCheck = checkPostActionVariables(ppStr);
        report.add(postActionVarCheck);
        if (!postActionVarCheck.isPassed()) return postActionVarCheck;

        @SuppressWarnings("unchecked")
        ValidationCheck recipeCheck = checkAvailableRecipes(
                (Map<String, Object>) params, process);
        report.add(recipeCheck);
        return recipeCheck.isPassed() ? null : recipeCheck;
    }

    private static ValidationCheck checkPostActionVariables(String promptPrefix) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)"
                        + "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"
                        + "(?:\\s*\\|\\s*[A-Za-z_][A-Za-z0-9_]*)?"
                        + "\\s*\\}\\}");
        java.util.regex.Matcher m = p.matcher(promptPrefix);
        Set<String> allowedRoots = Set.of(
                "node", "result", "process", "item",
                "tier", "model", "provider", "mode", "profile",
                "recipe", "engine", "lang", "params");
        Set<String> bad = new LinkedHashSet<>();
        Map<String, String> badFullRef = new LinkedHashMap<>();
        java.util.regex.Pattern fullRefP = java.util.regex.Pattern.compile(
                "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)");
        while (m.find()) {
            String root = m.group(1);
            if (!allowedRoots.contains(root)) {
                bad.add(root);
                String matched = m.group();
                java.util.regex.Matcher refM = fullRefP.matcher(matched);
                if (refM.find()) badFullRef.putIfAbsent(root, refM.group(1));
            }
        }
        if (bad.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_POSTACTION_VARS).passed(true)
                    .message("template variables look ok")
                    .build();
        }
        StringBuilder msg = new StringBuilder(
                "promptPrefix references unknown template root(s): ");
        boolean first = true;
        for (String r : bad) {
            if (!first) msg.append(", ");
            first = false;
            msg.append("'").append(badFullRef.getOrDefault(r, r)).append("'");
        }
        msg.append(". Allowed roots: node.*, process.*, result.* (alias), "
                + "item.* (EXPAND_FROM_DOC). Common mistakes: "
                + "process.recipe.<x>, aggregate.<x>, worker.<x> "
                + "(do not exist).");
        return ValidationCheck.builder()
                .rule(RULE_MARVIN_POSTACTION_VARS).passed(false)
                .message(msg.toString())
                .build();
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        List<String> available = listAvailableRecipeNames(process);
        StringBuilder sb = new StringBuilder();
        sb.append("\nValid recipe names for params.availableRecipes "
                + "(use ONLY these, never invent): ");
        if (available.isEmpty()) {
            sb.append("(none — leave availableRecipes empty).\n");
        } else {
            sb.append(String.join(", ", available)).append(".\n");
        }
        sb.append("\nEmit a corrected templateId + params JSON.");
        return sb.toString();
    }

    private ValidationCheck checkAvailableRecipes(
            Map<String, Object> params, ThinkProcessDocument process) {
        List<String> declared = readStringList(params.get("availableRecipes"));
        if (declared.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPES_EXIST).passed(true)
                    .message("availableRecipes is empty — no names to validate")
                    .build();
        }

        Set<String> available = new LinkedHashSet<>();
        Map<String, String> engineOfRecipe = new LinkedHashMap<>();
        try {
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")) {
                    available.add(r.name());
                    engineOfRecipe.put(r.name(), r.engine());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' VALIDATING failed listing recipes: {}",
                    process.getId(), e.toString());
        }

        List<String> unknown = new ArrayList<>();
        List<String> marvinTargets = new ArrayList<>();
        for (String name : declared) {
            if (!available.contains(name)) {
                unknown.add(name);
                continue;
            }
            String engine = engineOfRecipe.get(name);
            if ("marvin".equalsIgnoreCase(engine)) {
                marvinTargets.add(name);
            }
        }

        Set<String> seen = new HashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        for (String name : declared) {
            if (!seen.add(name)) dupes.add(name);
        }

        if (!unknown.isEmpty() || !dupes.isEmpty() || !marvinTargets.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            if (!unknown.isEmpty()) {
                msg.append("recipe(s) not found in project: ")
                        .append(String.join(", ", unknown)).append(". ");
            }
            if (!dupes.isEmpty()) {
                msg.append("duplicate name(s) in availableRecipes: ")
                        .append(String.join(", ", dupes)).append(". ");
            }
            if (!marvinTargets.isEmpty()) {
                msg.append("recipe(s) use engine=marvin (not allowed as "
                                + "CALL_RECIPE target in v1): ")
                        .append(String.join(", ", marvinTargets)).append(". ");
            }
            if (available.isEmpty()) {
                msg.append("Project has no available recipes — set "
                        + "availableRecipes to []. ");
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
                .message("all " + declared.size()
                        + " recipe name(s) resolve to non-marvin project recipes")
                .build();
    }

    private List<String> listAvailableRecipeNames(ThinkProcessDocument process) {
        try {
            List<String> names = new ArrayList<>();
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")
                        && !"marvin".equalsIgnoreCase(r.engine())) {
                    names.add(r.name());
                }
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
     * Marvin's PROPOSING LLM emits {@code {templateId, params}} —
     * the recipe name lives inside {@code params}, not at the root.
     */
    @Override
    public String extractRecipeName(Map<String, Object> jsonRoot) {
        Object paramsObj = jsonRoot.get("params");
        if (!(paramsObj instanceof Map<?, ?> paramsRaw)) {
            throw new IllegalArgumentException(
                    "required field 'params' missing or not an object");
        }
        Object n = paramsRaw.get("name");
        if (!(n instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException(
                    "required field 'params.name' missing or blank");
        }
        return name;
    }

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

        // Common params required by all templates.
        requireNonBlankString(params, "name");
        requireNonBlankString(params, "description");
        requireNonBlankString(params, "language");
        validateAvailableRecipesShape(params);

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

    private static void validateAvailableRecipesShape(Map<String, Object> params) {
        Object ar = params.get("availableRecipes");
        if (ar == null) {
            // Default to empty list — the worker can still answer
            // directly via PROCEED_TO_CONCLUDE.
            params.put("availableRecipes", new ArrayList<String>());
            return;
        }
        if (!(ar instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "params.availableRecipes must be a list of recipe names");
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(
                        "params.availableRecipes[" + i + "] must be a non-blank string");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateResearchAggregateWriteParams(
            Map<String, Object> params) {
        requireNonBlankString(params, "synthesisPrompt");
        requireNonBlankString(params, "outputPathTpl");
        checkReservedPath(params, "outputPathTpl");

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
                            + " entries — keep it to 2-7 (10 is the hard cap)");
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
    }

    private static void validateDocDrivenChaptersParams(
            Map<String, Object> params) {
        requireNonBlankString(params, "outlinePrompt");
        requireNonBlankString(params, "outlinePath");
        requireNonBlankString(params, "chaptersDir");
        requireNonBlankString(params, "chapterPromptTpl");
        checkReservedPath(params, "outlinePath");
        checkReservedPath(params, "chaptersDir");

        Object consolidate = params.get("consolidate");
        boolean consolidateOn = consolidate instanceof Boolean b ? b : false;
        if (consolidateOn) {
            requireNonBlankString(params, "consolidatePrompt");
            requireNonBlankString(params, "finalPath");
            checkReservedPath(params, "finalPath");
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateDecideWithUserInputParams(
            Map<String, Object> params) {
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
        Set<String> validTypes = Set.of("DECISION", "FEEDBACK", "APPROVAL");
        Set<String> validCrit = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
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
                                + reserved + "'. Use research/, essays/, "
                                + "decisions/, reports/, documents/ instead.");
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
