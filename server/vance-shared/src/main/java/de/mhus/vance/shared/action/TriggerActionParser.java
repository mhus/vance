package de.mhus.vance.shared.action;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Parses the YAML map of a trigger document (scheduler / event /
 * workflow-task) into a {@link TriggerAction}. The disjunction over
 * {@code recipe} / {@code script} / {@code workflow} is enforced here,
 * once, for every trigger surface.
 *
 * <p>Errors do not throw incrementally — the parser collects every
 * issue and throws a single {@link ActionParseException} with the full
 * list. That way an editor can surface all problems at once.
 *
 * <p>This class is stateless; one instance is enough for the whole
 * brain. Made a Spring component for convenience (zero deps).
 */
public final class TriggerActionParser {

    /**
     * Parse a YAML map. The map's keys are typically the top-level
     * keys of a YAML document (e.g. {@code recipe}, {@code script},
     * {@code workflow}, {@code params}, {@code runAs}). Unknown keys
     * are not flagged — trigger-specific keys ({@code cron},
     * {@code at}, {@code overlap}, …) live alongside the action
     * fields, the loader strips them before calling here.
     *
     * @throws ActionParseException with the full error list if any
     *         validation fails
     */
    public TriggerAction parse(Map<String, Object> yaml) {
        List<ActionValidationError> errors = new ArrayList<>();
        TriggerAction action = parseInternal(yaml, errors);
        if (!errors.isEmpty()) {
            throw new ActionParseException(errors);
        }
        // parseInternal guarantees non-null when errors is empty.
        return action;
    }

