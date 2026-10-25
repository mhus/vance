package de.mhus.vance.brain.users;

import de.mhus.vance.api.users.UserQuotaDto;
import de.mhus.vance.api.users.UserQuotaUsageDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.user.UserQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's account quotas — counts and limits.
 *
 * <p>Its own path rather than {@code /admin/users/quota}: that one is
 * {@code GET /admin/users/{name}} for a user called {@code quota}. Same gate as
 * the user list — whoever may see the accounts may see how full the quota is.
 */
@RestController
@RequiredArgsConstructor
public class UserQuotaController {

    private final UserQuotaService quotaService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/admin/user-quota")
    public UserQuotaDto quota(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.WRITE);
        UserQuotaService.Status status = quotaService.status(tenant);
        return UserQuotaDto.builder()
                .seats(toDto(status.seats()))
                .serviceAccounts(toDto(status.serviceAccounts()))
                .build();
    }

    private static UserQuotaUsageDto toDto(UserQuotaService.Usage usage) {
        return UserQuotaUsageDto.builder()
                .active(usage.active())
                .limit(usage.limit())
                .build();
    }
}
