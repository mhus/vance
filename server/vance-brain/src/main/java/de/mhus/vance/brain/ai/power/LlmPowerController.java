package de.mhus.vance.brain.ai.power;

import de.mhus.vance.api.insights.LlmPowerStatusDto;
import de.mhus.vance.api.insights.LlmPowerUpdateRequest;
import de.mhus.vance.api.insights.LlmPowerUserDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface of the LLM power switches, behind the Insights tab "Power".
 * Every endpoint requires {@link Action#ADMIN} on the tenant — a switch that
 * the switched-off user could flip back would be decoration.
 *
 * <p>Every answer carries both views: what is stored, and what the answering
 * pod currently enforces. A change applies on the pod that wrote it at once and
 * on every other pod at the next refresh tick; the client checks at
 * {@code verifyAt} that the two views agree. Which pod answers that check does
 * not matter — every pod ticks at the same wall-clock second.
 */
@RestController
@RequestMapping("/brain/{tenant}/llm-power")
@RequiredArgsConstructor
public class LlmPowerController {

    private final LlmPowerSwitch powerSwitch;
    private final UserService userService;
    private final RequestAuthority authority;

    @GetMapping
    public LlmPowerStatusDto status(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        return toDto(tenant, powerSwitch.stored(tenant));
    }

    @PutMapping
    public LlmPowerStatusDto setTenant(
            @PathVariable("tenant") String tenant,
            @RequestBody LlmPowerUpdateRequest body,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        String actor = authority.contextOf(httpRequest).subjectId();
        return toDto(tenant, powerSwitch.setTenantPower(tenant, body.isEnabled(), actor));
    }

    @PutMapping("/users/{userId}")
    public LlmPowerStatusDto setUser(
            @PathVariable("tenant") String tenant,
            @PathVariable("userId") String userId,
            @RequestBody LlmPowerUpdateRequest body,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        String actor = authority.contextOf(httpRequest).subjectId();
        return toDto(tenant, powerSwitch.setUserPower(tenant, userId, body.isEnabled(), actor));
    }

    private LlmPowerStatusDto toDto(String tenant, LlmPowerState state) {
        // Nothing cached means the pod loads the stored state on its next call.
        LlmPowerState cached = powerSwitch.cached(tenant);
        LlmPowerState effective = cached == null ? state : cached;
        List<LlmPowerUserDto> users = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UserDocument user : userService.all(tenant)) {
            seen.add(user.getName());
            users.add(userRow(user.getName(), user.getTitle(), user.isServiceAccount(), true, state, effective));
        }
        // A switched-off login without a user: still listed, so it can be switched on.
        for (String orphan : state.usersOff().keySet()) {
            if (!seen.contains(orphan)) {
                users.add(userRow(
                        orphan, null, orphan.startsWith(UserService.SERVICE_ACCOUNT_PREFIX), false, state, effective));
            }
        }
        users.sort(Comparator.comparing(LlmPowerUserDto::getUserId));
        LlmPowerState.Switch tenantOff = state.tenantOff();
        return LlmPowerStatusDto.builder()
                .enabled(state.tenantOn())
                .since(tenantOff == null ? null : tenantOff.since())
                .note(tenantOff == null ? null : tenantOff.note())
                .effectiveEnabled(effective.tenantOn())
                .serverTime(powerSwitch.now())
                .verifyAt(powerSwitch.verifyAt())
                .users(users)
                .build();
    }

    private static LlmPowerUserDto userRow(
            String userId,
            @Nullable String title,
            boolean serviceAccount,
            boolean known,
            LlmPowerState state,
            LlmPowerState effective) {
        LlmPowerState.Switch off = state.usersOff().get(userId);
        return LlmPowerUserDto.builder()
                .userId(userId)
                .title(title)
                .serviceAccount(serviceAccount)
                .known(known)
                .enabled(off == null)
                .effectiveEnabled(effective.userOn(userId))
                .since(off == null ? null : off.since())
                .note(off == null ? null : off.note())
                .build();
    }
}