    /**
     * Validate a constructed {@link TriggerAction} defensively
     * (post-parse / pre-execute). Returns the list of errors; empty
     * list means valid. Mostly used by trigger handlers that re-check
     * before firing, in case the action was deserialized from a stale
     * source.
     */
    public List<ActionValidationError> validate(TriggerAction action) {
        if (action == null) {
            return List.of(new ActionValidationError(
                    ActionValidationError.Kind.NONE_SET, "", "action is null"));
        }
        // Records enforce invariants in their canonical constructors;
        // a constructed instance is by definition valid. Keep the
        // method as a forward-compat hook for future cross-field rules.
        return List.of();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private @Nullable TriggerAction parseInternal(Map<String, Object> yaml,
                                                  List<ActionValidationError> errors) {
        if (yaml == null) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.NONE_SET, "", "yaml map is null"));
            return null;
        }
        boolean hasRecipe = yaml.get("recipe") != null;
        boolean hasScript = yaml.get("script") != null;
        boolean hasWorkflow = yaml.get("workflow") != null;
        int count = (hasRecipe ? 1 : 0) + (hasScript ? 1 : 0) + (hasWorkflow ? 1 : 0);
        if (count == 0) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.NONE_SET, "",
                    "exactly one of 'recipe', 'script', 'workflow' must be set"));
            return null;
        }
        if (count > 1) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MULTIPLE_SET, "",
                    "only one of 'recipe', 'script', 'workflow' may be set"));
            return null;
        }
        String runAs = readString(yaml, "runAs", errors);
        Map<String, Object> params = readMap(yaml, "params", errors);
        if (hasRecipe) {
            return parseRecipe(yaml, runAs, params, errors);
        }
        if (hasScript) {
            return parseScript(yaml, runAs, params, errors);
        }
        return parseWorkflow(yaml, runAs, params, errors);
    }

    private @Nullable TriggerAction parseRecipe(Map<String, Object> yaml,
                                                @Nullable String runAs,
                                                @Nullable Map<String, Object> params,
                                                List<ActionValidationError> errors) {
        String recipe = readString(yaml, "recipe", errors);
        if (StringUtils.isBlank(recipe)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MISSING_FIELD, "recipe",
                    "recipe name must be non-blank"));
            return null;
        }
        String initialMessage = readString(yaml, "initialMessage", errors);
        return TriggerAction.Recipe.of(recipe, initialMessage, params, runAs);
    }

    private @Nullable TriggerAction parseScript(Map<String, Object> yaml,
                                                @Nullable String runAs,
                                                @Nullable Map<String, Object> params,
                                                List<ActionValidationError> errors) {
        Object raw = yaml.get("script");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_TYPE, "script",
                    "expected a map with 'source', 'path' [, 'dirName', 'timeoutSeconds', 'params']"));
            return null;
        }
        Map<String, Object> scriptMap = coerceStringKeys(rawMap, "script", errors);
        if (scriptMap == null) {
            return null;
        }
        ScriptSource source = readScriptSource(scriptMap, errors);
        String path = readString(scriptMap, "path", errors);
        if (StringUtils.isBlank(path)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MISSING_FIELD, "script.path",
                    "script.path must be non-blank"));
        }
        String dirName = readString(scriptMap, "dirName", errors);
        Integer timeoutSeconds = readPositiveInt(scriptMap, "timeoutSeconds", errors);
        Map<String, Object> scriptParams = readMap(scriptMap, "params", errors);
        if (scriptParams != null && params != null) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_VALUE, "params",
                    "params may be declared at the top level OR under 'script', not both"));
            return null;
        }
        Map<String, Object> effectiveParams = scriptParams != null ? scriptParams : params;
        if (source == null) {
            return null;
        }
        if (source == ScriptSource.WORKSPACE && StringUtils.isBlank(dirName)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MISSING_FIELD, "script.dirName",
                    "script.dirName is required when source=workspace"));
            return null;
        }
        if (source == ScriptSource.DOCUMENT && StringUtils.isNotBlank(dirName)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_VALUE, "script.dirName",
                    "script.dirName must be omitted when source=document"));
            return null;
        }
        if (StringUtils.isBlank(path)) {
            return null;
        }
        try {
            return new TriggerAction.Script(source,
                    StringUtils.isBlank(dirName) ? null : dirName,
                    path,
                    timeoutSeconds,
                    effectiveParams,
                    runAs);
        } catch (IllegalArgumentException e) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_VALUE, "script", e.getMessage()));
            return null;
        }
    }

    private @Nullable TriggerAction parseWorkflow(Map<String, Object> yaml,
                                                  @Nullable String runAs,
                                                  @Nullable Map<String, Object> params,
                                                  List<ActionValidationError> errors) {
        String workflow = readString(yaml, "workflow", errors);
        if (StringUtils.isBlank(workflow)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MISSING_FIELD, "workflow",
                    "workflow name must be non-blank"));
            return null;
        }
        return new TriggerAction.Workflow(workflow, params, runAs);
    }

    private @Nullable ScriptSource readScriptSource(Map<String, Object> scriptMap,
                                                    List<ActionValidationError> errors) {
        Object raw = scriptMap.get("source");
        if (raw == null) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.MISSING_FIELD, "script.source",
                    "script.source is required (document | workspace)"));
            return null;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_TYPE, "script.source",
                    "script.source must be a non-blank string"));
            return null;
        }
        try {
            return ScriptSource.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_VALUE, "script.source",
                    "unknown source '" + s + "' (expected: document | workspace)"));
            return null;
        }
    }

    private @Nullable String readString(Map<String, Object> yaml, String key,
                                        List<ActionValidationError> errors) {
        Object raw = yaml.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            return s;
        }
        errors.add(new ActionValidationError(
                ActionValidationError.Kind.BAD_TYPE, key,
                "expected a string, got " + raw.getClass().getSimpleName()));
        return null;
    }

    private @Nullable Integer readPositiveInt(Map<String, Object> yaml, String key,
                                              List<ActionValidationError> errors) {
        Object raw = yaml.get(key);
        if (raw == null) {
            return null;
        }
        int value;
        if (raw instanceof Number n) {
            value = n.intValue();
        } else if (raw instanceof String s) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                errors.add(new ActionValidationError(
                        ActionValidationError.Kind.BAD_TYPE, key,
                        "expected an integer, got '" + s + "'"));
                return null;
            }
        } else {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_TYPE, key,
                    "expected an integer, got " + raw.getClass().getSimpleName()));
            return null;
        }
        if (value <= 0) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_VALUE, key,
                    key + " must be > 0, got " + value));
            return null;
        }
        return value;
    }

    private @Nullable Map<String, Object> readMap(Map<String, Object> yaml, String key,
                                                  List<ActionValidationError> errors) {
        Object raw = yaml.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            errors.add(new ActionValidationError(
                    ActionValidationError.Kind.BAD_TYPE, key,
                    "expected a map, got " + raw.getClass().getSimpleName()));
            return null;
        }
        return coerceStringKeys(rawMap, key, errors);
    }

    private @Nullable Map<String, Object> coerceStringKeys(Map<?, ?> raw, String field,
                                                           List<ActionValidationError> errors) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                errors.add(new ActionValidationError(
                        ActionValidationError.Kind.BAD_TYPE, field,
                        "non-string key '" + e.getKey() + "'"));
                return null;
            }
            out.put(key, e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }
}
