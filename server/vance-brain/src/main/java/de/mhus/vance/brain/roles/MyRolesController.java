package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.MyRolesDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own roles, for the Web-UI to decide which surfaces to show. Open
 * to every authenticated member: it only reports what the caller holds, and the
 * server still enforces every request on its own.
 */
@RestController
@RequiredArgsConstructor
public class MyRolesController {

    private final RequestAuthority authority;
    private final PermissionService permissionService;

    @GetMapping("/brain/{tenant}/me/roles")
    public MyRolesDto myRoles(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "project", required = false) @Nullable String project,
            HttpServletRequest httpRequest) {
        SecurityContext me = authority.contextOf(httpRequest);
        boolean askedAboutProject = project != null && !project.isBlank();
        return MyRolesDto.builder()
                .tenantRole(permissionService.tenantRoleOf(me))
                .project(askedAboutProject ? project : null)
                .projectRole(askedAboutProject ? permissionService.projectRoleOf(me, project) : null)
                .build();
    }
}
