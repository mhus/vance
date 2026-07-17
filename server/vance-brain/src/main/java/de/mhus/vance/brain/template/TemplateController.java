package de.mhus.vance.brain.template;

import de.mhus.vance.api.template.TemplateApplyRequestDto;
import de.mhus.vance.api.template.TemplateApplyResponseDto;
import de.mhus.vance.api.template.TemplateDto;
import de.mhus.vance.api.template.TemplateListResponseDto;
import de.mhus.vance.api.template.TemplateSummaryDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.form.LocalizedTexts;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.LanguageResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * REST endpoints for the document-template subsystem:
 *
 * <ul>
 *   <li>{@code GET  /brain/{tenant}/templates}          — listing (optional {@code ?tag=} filter)</li>
 *   <li>{@code GET  /brain/{tenant}/templates/{name}}   — full definition for form rendering</li>
 *   <li>{@code POST /brain/{tenant}/templates/{name}/apply} — render the body, write a new document</li>
 * </ul>
 *
 * <p>All three accept an optional {@code projectId} query parameter.
 * Auth: {@code apply} enforces project-scoped WRITE (the document is
 * created in the project); the read endpoints enforce project- or
 * tenant-scoped READ, mirroring the wizard subsystem.
 *
 * <p>{@code FormValidationException} thrown by the service is translated
 * to a structured HTTP 400 by the global {@code FormValidationExceptionAdvice}.
 */
@RestController
@RequestMapping("/brain/{tenant}/templates")
@RequiredArgsConstructor
@Slf4j
public class TemplateController {

    private final TemplateLoader templateLoader;
    private final TemplateService templateService;
    private final LanguageResolver languageResolver;
    private final RequestAuthority authority;

    @GetMapping("")
    public TemplateListResponseDto list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "tag", required = false) @Nullable String tag,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);
        String lang = languageResolver.chatLanguage(tenant, userId, projectId, null);

        String tagFilter = (tag == null || tag.isBlank()) ? null : tag.trim().toLowerCase(Locale.ROOT);
        List<ResolvedTemplate> templates = templateLoader.listAll(tenant, projectId);
        List<TemplateSummaryDto> summaries = new ArrayList<>(templates.size());
        for (ResolvedTemplate t : templates) {
            if (tagFilter != null && !containsTag(t.tags(), tagFilter)) continue;
            summaries.add(toSummary(t, lang));
        }
        return TemplateListResponseDto.builder().templates(summaries).build();
    }

    @GetMapping("/{name}")
    public TemplateDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {
        SecurityContext context = enforceRead(request, tenant, projectId);
        String userId = userIdOf(context);
        String lang = languageResolver.chatLanguage(tenant, userId, projectId, null);

        ResolvedTemplate t = loadOrThrow(tenant, projectId, name);
        String nameDefault = t.nameMode() == TemplateNameMode.FREE
                ? templateService.renderNameDefault(t, userId, projectId, lang, tenant)
                : null;
        return TemplateDto.builder()
                .name(t.name())
                .title(t.title())
                .description(t.description())
                .icon(t.icon())
                .tags(t.tags())
                .nameMode(t.nameMode().wire())
                .nameDefault(nameDefault == null || nameDefault.isBlank() ? null : nameDefault)
                .nameValue(t.nameValue())
                .type(t.typeOverride())
                .fields(t.fields())
                .hasForm(!t.fields().isEmpty())
                .source(t.source().name())
                .build();
    }

    @PostMapping("/{name}/apply")
    public TemplateApplyResponseDto apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @Valid @RequestBody TemplateApplyRequestDto body,
            HttpServletRequest request) {
        SecurityContext context = enforceWrite(request, tenant, projectId);
        String userId = userIdOf(context);

        ResolvedTemplate t = loadOrThrow(tenant, projectId, name);
        Map<String, Object> values = body.getValues() == null ? Map.of() : body.getValues();
        String lang = body.getLang() != null && !body.getLang().isBlank()
                ? body.getLang()
                : languageResolver.chatLanguage(tenant, userId, projectId, null);

        TemplateService.AppliedTemplate applied;
        try {
            applied = templateService.apply(
                    t, body.getFolder(), body.getName(), values, tenant, projectId, userId, lang);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.warn("Template '{}' apply failed: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return TemplateApplyResponseDto.builder()
                .path(applied.path())
                .mimeType(applied.mimeType())
                .build();
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

    private SecurityContext enforceWrite(
            HttpServletRequest request, String tenant, @Nullable String projectId) {
        Resource resource = (projectId == null || projectId.isBlank())
                ? new Resource.Tenant(tenant)
                : new Resource.Project(tenant, projectId);
        authority.enforce(request, resource, Action.WRITE);
        return authority.contextOf(request);
    }

    private static @Nullable String userIdOf(SecurityContext context) {
        String id = context.subjectId();
        return id == null || id.isBlank() ? null : id;
    }

    private ResolvedTemplate loadOrThrow(
            String tenant, @Nullable String projectId, String name) {
        try {
            return templateLoader.load(tenant, projectId, name)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Template not found: " + name));
        } catch (TemplateParseException e) {
            log.warn("Template '{}' parse error: {}", name, e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Template parse error: " + e.getMessage(), e);
        }
    }

    private static boolean containsTag(List<String> tags, String tagLower) {
        for (String s : tags) {
            if (s.toLowerCase(Locale.ROOT).equals(tagLower)) return true;
        }
        return false;
    }

    private static TemplateSummaryDto toSummary(ResolvedTemplate t, String lang) {
        return TemplateSummaryDto.builder()
                .name(t.name())
                .title(LocalizedTexts.resolve(t.title(), lang))
                .description(LocalizedTexts.resolve(t.description(), lang))
                .icon(t.icon())
                .tags(t.tags())
                .nameMode(t.nameMode().wire())
                .source(t.source().name())
                .build();
    }
}
