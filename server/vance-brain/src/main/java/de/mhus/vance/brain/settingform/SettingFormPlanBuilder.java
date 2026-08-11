package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Translates a {@link ResolvedSettingForm} plus user-submitted values
 * into a flat list of {@link PlannedSettingAction}s — the apply plan.
 *
 * <p>Pipeline (mirrors spec §6.1):
 * <ol>
 *   <li>Evaluate {@code showIf} for every field. Fields with falsy
 *       {@code showIf} are kept in the Pebble context but their
 *       {@code bindsTo} is not contributed to the plan.</li>
 *   <li>For each visible direct-mapped field: emit WRITE / DELETE /
 *       SKIP according to {@code writeIf} + value-presence rules. An
 *       empty submission is resolved against the live cascade layer —
 *       see {@link #planForEmptySubmission}. Empty {@code password}
 *       fields skip silently (spec §6.4).</li>
 *   <li>For each computed setting: render the Pebble {@code value}
 *       template, evaluate {@code writeIf}, emit WRITE or DELETE.</li>
 *   <li>Sanity-check that no (key, scope) tuple is targeted twice —
 *       the loader already does this at parse time, but a second
 *       safety net here protects against programmatic misuse.</li>
 * </ol>
 *
 * <p>Both directly-mapped values and computed-template outputs are
 * coerced to their persisted {@link SettingType} just before going
 * into the plan, so the executor (the service) does not need to know
 * about field types at all — it just calls
 * {@code SettingService.set(...)} per WRITE entry.
 *
 * <p>The plan is also what the {@code /reset} endpoint walks: every
 * direct-mapped key + every computed key gets a DELETE action, with
 * value/template rendering skipped. See {@link #buildResetPlan}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingFormPlanBuilder {

    private final PromptTemplateRenderer templateRenderer;
    private final SettingService settingService;

    /** Pebble-context variable name carrying the live cascade values. */
    public static final String CURRENT_VAR = "current";

    /**
     * Builds the apply plan for {@code form} given the submitted values
     * and the caller's tenant/project/user context. Validation of
     * required-presence / type / select-whitelist happens upstream in
     * {@code FormValidator}; this method assumes structurally-valid
     * input.
     *
     * @param liveStates per-field pre-submit cascade state, keyed by field
     *        name, determined by {@code SettingFormService} (which owns
     *        the cascade reads). Fields missing from the map are treated
     *        as {@link FieldLiveState#ABSENT}. Two things ride on it:
     *        <ul>
     *          <li>{@link FieldLiveState#unchanged()} reduces the binding
     *              to SKIP — writing the same value again would be a
     *              no-op at best and, when the value is inherited from an
     *              outer scope, would silently pin it into the scope being
     *              edited. Such fields stay in the Pebble context so
     *              computed settings still see a complete value map.</li>
     *          <li>{@link FieldLiveState#liveReferenceId()} disambiguates
     *              an empty submission — see
     *              {@link #planForEmptySubmission}.</li>
     *        </ul>
     */
    public List<PlannedSettingAction> buildApplyPlan(
            ResolvedSettingForm form,
            Map<String, Object> values,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String lang,
            Map<String, FieldLiveState> liveStates) {
        Map<String, Object> ctx = buildPebbleContext(form, values, projectId, userId, lang, tenantId);
        Map<String, Boolean> shownFields = evaluateShowIf(form.fields(), ctx);

        Map<TupleKey, PlannedSettingAction> seen = new LinkedHashMap<>();
        List<PlannedSettingAction> out = new ArrayList<>();

        // 1) Direct-mapped fields.
        for (FormFieldDto field : form.fields()) {
            BindsToDto binding = field.getBindsTo();
            if (binding == null) continue;
            // showIf=false → field's binding is treated as absent.
            if (!shownFields.getOrDefault(field.getName(), Boolean.TRUE)) {
                continue;
            }
            PlannedSettingAction action = planForField(
                    form, field, binding, values, ctx, projectId, userId, tenantId,
                    liveStates.getOrDefault(field.getName(), FieldLiveState.ABSENT));
            if (action == null) continue;
            recordPlanned(seen, out, action);
        }

        // 2) Computed settings.
        for (int i = 0; i < form.computedSettings().size(); i++) {
            ResolvedComputedSetting cs = form.computedSettings().get(i);
            PlannedSettingAction action = planForComputed(
                    form, cs, i, ctx, projectId, userId, tenantId);
            recordPlanned(seen, out, action);
        }

        return out;
    }

    /**
     * Returns the DELETE-only plan that the reset endpoint executes —
     * one DELETE per direct-mapped key plus one per computed key, on
     * the form's respective scopes. No template rendering, no
     * {@code showIf}/{@code writeIf} evaluation.
     */
    public List<PlannedSettingAction> buildResetPlan(
            ResolvedSettingForm form,
            @Nullable String projectId,
            @Nullable String userId) {
        Map<TupleKey, PlannedSettingAction> seen = new LinkedHashMap<>();
        List<PlannedSettingAction> out = new ArrayList<>();

        for (FormFieldDto field : form.fields()) {
            BindsToDto binding = field.getBindsTo();
            if (binding == null) continue;
            String scope = binding.getScope() == null ? form.defaultScope() : binding.getScope();
            ResolvedScope resolved = resolveScope(scope, projectId, userId);
            PlannedSettingAction action = new PlannedSettingAction(
                    binding.getKey(),
                    scope,
                    resolved.referenceType(),
                    resolved.referenceId(),
                    PlannedSettingAction.Action.DELETE,
                    null,
                    null,
                    "password".equals(field.getType()),
                    "fields[" + field.getName() + "]");
            recordPlanned(seen, out, action);
        }
        for (int i = 0; i < form.computedSettings().size(); i++) {
            ResolvedComputedSetting cs = form.computedSettings().get(i);
            String scope = cs.scope() == null ? form.defaultScope() : cs.scope();
            ResolvedScope resolved = resolveScope(scope, projectId, userId);
            PlannedSettingAction action = new PlannedSettingAction(
                    cs.key(),
                    scope,
                    resolved.referenceType(),
                    resolved.referenceId(),
                    PlannedSettingAction.Action.DELETE,
                    null,
                    null,
                    cs.settingType().encrypted(),
                    "settings[" + i + "]");
            recordPlanned(seen, out, action);
        }
        return out;
    }

    // ──────────────────── Field path ────────────────────

    private @Nullable PlannedSettingAction planForField(
            ResolvedSettingForm form,
            FormFieldDto field,
            BindsToDto binding,
            Map<String, Object> values,
            Map<String, Object> ctx,
            @Nullable String projectId,
            @Nullable String userId,
            String tenantId,
            FieldLiveState live) {
        String wireScope = binding.getScope() == null ? form.defaultScope() : binding.getScope();
        ResolvedScope scope = resolveScope(wireScope, projectId, userId);
        SettingType settingType = resolveFieldSettingType(field, binding);
        String sourceLabel = "fields[" + field.getName() + "]";
        boolean masked = "password".equals(field.getType()) || settingType.encrypted();

        boolean writeAllowed = evaluateBooleanExpression(
                field.getWriteIf(), ctx, "fields[" + field.getName() + "].writeIf");

        if (!writeAllowed) {
            // Conditional-Reset outranks the unchanged-check below: a falsy
            // writeIf is an explicit "remove this key", not a no-op.
            return new PlannedSettingAction(
                    binding.getKey(), wireScope,
                    scope.referenceType(), scope.referenceId(),
                    PlannedSettingAction.Action.DELETE,
                    null, null, masked, sourceLabel);
        }

        // Value equals what the cascade already yields → nothing to write.
        // Same effective outcome, minus pinning an inherited value into the
        // scope being edited.
        if (live.unchanged()) {
            return new PlannedSettingAction(
                    binding.getKey(), wireScope,
                    scope.referenceType(), scope.referenceId(),
                    PlannedSettingAction.Action.SKIP,
                    null, null, masked, sourceLabel);
        }

        @Nullable Object submitted = values.get(field.getName());
        @Nullable String raw = coerceScalar(submitted, field, settingType);

        if (raw == null) {
            return planForEmptySubmission(
                    binding, wireScope, scope, settingType, masked, sourceLabel,
                    live, submitted != null);
        }

        return new PlannedSettingAction(
                binding.getKey(), wireScope,
                scope.referenceType(), scope.referenceId(),
                PlannedSettingAction.Action.WRITE,
                raw, settingType, masked, sourceLabel);
    }

    /**
     * Turns an <em>empty</em> submission into an action. On its own an
     * empty field is ambiguous — "leave this alone" and "make this empty
     * here" look identical on the wire — so the live cascade layer
     * decides:
     *
     * <ul>
     *   <li><b>Value owned by the scope being edited</b> → DELETE. The
     *       user is dropping their own override; the key goes away and
     *       the outer layer is inherited again.</li>
     *   <li><b>Value inherited from an outer scope</b> → WRITE {@code ""}.
     *       The user wants no value <em>here</em> despite the outer layer.
     *       An existing-but-empty setting is exactly how the cascade
     *       expresses that: {@code SettingService.getStringValueCascade}
     *       stops at any layer that has the key set (only a {@code null}
     *       value keeps cascading), and every consumer reads blank as "not
     *       configured" and falls back to its own default. Without this,
     *       emptying an inherited field would silently do nothing — the
     *       outer value would just stay in effect.</li>
     *   <li><b>Nothing set anywhere</b> → SKIP. There is no override to
     *       drop and no inherited value to shadow, so writing an empty row
     *       would only pin noise into the scope.</li>
     * </ul>
     *
     * <p>Three deliberate exceptions keep SKIP:
     * <ul>
     *   <li>Fields the caller did not submit at all ({@code submitted =
     *       false}: key absent from the values map, or present as JSON
     *       null). "Not sent" is not "cleared" — the Web-UI happens to
     *       pre-fill and resubmit every field, but a partial submit is a
     *       legitimate API call and must not delete or blank anything the
     *       caller never mentioned.</li>
     *   <li>PASSWORD fields — an empty password field means "keep the
     *       stored secret" (spec §6.4), never "clear it". Clearing a
     *       credential goes through {@code /reset} or the raw settings
     *       editor.</li>
     *   <li>Non-STRING types (INT / LONG / DOUBLE / BOOLEAN) with an
     *       inherited value — {@code ""} is not a parseable value of those
     *       types, and while the typed getters treat blank as absent, a
     *       consumer parsing the raw cascade string would blow up. The
     *       DELETE branch above still applies to them, which covers the
     *       case that actually occurs (dropping one's own override).</li>
     * </ul>
     */
    private PlannedSettingAction planForEmptySubmission(
            BindsToDto binding,
            String wireScope,
            ResolvedScope scope,
            SettingType settingType,
            boolean masked,
            String sourceLabel,
            FieldLiveState live,
            boolean submitted) {
        PlannedSettingAction.Action action;
        if (!submitted || masked || settingType.encrypted() || !live.hasLiveValue()) {
            action = PlannedSettingAction.Action.SKIP;
        } else if (live.isOwnedBy(scope.referenceId())) {
            action = PlannedSettingAction.Action.DELETE;
        } else if (settingType == SettingType.STRING) {
            action = PlannedSettingAction.Action.WRITE;
        } else {
            action = PlannedSettingAction.Action.SKIP;
        }
        boolean write = action == PlannedSettingAction.Action.WRITE;
        return new PlannedSettingAction(
                binding.getKey(), wireScope,
                scope.referenceType(), scope.referenceId(),
                action,
                write ? "" : null,
                write ? settingType : null,
                masked, sourceLabel);
    }

    /**
     * The {@link SettingType} a bound field writes. Package-private because
     * {@code SettingFormService} must resolve it identically when reading the
     * live value — reading a field through the wrong type silently reports
     * "not set" for an existing encrypted setting.
     */
    static SettingType resolveFieldSettingType(FormFieldDto field, BindsToDto binding) {
        if (binding.getSettingType() != null && !binding.getSettingType().isBlank()) {
            try {
                return SettingType.valueOf(binding.getSettingType().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "fields[" + field.getName() + "].bindsTo.settingType invalid: "
                                + binding.getSettingType());
            }
        }
        return switch (field.getType()) {
            case "string", "textarea", "select" -> SettingType.STRING;
            case "password" -> SettingType.PASSWORD;
            case "integer" -> SettingType.INT;
            case "boolean" -> SettingType.BOOLEAN;
            default -> throw new IllegalStateException(
                    "fields[" + field.getName() + "] is not bindable: type '"
                            + field.getType() + "'");
        };
    }

    private static @Nullable String coerceScalar(
            @Nullable Object raw, FormFieldDto field, SettingType settingType) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        return switch (settingType) {
            // Encrypted types pass through verbatim — the plaintext is coerced
            // by nobody, it is handed to the encrypted-write path as typed.
            case STRING, PASSWORD, HIDDEN -> s;
            case INT, LONG -> {
                try {
                    yield Long.toString(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "fields[" + field.getName() + "] is not an integer: " + s);
                }
            }
            case DOUBLE -> {
                try {
                    yield Double.toString(Double.parseDouble(s));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "fields[" + field.getName() + "] is not a number: " + s);
                }
            }
            case BOOLEAN -> {
                String low = s.toLowerCase(Locale.ROOT);
                yield Boolean.toString(
                        "true".equals(low) || "1".equals(low) || "yes".equals(low) || "on".equals(low));
            }
        };
    }

    // ──────────────────── Computed-settings path ────────────────────

    private PlannedSettingAction planForComputed(
            ResolvedSettingForm form,
            ResolvedComputedSetting cs,
            int index,
            Map<String, Object> ctx,
            @Nullable String projectId,
            @Nullable String userId,
            String tenantId) {
        String wireScope = cs.scope() == null ? form.defaultScope() : cs.scope();
        ResolvedScope scope = resolveScope(wireScope, projectId, userId);
        String sourceLabel = "settings[" + index + "]";
        boolean masked = cs.settingType().encrypted();

        boolean writeAllowed = evaluateBooleanExpression(
                cs.writeIfExpression(), ctx, sourceLabel + ".writeIf");
        if (!writeAllowed) {
            return new PlannedSettingAction(
                    cs.key(), wireScope,
                    scope.referenceType(), scope.referenceId(),
                    PlannedSettingAction.Action.DELETE,
                    null, null, masked, sourceLabel);
        }

        String rendered;
        try {
            rendered = templateRenderer.render(cs.valueTemplate(), ctx);
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    sourceLabel + ".value render failed: " + e.getMessage(), e);
        }
        if (rendered == null) rendered = "";
        String coerced = coerceComputed(rendered, cs.settingType(), sourceLabel);
        if (coerced == null) {
            return new PlannedSettingAction(
                    cs.key(), wireScope,
                    scope.referenceType(), scope.referenceId(),
                    PlannedSettingAction.Action.SKIP,
                    null, null, masked, sourceLabel);
        }
        return new PlannedSettingAction(
                cs.key(), wireScope,
                scope.referenceType(), scope.referenceId(),
                PlannedSettingAction.Action.WRITE,
                coerced, cs.settingType(), masked, sourceLabel);
    }

    private static @Nullable String coerceComputed(
            String rendered, SettingType settingType, String sourceLabel) {
        String trimmed = rendered.trim();
        if (trimmed.isEmpty()) return null;
        return switch (settingType) {
            case STRING, PASSWORD, HIDDEN -> trimmed;
            case INT, LONG -> {
                try {
                    yield Long.toString(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            sourceLabel + ".value did not render to an integer: " + trimmed);
                }
            }
            case DOUBLE -> {
                try {
                    yield Double.toString(Double.parseDouble(trimmed));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            sourceLabel + ".value did not render to a number: " + trimmed);
                }
            }
            case BOOLEAN -> {
                String low = trimmed.toLowerCase(Locale.ROOT);
                yield Boolean.toString(
                        "true".equals(low) || "1".equals(low) || "yes".equals(low) || "on".equals(low));
            }
        };
    }

    // ──────────────────── Scope ────────────────────

    /** Internal scope-resolution result: persistence reference. */
    public record ResolvedScope(String referenceType, String referenceId) {}

    /**
     * Translates a wire-format scope ({@code project}, {@code user},
     * {@code tenant}) into the persisted reference-type +
     * reference-id pair that {@link SettingService} expects.
     *
     * <p>A missing {@code projectId} is the tenant-wide context, not an
     * error: the whole subsystem reads "no project in the request" as
     * {@code _tenant} — the loader skips the project tier and filters
     * {@code availableIn} against {@code _tenant}, and the live-cascade
     * reads in {@code SettingFormService} land on {@code _tenant} too.
     * Writes have to agree, otherwise a {@code defaultScope: project}
     * form that is deliberately offered in the tenant context
     * ({@code llm-setup}, {@code vault}, {@code fenchurch-style}) would
     * render its values fine and then fail on save.
     *
     * @throws IllegalStateException when {@code userId} is not provided
     *         for the {@code user} scope, or the scope is unknown.
     */
    public ResolvedScope resolveScope(
            String wireScope,
            @Nullable String projectId,
            @Nullable String userId) {
        return switch (wireScope) {
            case SettingService.SCOPE_PROJECT -> new ResolvedScope(
                    SettingService.SCOPE_PROJECT,
                    (projectId == null || projectId.isBlank())
                            ? HomeBootstrapService.TENANT_PROJECT_NAME
                            : projectId);
            case SettingService.SCOPE_USER -> {
                if (userId == null || userId.isBlank()) {
                    throw new IllegalStateException(
                            "scope 'user' requires an authenticated user");
                }
                yield new ResolvedScope(SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId);
            }
            case SettingService.SCOPE_TENANT -> new ResolvedScope(
                    SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME);
            default -> throw new IllegalStateException(
                    "unsupported scope '" + wireScope + "'");
        };
    }

    // ──────────────────── showIf / writeIf ────────────────────

    private Map<String, Boolean> evaluateShowIf(
            List<FormFieldDto> fields, Map<String, Object> ctx) {
        Map<String, Boolean> out = new HashMap<>();
        for (FormFieldDto f : fields) {
            boolean shown = evaluateBooleanExpression(
                    f.getShowIf(), ctx, "fields[" + f.getName() + "].showIf");
            out.put(f.getName(), shown);
        }
        return out;
    }

    private boolean evaluateBooleanExpression(
            @Nullable String expression, Map<String, Object> ctx, String label) {
        if (expression == null || expression.isBlank()) return true;
        String probe = "{% if " + expression + " %}1{% endif %}";
        try {
            String result = templateRenderer.render(probe, ctx);
            return result != null && !result.isBlank();
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    label + " evaluation failed: " + e.getMessage(), e);
        }
    }

    // ──────────────────── Pebble context ────────────────────

    private Map<String, Object> buildPebbleContext(
            ResolvedSettingForm form,
            Map<String, Object> values,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String lang,
            String tenantId) {
        // Field values arrive as strings on the wire (multi_select/repeat as
        // collections). For Pebble, we want booleans typed as Boolean so that
        // `{% if tracing %}` works for the literal "false" string. Same for
        // integers — `{% if budget > 100 %}` would otherwise compare strings.
        Map<String, Object> ctx = new HashMap<>(values);
        for (FormFieldDto field : form.fields()) {
            Object raw = values.get(field.getName());
            if (raw == null) continue;
            Object typed = coerceForPebble(raw, field);
            if (typed != raw) ctx.put(field.getName(), typed);
        }
        ctx.putIfAbsent("lang", lang == null ? "" : lang);
        ctx.putIfAbsent("user", userId == null ? "" : userId);
        ctx.putIfAbsent("project", projectId == null ? "" : projectId);
        ctx.put(CURRENT_VAR, loadCurrentValues(form, tenantId, projectId, userId));
        return ctx;
    }

    /**
     * Re-types a raw wire value for use in the Pebble evaluation
     * context, based on the declared field type:
     * <ul>
     *   <li>{@code boolean} → {@link Boolean} (so {@code {% if x %}}
     *       evaluates the actual truth value, not "non-empty string")</li>
     *   <li>{@code integer} → {@link Long} (so comparisons are numeric)</li>
     * </ul>
     * Strings, textareas, passwords, selects keep their wire form.
     * {@code multi_select} and {@code repeat} pass through unchanged.
     */
    private static Object coerceForPebble(Object raw, FormFieldDto field) {
        if (raw == null) return null;
        String type = field.getType();
        if ("boolean".equals(type)) {
            if (raw instanceof Boolean b) return b;
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s);
        }
        if ("integer".equals(type)) {
            if (raw instanceof Number n) return n.longValue();
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * Pre-fetches the live cascade values for every key the form
     * references. Used as the {@code current} variable in Pebble
     * contexts so authors can write
     * {@code {{ current['ai.default.model'] | default('foo') }}}.
     *
     * <p>Password keys are skipped — the {@code current} map carries
     * only non-secret values. Per-key cascade follows
     * {@link SettingService#getStringValueCascade}.
     */
    private Map<String, String> loadCurrentValues(
            ResolvedSettingForm form, String tenantId,
            @Nullable String projectId, @Nullable String userId) {
        Map<String, String> out = new HashMap<>();
        for (FormFieldDto f : form.fields()) {
            BindsToDto b = f.getBindsTo();
            if (b == null) continue;
            if ("password".equals(f.getType())) continue;
            String value = settingService.getStringValueCascade(
                    tenantId, projectId, null, b.getKey());
            if (value != null) out.put(b.getKey(), value);
        }
        for (ResolvedComputedSetting cs : form.computedSettings()) {
            if (cs.settingType().encrypted()) continue;
            String value = settingService.getStringValueCascade(
                    tenantId, projectId, null, cs.key());
            if (value != null) out.putIfAbsent(cs.key(), value);
        }
        // User-scope keys live under _user_<user>; not part of the project cascade,
        // so look them up directly to keep the `current` view truthful for those.
        if (userId != null && !userId.isBlank()) {
            for (FormFieldDto f : form.fields()) {
                BindsToDto b = f.getBindsTo();
                if (b == null) continue;
                String scope = b.getScope() == null ? form.defaultScope() : b.getScope();
                if (!SettingService.SCOPE_USER.equals(scope)) continue;
                if ("password".equals(f.getType())) continue;
                String value = settingService.getUserStringValue(tenantId, userId, b.getKey());
                if (value != null) out.putIfAbsent(b.getKey(), value);
            }
        }
        return out;
    }

    // ──────────────────── Dedup ────────────────────

    private record TupleKey(String referenceType, String referenceId, String key) {}

    /**
     * Merges plan actions for the same (refType, refId, key) tuple. The
     * mutually-exclusive {@code writeIf}-pattern legitimately produces
     * two entries pointing at the same key — exactly one is meant to be
     * "WRITE-active" at apply time, the other one's {@code writeIf} fires
     * falsy and reduces to DELETE or SKIP. Effective verb is the
     * strongest of all contributors: WRITE > DELETE > SKIP. Two
     * concurrent WRITEs for the same tuple is a real definition
     * collision that the planner rejects.
     */
    private static void recordPlanned(
            Map<TupleKey, PlannedSettingAction> seen,
            List<PlannedSettingAction> out,
            PlannedSettingAction action) {
        Objects.requireNonNull(action);
        TupleKey k = new TupleKey(action.referenceType(), action.referenceId(), action.key());
        PlannedSettingAction prior = seen.get(k);
        if (prior == null) {
            seen.put(k, action);
            out.add(action);
            return;
        }
        if (prior.action() == PlannedSettingAction.Action.WRITE
                && action.action() == PlannedSettingAction.Action.WRITE) {
            throw new IllegalStateException(
                    "duplicate WRITE plan entries for key '" + action.key() + "' on "
                            + action.referenceType() + ":" + action.referenceId()
                            + " — sources: " + prior.sourceLabel()
                            + " and " + action.sourceLabel()
                            + ". Use writeIf to make the entries mutually exclusive.");
        }
        // Strongest action wins; verbs are ordered WRITE > DELETE > SKIP.
        if (strength(action.action()) > strength(prior.action())) {
            seen.put(k, action);
            int idx = out.indexOf(prior);
            out.set(idx, action);
        }
        // Else: keep prior (it is already the strongest contributor).
    }

    private static int strength(PlannedSettingAction.Action a) {
        return switch (a) {
            case WRITE -> 3;
            case DELETE -> 2;
            case SKIP -> 1;
        };
    }
}
