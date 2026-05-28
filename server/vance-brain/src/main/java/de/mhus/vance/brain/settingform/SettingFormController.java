package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settingform.AppliedSettingDto;
import de.mhus.vance.api.settingform.ComputedSettingDto;
import de.mhus.vance.api.settingform.SettingFormApplyRequestDto;
import de.mhus.vance.api.settingform.SettingFormApplyResponseDto;
import de.mhus.vance.api.settingform.SettingFormDto;
import de.mhus.vance.api.settingform.SettingFormListResponseDto;
import de.mhus.vance.api.settingform.SettingFormSummaryDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.form.FormValidationException;
import de.mhus.vance.shared.form.LocalizedTexts;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.LanguageResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for Setting Forms — listing, definition fetch (with
 * live cascade values per direct-mapped field), and the three write
 * verbs apply / validate / reset. See spec §7 + §8.
 *
 * <p>Auth is two-stage. Read endpoints (listing, GET /{name}) enforce
 * project-scoped READ when {@code projectId} is supplied, else
 * tenant-scoped READ — mirroring the wizard subsystem.  Write
 * endpoints enforce {@code Resource.Setting(...ADMIN)} per plan
 * action <em>before</em> any persistence touches the database, so a
 * partial write under a missing permission is impossible.
 *
 * <p>Pebble templates are never serialised by these endpoints — the
 * {@code value} expressions in {@code settings:} and the
 * {@code showIf}/{@code writeIf} on fields stay backend-only.
 * {@link SettingFormService#withLiveCascadeValues} strips the
 * expression strings from the response.
 */
@RestController
@RequestMapping("/brain/{tenant}/setting-forms")
@RequiredArgsConstructor
@Slf4j
public class SettingFormController {

    private final SettingFormLoader loader;
    private final SettingFormService settingFormService;
    private final LanguageResolver languageResolver;
    private final RequestAuthority authority;

    @GetMapping("")
    public SettingFormListResponseDto list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);
        String lang = languageResolver.chatLanguage(tenant, userId, projectId, null);

        List<ResolvedSettingForm> forms = loader.listAll(tenant, projectId, userId);
        List<SettingFormSummaryDto> summaries = new ArrayList<>(forms.size());
        for (ResolvedSettingForm f : forms) {
            summaries.add(toSummary(f, lang));
        }
        return SettingFormListResponseDto.builder().forms(summaries).build();
    }

    @GetMapping("/{name}")
    public SettingFormDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);
        String lang = languageResolver.chatLanguage(tenant, userId, projectId, null);

        ResolvedSettingForm form = loadOrThrow(tenant, projectId, userId, name);

        List<FormFieldDto> fields = settingFormService.withLiveCascadeValues(
                form, tenant, projectId, userId);
        List<ComputedSettingDto> computed = toComputedSummaries(form);

        return SettingFormDto.builder()
                .name(form.name())
                .title(LocalizedTexts.resolve(form.title(), lang))
                .description(LocalizedTexts.resolve(form.description(), lang))
                .icon(form.icon())
                .category(form.category())
                .defaultScope(form.defaultScope())
                .fields(fields)
                .settings(computed)
                .source(form.source().name())
                .clearable(form.clearable())
                .build();
    }

    @PostMapping("/{name}/apply")
    public SettingFormApplyResponseDto apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @Valid @RequestBody SettingFormApplyRequestDto body,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);

        ResolvedSettingForm form = loadOrThrow(tenant, projectId, userId, name);
        enforceWriteForScopes(request, tenant, form, projectId, userId);

        Map<String, Object> values = body.getValues() == null ? Map.of() : body.getValues();
        String lang = body.getLang() != null && !body.getLang().isBlank()
                ? body.getLang()
                : languageResolver.chatLanguage(tenant, userId, projectId, null);

        List<PlannedSettingAction> plan;
        try {
            plan = settingFormService.apply(form, values, tenant, projectId, userId, lang);
        } catch (FormValidationException e) {
            log.warn("Setting form '{}' validation failed: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.warn("Setting form '{}' apply failed: {}", name, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        log.info("Setting form '{}' applied tenant='{}' project='{}' actions={}",
                name, tenant, projectId, plan.size());
        return toApplyResponse(plan);
    }

    @PostMapping("/{name}/validate")
    public SettingFormApplyResponseDto validate(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @Valid @RequestBody SettingFormApplyRequestDto body,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);

        ResolvedSettingForm form = loadOrThrow(tenant, projectId, userId, name);
        // Validate is a dry-run preview — no writes happen, so we don't enforce
        // write-perms here. The UI still calls /apply afterwards which does.

        Map<String, Object> values = body.getValues() == null ? Map.of() : body.getValues();
        String lang = body.getLang() != null && !body.getLang().isBlank()
                ? body.getLang()
                : languageResolver.chatLanguage(tenant, userId, projectId, null);

        List<PlannedSettingAction> plan;
        try {
            plan = settingFormService.validate(form, values, tenant, projectId, userId, lang);
        } catch (FormValidationException e) {
            log.warn("Setting form '{}' validation (dry-run) failed: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.warn("Setting form '{}' validate failed: {}", name, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return toApplyResponse(plan);
    }

    @PostMapping("/{name}/reset")
    public SettingFormApplyResponseDto reset(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);

        ResolvedSettingForm form = loadOrThrow(tenant, projectId, userId, name);
        if (!form.clearable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Setting form '" + name + "' is not clearable");
        }
        enforceWriteForScopes(request, tenant, form, projectId, userId);

        List<PlannedSettingAction> plan;
        try {
            plan = settingFormService.reset(form, tenant, projectId, userId);
        } catch (IllegalStateException e) {
            log.warn("Setting form '{}' reset failed: {}", name, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        log.info("Setting form '{}' reset tenant='{}' project='{}' actions={}",
                name, tenant, projectId, plan.size());
        return toApplyResponse(plan);
    }

    // ──────────────────── Auth helpers ────────────────────

    private SecurityContext enforceRead(
            HttpServletRequest request, String tenant, @Nullable String projectId) {
        Resource resource = (projectId == null || projectId.isBlank())
                ? new Resource.Tenant(tenant)
                : new Resource.Project(tenant, projectId);
        authority.enforce(request, resource, Action.READ);
        return authority.contextOf(request);
    }

    private void enforceWriteForScopes(
            HttpServletRequest request,
            String tenant,
            ResolvedSettingForm form,
            @Nullable String projectId,
            @Nullable String userId) {
        Set<String> wireScopes = settingFormService.collectTargetScopes(form);
        for (String wireScope : wireScopes) {
            SettingFormPlanBuilder.ResolvedScope scope;
            try {
                scope = settingFormService.resolveScope(wireScope, projectId, userId);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
            // Per-form-scope check on the synthetic Resource.Setting; the key
            // is intentionally blank — the permission resolver treats this as
            // "may write any key in this scope". Concrete-key checks would
            // mean N round-trips per form for typical use; coarse scope is
            // the right grain for Setting Forms.
            authority.enforce(request,
                    new Resource.Setting(tenant, scope.referenceType(), scope.referenceId(), ""),
                    Action.ADMIN);
        }
    }

    // ──────────────────── Load helpers ────────────────────

    private ResolvedSettingForm loadOrThrow(
            String tenant, @Nullable String projectId, @Nullable String userId, String name) {
        Optional<ResolvedSettingForm> hit;
        try {
            hit = loader.load(tenant, projectId, userId, name);
        } catch (SettingFormParseException e) {
            log.warn("Setting form '{}' parse error: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Setting form parse error: " + e.getMessage(), e);
        }
        if (hit.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Setting form not found: " + name);
        }
        return hit.get();
    }

    private static @Nullable String userIdOf(SecurityContext context) {
        String id = context.subjectId();
        return id == null || id.isBlank() ? null : id;
    }

    // ──────────────────── DTO mapping ────────────────────

    private static SettingFormSummaryDto toSummary(ResolvedSettingForm f, String lang) {
        return SettingFormSummaryDto.builder()
                .name(f.name())
                .title(LocalizedTexts.resolve(f.title(), lang))
                .description(LocalizedTexts.resolve(f.description(), lang))
                .icon(f.icon())
                .category(f.category())
                .source(f.source().name())
                .clearable(f.clearable())
                .build();
    }

    private static List<ComputedSettingDto> toComputedSummaries(ResolvedSettingForm f) {
        List<ComputedSettingDto> out = new ArrayList<>(f.computedSettings().size());
        for (ResolvedComputedSetting cs : f.computedSettings()) {
            out.add(ComputedSettingDto.builder()
                    .key(cs.key())
                    .scope(cs.scope())
                    .settingType(cs.settingType().name())
                    .conditional(cs.writeIfExpression() != null)
                    .build());
        }
        return out;
    }

    private static SettingFormApplyResponseDto toApplyResponse(List<PlannedSettingAction> plan) {
        List<AppliedSettingDto> applied = new ArrayList<>(plan.size());
        for (PlannedSettingAction a : plan) {
            applied.add(AppliedSettingDto.builder()
                    .key(a.key())
                    .scope(a.wireScope())
                    .action(a.action().name().toLowerCase(java.util.Locale.ROOT))
                    .settingType(a.settingType() == null ? null : a.settingType().name())
                    .valueMasked(a.masked())
                    .build());
        }
        return SettingFormApplyResponseDto.builder().applied(applied).build();
    }
}
