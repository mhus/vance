package de.mhus.vance.brain.profile;

import de.mhus.vance.api.profile.ProfileDto;
import de.mhus.vance.api.profile.ProfileSettingWriteRequest;
import de.mhus.vance.api.profile.ProfileUpdateRequest;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.teams.TeamSummary;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-service profile endpoint. Backs the Web-UI's
 * {@code profile.html} page: read aggregated profile (identity +
 * teams + {@code webui.*} settings), patch identity, write a single
 * UI-setting.
 *
 * <p>Authorisation model: <b>only the caller's own profile</b>. There
 * is no path parameter for the username — every endpoint resolves the
 * subject from the authenticated JWT claim. That keeps the surface
 * tight: any user can edit themselves; nothing here can target
 * another user. Cross-user user-management goes through
 * {@code /brain/{tenant}/admin/users} (which requires {@code ADMIN}).
 *
 * <p>Settings writes are restricted to the {@code webui.*} prefix to
 * keep the profile from becoming a backdoor into arbitrary user-scope
 * settings (which {@code AdminSettingsController} guards with admin
 * permission). Anything outside the prefix → 400.
 */
@RestController
@RequestMapping("/brain/{tenant}/profile")
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

    private final UserService userService;
    private final SettingService settingService;
    private final TeamService teamService;

    @GetMapping
    public ProfileDto get(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        UserDocument user = userService.findByTenantAndName(tenant, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Profile not found for current user"));
        return toDto(user, loadTeams(tenant, username), loadSettings(tenant, username));
    }

    @PutMapping
    public ProfileDto update(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody ProfileUpdateRequest request,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        try {
            // Pass the existing status as null so it stays unchanged —
            // the caller cannot escalate / lock themselves out via
            // this endpoint. Title and email are the only mutable
            // fields exposed here.
            UserDocument saved = userService.update(
                    tenant, username, request.getTitle(), request.getEmail(), null);
            log.info("Profile updated tenant='{}' user='{}'", tenant, username);
            return toDto(saved, loadTeams(tenant, username), loadSettings(tenant, username));
        } catch (UserService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/settings/{key}")
    public ProfileDto setSetting(
            @PathVariable("tenant") String tenant,
            @PathVariable("key") String key,
            @Valid @RequestBody ProfileSettingWriteRequest request,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        requireWebUiKey(key);
        // Stored on the per-user `_user_<login>` project — the same
        // location AdminSettingsController writes to for the {@code
        // user/<login>} wire scope.
        settingService.set(tenant,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + username,
                key,
                request.getValue(),
                SettingType.STRING,
                null);
        log.info("Profile setting upserted tenant='{}' user='{}' key='{}'",
                tenant, username, key);
        UserDocument user = userService.findByTenantAndName(tenant, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Profile not found for current user"));
        return toDto(user, loadTeams(tenant, username), loadSettings(tenant, username));
    }

    @DeleteMapping("/settings/{key}")
    public ResponseEntity<Void> deleteSetting(
            @PathVariable("tenant") String tenant,
            @PathVariable("key") String key,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        requireWebUiKey(key);
        settingService.delete(tenant,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + username,
                key);
        log.info("Profile setting deleted tenant='{}' user='{}' key='{}'",
                tenant, username, key);
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }

    private static void requireWebUiKey(String key) {
        if (key == null || !key.startsWith(WebUiCookies.SETTINGS_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Profile settings keys must start with '"
                            + WebUiCookies.SETTINGS_PREFIX + "'");
        }
    }

    private List<TeamSummary> loadTeams(String tenant, String username) {
        List<TeamDocument> teams = teamService.byMember(tenant, username);
        List<TeamSummary> out = new ArrayList<>(teams.size());
        for (TeamDocument t : teams) {
            out.add(TeamSummary.builder()
                    .id(t.getId() == null ? "" : t.getId())
                    .name(t.getName())
                    .title(t.getTitle())
                    .members(t.getMembers() == null
                            ? new ArrayList<>()
                            : new ArrayList<>(t.getMembers()))
                    .enabled(t.isEnabled())
                    .build());
        }
        return out;
    }

    private Map<String, String> loadSettings(String tenant, String username) {
        return settingService.findUserSettingsByPrefix(
                tenant, username, WebUiCookies.SETTINGS_PREFIX);
    }

    private static ProfileDto toDto(UserDocument user,
                                    List<TeamSummary> teams,
                                    Map<String, String> settings) {
        return ProfileDto.builder()
                .tenantId(user.getTenantId())
                .name(user.getName())
                .title(user.getTitle())
                .email(user.getEmail())
                .teams(teams)
                .webUiSettings(settings)
                .build();
    }
}
