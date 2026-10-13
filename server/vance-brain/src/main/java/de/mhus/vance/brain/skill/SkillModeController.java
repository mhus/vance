package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillModeRequest;
import de.mhus.vance.api.skills.SkillModeResponse;
import de.mhus.vance.api.skills.SkillModeScope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.shared.permission.WriteActor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for a skill's activation mode — {@code POST
 * /brain/{tenant}/skills/{skillName}/activation}.
 *
 * <p>Scope configuration without process involvement, which is why this
 * is a REST endpoint and not a {@code process-skill} command: the mode
 * applies to every future turn of every process in the scope
 * (planning/skill-activation-levels.md §3.5).
 *
 * <p>Authorization follows the F1 rule — enforced at the source, not
 * re-checked at the surface: the skill resolution goes through the
 * document cascade's read checks, the override write goes through
 * {@code DocumentService}'s CREATE/WRITE enforcement against the
 * authenticated actor. The user tier is the caller's own hub project;
 * project and tenant writes fail there for callers without access.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SkillModeController {

    private final SkillActivationService activationService;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/skills/{skillName}/activation")
    @ResponseStatus(HttpStatus.OK)
    public SkillModeResponse setMode(
            @PathVariable("tenant") String tenant,
            @PathVariable("skillName") String skillName,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestBody SkillModeRequest request,
            HttpServletRequest httpRequest) {
        if (request == null || request.getMode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required (hidden|manual|trigger|agent)");
        }
        if (request.getScope() == null) {
            request.setScope(SkillModeScope.USER);
        }
        SecurityContext ctx = authority.contextOf(httpRequest);
        String userId = ctx.subjectType() == SubjectType.USER ? ctx.subjectId() : null;
        try {
            SkillActivationService.ActivationResolution resolved = activationService.setMode(
                    tenant, userId, projectId, skillName, request.getMode(), request.getScope(), WriteActor.user(ctx));
            return SkillModeResponse.builder()
                    .skillName(skillName)
                    .activation(resolved.mode())
                    .activationSource(resolved.source())
                    .activationOverrideScope(resolved.overrideScope())
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
