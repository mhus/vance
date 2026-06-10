package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Apply / validate / reset orchestration for Setting Forms. Sits
 * between {@link SettingFormController} (auth + HTTP) and the lower
 * layers ({@link SettingFormPlanBuilder} + {@link SettingService}).
 *
 * <p>Auth is the controller's job — by the time anything in here is
 * called, the user has already cleared the scope-permission check
 * for every distinct scope this form would touch (see
 * {@link #collectTargetScopes}).
 *
 * <p>Validation is in two stages: structural form-validation through
 * {@link FormValidator} (required, bounds, select-whitelist) before
 * the plan is built; planner failures (Pebble render errors,
 * scope-resolution errors, duplicate keys) surface as
 * {@link IllegalStateException} which the controller maps to HTTP
 * 400 / 500 depending on whether they came from form data or
 * definition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingFormService {

    private final SettingService settingService;
    private final FormValidator formValidator;
    private final SettingFormPlanBuilder planBuilder;
    private final ModelCatalog modelCatalog;

    /** Recognized {@link FormFieldDto#getChoicesFrom()} markers. */
    public static final String CHOICES_FROM_AI_MODELS = "ai-models";

    /** Image-only counterpart of {@link #CHOICES_FROM_AI_MODELS} —
     *  populates a select with the {@code kind: image} entries from
     *  {@code ai-models.yaml}. Used by the Fenchurch alias pickers. */
    public static final String CHOICES_FROM_AI_IMAGE_MODELS = "ai-image-models";

    /**
     * Validates and applies {@code values} against {@code form}. The
     * returned list mirrors what was actually executed — including
     * SKIPs for password-empty submissions.
     */
    public List<PlannedSettingAction> apply(
            ResolvedSettingForm form,
            Map<String, Object> values,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String lang) {
        formValidator.validate(resolveDynamicChoices(form.fields(), tenantId, projectId), values);
        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, values, tenantId, projectId, userId, lang);
        executePlan(tenantId, plan);
        return plan;
    }

    /**
     * Same as {@link #apply} but without side-effects — runs the form
     * validation and the planner so the UI can show a preview before
     * the user clicks "Save". The returned plan tells the user exactly
     * which keys would be written/deleted/skipped.
     */
    public List<PlannedSettingAction> validate(
            ResolvedSettingForm form,
            Map<String, Object> values,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String lang) {
        formValidator.validate(resolveDynamicChoices(form.fields(), tenantId, projectId), values);
        return planBuilder.buildApplyPlan(
                form, values, tenantId, projectId, userId, lang);
    }

    /**
     * Builds and executes the reset plan: one DELETE per key the form
     * references, on the form's respective scopes. {@code clearable=false}
     * forms must be rejected by the controller — this method does not
     * second-guess the flag.
     */
    public List<PlannedSettingAction> reset(
            ResolvedSettingForm form,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId) {
        List<PlannedSettingAction> plan = planBuilder.buildResetPlan(form, projectId, userId);
        executePlan(tenantId, plan);
        return plan;
    }

    /**
     * Returns the distinct wire-scope labels touched by {@code form}
     * after defaulting per-binding / per-computed entries onto the
     * top-level {@code defaultScope}. The controller iterates this set
     * for per-scope auth checks.
     */
    public Set<String> collectTargetScopes(ResolvedSettingForm form) {
        Set<String> out = new LinkedHashSet<>();
        for (FormFieldDto f : form.fields()) {
            BindsToDto b = f.getBindsTo();
            if (b == null) continue;
            out.add(b.getScope() == null ? form.defaultScope() : b.getScope());
        }
        for (ResolvedComputedSetting cs : form.computedSettings()) {
            out.add(cs.scope() == null ? form.defaultScope() : cs.scope());
        }
        return out;
    }

    /**
     * Populates {@link FormFieldDto#getCurrentValue()} /
     * {@link FormFieldDto#getCurrentSource()} for every direct-mapped
     * field on the resolved form. PASSWORD values are always returned
     * as {@code "***"} (when set) or {@code null} (when unset) —
     * plaintext never leaves this method.
     *
     * <p>The returned list is a copy with new builder-constructed
     * field DTOs; the input form is not mutated.
     */
    public List<FormFieldDto> withLiveCascadeValues(
            ResolvedSettingForm form,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId) {
        List<FormFieldDto> withChoices = resolveDynamicChoices(form.fields(), tenantId, projectId);

        List<FormFieldDto> out = new ArrayList<>(withChoices.size());
        for (FormFieldDto f : withChoices) {
            FormFieldDto base = stripBackendOnly(f);
            BindsToDto b = f.getBindsTo();
            if (b == null) {
                out.add(base);
                continue;
            }
            String wireScope = b.getScope() == null ? form.defaultScope() : b.getScope();
            LiveValue live = lookupLiveValue(f, b, wireScope, tenantId, projectId, userId);
            out.add(base.toBuilder()
                    .currentValue(live.value)
                    .currentSource(live.source)
                    .build());
        }
        return out;
    }

    /**
     * Expands {@code choicesFrom} markers on every field into a real
     * {@link FormChoiceDto} list. Used both by
     * {@link #withLiveCascadeValues} (so the UI sees the choices) and
     * by {@link #apply} / {@link #validate} (so the
     * {@link FormValidator} sees them too — without this the validator
     * rejects every submitted value with {@code invalid_choice}).
     */
    private List<FormFieldDto> resolveDynamicChoices(
            List<FormFieldDto> fields, String tenantId, @Nullable String projectId) {
        @Nullable List<FormChoiceDto> aiModelChoices = null;
        @Nullable List<FormChoiceDto> aiImageModelChoices = null;
        List<FormFieldDto> out = new ArrayList<>(fields.size());
        for (FormFieldDto f : fields) {
            String src = f.getChoicesFrom();
            if (CHOICES_FROM_AI_MODELS.equals(src)) {
                if (aiModelChoices == null) {
                    aiModelChoices = buildAiModelChoices(tenantId, projectId);
                }
                out.add(f.toBuilder().choices(aiModelChoices).build());
            } else if (CHOICES_FROM_AI_IMAGE_MODELS.equals(src)) {
                if (aiImageModelChoices == null) {
                    aiImageModelChoices = buildAiImageModelChoices(tenantId, projectId);
                }
                out.add(f.toBuilder().choices(aiImageModelChoices).build());
            } else {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Builds the {@code FormChoiceDto} list for every
     * {@code (provider, modelName)} pair visible to the current scope.
     * Value: {@code "<provider>:<modelName>"}; label: the same, plus
     * a size tag in parentheses so users can spot small-vs-large at a
     * glance. Order follows {@link ModelCatalog#listAll}.
     */
    private List<FormChoiceDto> buildAiModelChoices(
            String tenantId, @Nullable String projectId) {
        List<ModelInfo> models = modelCatalog.listAll(tenantId, projectId);
        List<FormChoiceDto> out = new ArrayList<>(models.size());
        for (ModelInfo m : models) {
            String value = m.provider() + ":" + m.modelName();
            String label = value + "  (" + m.size().name().toLowerCase(Locale.ROOT) + ")";
            out.add(FormChoiceDto.builder().value(value).label(java.util.Map.of("en", label)).build());
        }
        return out;
    }

    /**
     * Image-model counterpart of {@link #buildAiModelChoices}: only
     * picks up {@code kind: image} entries via
     * {@link ModelCatalog#listAllImages}. Order follows the catalog
     * iteration order.
     */
    private List<FormChoiceDto> buildAiImageModelChoices(
            String tenantId, @Nullable String projectId) {
        List<de.mhus.vance.brain.ai.image.ImageModelInfo> models =
                modelCatalog.listAllImages(tenantId, projectId);
        List<FormChoiceDto> out = new ArrayList<>(models.size());
        for (de.mhus.vance.brain.ai.image.ImageModelInfo m : models) {
            String value = m.provider() + ":" + m.modelName();
            String label = value;
            out.add(FormChoiceDto.builder().value(value).label(java.util.Map.of("en", label)).build());
        }
        return out;
    }

    private LiveValue lookupLiveValue(
            FormFieldDto field, BindsToDto binding, String wireScope,
            String tenantId, @Nullable String projectId, @Nullable String userId) {
        SettingType type = field.getType().equals("password")
                ? SettingType.PASSWORD
                : SettingType.STRING;
        return switch (wireScope) {
            case SettingService.SCOPE_PROJECT -> readCascadeProject(
                    tenantId, projectId, binding.getKey(), type);
            case SettingService.SCOPE_USER -> readUser(
                    tenantId, userId, binding.getKey(), type);
            case SettingService.SCOPE_TENANT -> readTenant(
                    tenantId, binding.getKey(), type);
            default -> new LiveValue(null, null);
        };
    }

    private LiveValue readCascadeProject(
            String tenantId, @Nullable String projectId, String key, SettingType type) {
        if (type == SettingType.PASSWORD) {
            return findFirstPassword(tenantId, projectId, key);
        }
        // Walk: project (if any) → _tenant
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (v != null) return new LiveValue(v, projectId);
        }
        String v = settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, key);
        return v == null ? new LiveValue(null, null)
                : new LiveValue(v, HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    private LiveValue readUser(
            String tenantId, @Nullable String userId, String key, SettingType type) {
        if (userId == null || userId.isBlank()) return new LiveValue(null, null);
        String userProject = HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId;
        if (type == SettingType.PASSWORD) {
            Optional<SettingDocument> doc = settingService.find(
                    tenantId, SettingService.SCOPE_PROJECT, userProject, key);
            if (doc.isPresent() && doc.get().getValue() != null) {
                return new LiveValue("***", userProject);
            }
            return new LiveValue(null, null);
        }
        String v = settingService.getUserStringValue(tenantId, userId, key);
        return v == null ? new LiveValue(null, null) : new LiveValue(v, userProject);
    }

    private LiveValue readTenant(String tenantId, String key, SettingType type) {
        String ref = HomeBootstrapService.TENANT_PROJECT_NAME;
        if (type == SettingType.PASSWORD) {
            Optional<SettingDocument> doc = settingService.find(
                    tenantId, SettingService.SCOPE_PROJECT, ref, key);
            if (doc.isPresent() && doc.get().getValue() != null) {
                return new LiveValue("***", ref);
            }
            return new LiveValue(null, null);
        }
        String v = settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT, ref, key);
        return v == null ? new LiveValue(null, null) : new LiveValue(v, ref);
    }

    private LiveValue findFirstPassword(
            String tenantId, @Nullable String projectId, String key) {
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Optional<SettingDocument> doc = settingService.find(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (doc.isPresent() && doc.get().getValue() != null) {
                return new LiveValue("***", projectId);
            }
        }
        Optional<SettingDocument> doc = settingService.find(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, key);
        if (doc.isPresent() && doc.get().getValue() != null) {
            return new LiveValue("***", HomeBootstrapService.TENANT_PROJECT_NAME);
        }
        return new LiveValue(null, null);
    }

    /**
     * Returns a copy of the field DTO with backend-only fields
     * ({@code showIf}, {@code writeIf}) blanked. Pebble templates
     * never leave the brain — see spec §11.
     */
    private static FormFieldDto stripBackendOnly(FormFieldDto in) {
        return in.toBuilder()
                .showIf(null)
                .writeIf(null)
                .build();
    }

    private record LiveValue(@Nullable String value, @Nullable String source) {}

    // ──────────────────── Execution ────────────────────

    private void executePlan(String tenantId, List<PlannedSettingAction> plan) {
        for (PlannedSettingAction action : plan) {
            switch (action.action()) {
                case SKIP -> { /* no-op */ }
                case DELETE -> settingService.delete(
                        tenantId, action.referenceType(), action.referenceId(), action.key());
                case WRITE -> applyWrite(tenantId, action);
            }
        }
    }

    private void applyWrite(String tenantId, PlannedSettingAction action) {
        SettingType type = action.settingType();
        if (type == null) {
            throw new IllegalStateException(
                    "WRITE plan entry for key '" + action.key()
                            + "' is missing settingType — planner bug");
        }
        String value = action.value();
        if (type == SettingType.PASSWORD) {
            settingService.setEncryptedPassword(
                    tenantId, action.referenceType(), action.referenceId(),
                    action.key(), value);
        } else {
            settingService.set(
                    tenantId, action.referenceType(), action.referenceId(),
                    action.key(), value, type, null);
        }
        log.debug("setting-form wrote {}:{} key='{}' type={} (source={})",
                action.referenceType(), action.referenceId(),
                action.key(), type, action.sourceLabel());
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Convenience: looks up the {@link ResolvedScope} for a wire-scope
     * label without going through the planner. Used by the controller
     * for per-scope auth checks.
     */
    public SettingFormPlanBuilder.ResolvedScope resolveScope(
            String wireScope, @Nullable String projectId, @Nullable String userId) {
        return planBuilder.resolveScope(
                wireScope == null ? SettingService.SCOPE_PROJECT
                        : wireScope.toLowerCase(Locale.ROOT),
                projectId, userId);
    }
}
