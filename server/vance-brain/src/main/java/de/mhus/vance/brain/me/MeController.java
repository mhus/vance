package de.mhus.vance.brain.me;

import de.mhus.vance.api.me.MeDto;
import de.mhus.vance.brain.home.HomeStatusService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.profile.UiSettingsService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user, everything the Web-UI needs in one request: identity,
 * tenant role (and own project role with {@code ?project=}), UI settings and
 * the status hints. Replaces the settings snapshot that used to ride in the
 * data cookie — a cookie is only re-issued on login and refresh, so a value
 * changed elsewhere (a role, a setting written by an agent) stayed stale until
 * then.
 *
 * <p>Open to every authenticated member: it only reports about the caller.
 * Display information only — the server enforces every request on its own.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MeController {

    private final RequestAuthority authority;
    private final PermissionService permissionService;
    private final UserService userService;
    private final UiSettingsService uiSettingsService;
    private final HomeStatusService homeStatusService;

    @GetMapping("/brain/{tenant}/me")
    public MeDto me(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "project", required = false) @Nullable String project,
            HttpServletRequest httpRequest) {
        SecurityContext me = authority.contextOf(httpRequest);
        String username = me.subjectId();
        boolean askedAboutProject = project != null && !project.isBlank();

        MeDto.MeDtoBuilder dto = MeDto.builder()
                .username(username)
                .tenantId(me.tenantId())
                .displayName(userService
                        .findByTenantAndName(me.tenantId(), username)
                        .map(UserDocument::getTitle)
                        .orElse(null))
                .tenantRole(permissionService.tenantRoleOf(me))
                .project(askedAboutProject ? project : null)
                .projectRole(askedAboutProject ? permissionService.projectRoleOf(me, project) : null)
                .settings(uiSettingsService.load(me.tenantId(), username));
        try {
            dto.status(homeStatusService.status(me, me.tenantId(), askedAboutProject ? project : null));
        } catch (RuntimeException e) {
            // The status parts already fail open one by one; this only
            // catches what is left — the rest of the answer still stands.
            log.warn("Status for '{}' in '{}' not readable: {}", username, tenant, e.toString());
        }
        return dto.build();
    }
}
