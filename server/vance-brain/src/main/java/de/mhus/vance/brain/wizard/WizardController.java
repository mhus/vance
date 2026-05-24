package de.mhus.vance.brain.wizard;

import de.mhus.vance.api.wizard.WizardDto;
import de.mhus.vance.api.wizard.WizardListResponseDto;
import de.mhus.vance.api.wizard.WizardRenderRequestDto;
import de.mhus.vance.api.wizard.WizardRenderResponseDto;
import de.mhus.vance.api.wizard.WizardSummaryDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.form.FormValidationException;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.form.LocalizedTexts;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.LanguageResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * REST endpoints for the prompt-wizard subsystem. Three calls:
 *
 * <ul>
 *   <li>{@code GET    /brain/{tenant}/wizards}            — listing</li>
 *   <li>{@code GET    /brain/{tenant}/wizards/{name}}     — full definition for form rendering</li>
 *   <li>{@code POST   /brain/{tenant}/wizards/{name}/render} — submit values, get the rendered prompt</li>
 * </ul>
 *
 * <p>All three accept an optional {@code projectId} query parameter.
 * When set, the project layer of the cascade is consulted and the
 * permission check is project-scoped READ; otherwise tenant-scoped READ.
 *
 * <p>The render endpoint never spawns or persists — it just templates.
 * The resulting string is dropped into the Web-UI chat input field
 * and only sent through the normal spawn path when the user clicks
 * Send.
 */
@RestController
@RequestMapping("/brain/{tenant}/wizards")
@RequiredArgsConstructor
@Slf4j
public class WizardController {

    private final WizardLoader wizardLoader;
    private final FormValidator formValidator;
    private final PromptTemplateRenderer templateRenderer;
    private final FollowUpRenderer followUpRenderer;
    private final LanguageResolver languageResolver;
    private final RequestAuthority authority;

    @GetMapping("")
    public WizardListResponseDto list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);
        String lang = languageResolver.chatLanguage(tenant, userId, projectId, null);

        List<ResolvedWizard> wizards = wizardLoader.listAll(tenant, projectId, userId);
        List<WizardSummaryDto> summaries = new ArrayList<>(wizards.size());
        for (ResolvedWizard w : wizards) {
            summaries.add(toSummary(w, lang));
        }
        return WizardListResponseDto.builder().wizards(summaries).build();
    }

    @GetMapping("/{name}")
    public WizardDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);

        Optional<ResolvedWizard> hit = loadOrThrow(tenant, projectId, userId, name);
        ResolvedWizard w = hit.get();
        return WizardDto.builder()
                .name(w.name())
                .title(w.title())
                .description(w.description())
                .icon(w.icon())
                .category(w.category())
                .fields(w.fields())
                .source(w.source().name())
                .hasValidator(w.validatorPrompt() != null)
                .build();
    }

    @PostMapping("/{name}/render")
    public WizardRenderResponseDto render(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @Valid @RequestBody WizardRenderRequestDto body,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);

        Optional<ResolvedWizard> hit = loadOrThrow(tenant, projectId, userId, name);
        ResolvedWizard w = hit.get();
        Map<String, Object> values = body.getValues() == null ? Map.of() : body.getValues();

        try {
            formValidator.validate(w.fields(), values);
        } catch (FormValidationException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        String lang = body.getLang() != null && !body.getLang().isBlank()
                ? body.getLang()
                : languageResolver.chatLanguage(tenant, userId, projectId, null);

        Map<String, Object> ctx = new HashMap<>(values);
        ctx.putIfAbsent("lang", lang);
        ctx.putIfAbsent("user", userId == null ? "" : userId);
        ctx.putIfAbsent("project", projectId == null ? "" : projectId);

        String rendered;
        try {
            rendered = templateRenderer.render(w.promptTemplate(), ctx);
        } catch (PromptTemplateException e) {
            log.warn("Wizard '{}' render failed: {}", w.name(), e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Wizard prompt-template render failed: " + e.getMessage(), e);
        }
        String main = rendered == null ? "" : rendered;
        String suffix = followUpRenderer.render(w.name(), w.followUps(), ctx, lang);
        String full = suffix.isEmpty() ? main : main + suffix;
        return WizardRenderResponseDto.builder()
                .prompt(full)
                .build();
    }

    private SecurityContext enforceRead(
            HttpServletRequest request, String tenant, @Nullable String projectId) {
        Resource resource = (projectId == null || projectId.isBlank())
                ? new Resource.Tenant(tenant)
                : new Resource.Project(tenant, projectId);
        authority.enforce(request, resource, Action.READ);
        return authority.contextOf(request);
    }

    private static @Nullable String userIdOf(SecurityContext context) {
        String id = context.subjectId();
        return id == null || id.isBlank() ? null : id;
    }

    private Optional<ResolvedWizard> loadOrThrow(
            String tenant, @Nullable String projectId, @Nullable String userId, String name) {
        Optional<ResolvedWizard> hit;
        try {
            hit = wizardLoader.load(tenant, projectId, userId, name);
        } catch (WizardParseException e) {
            log.warn("Wizard '{}' parse error: {}", name, e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Wizard parse error: " + e.getMessage(), e);
        }
        if (hit.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Wizard not found: " + name);
        }
        return hit;
    }

    private static WizardSummaryDto toSummary(ResolvedWizard w, String lang) {
        return WizardSummaryDto.builder()
                .name(w.name())
                .title(LocalizedTexts.resolve(w.title(), lang))
                .description(LocalizedTexts.resolve(w.description(), lang))
                .icon(w.icon())
                .category(w.category())
                .source(w.source().name())
                .build();
    }
}
