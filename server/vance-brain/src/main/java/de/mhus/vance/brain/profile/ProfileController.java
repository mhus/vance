package de.mhus.vance.brain.profile;

import de.mhus.vance.api.profile.ProfileDto;
import de.mhus.vance.api.profile.ProfileSettingWriteRequest;
import de.mhus.vance.api.profile.ProfileUpdateRequest;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.teams.TeamSummary;
import de.mhus.vance.brain.access.WebUiCookieService;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Settings writes are restricted to the {@code webui.*} prefix
 * plus an explicit allowlist of self-service keys (currently just
 * {@link LanguageResolver.Keys#CHAT_LANGUAGE}). The prefix-plus-allow
 * model keeps the profile from becoming a backdoor into arbitrary
 * user-scope settings (which {@code AdminSettingsController} guards
 * with admin permission), while still letting users pick a personal
 * default assistant language without going through an admin. Anything
 * outside the allowed set → 400.
 */
@RestController
@RequestMapping("/brain/{tenant}/profile")
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

    /**
     * Extra keys (outside the {@code webui.*} prefix) that the profile
     * endpoint accepts as self-service writes. Keep this small and
     * deliberate — every entry here is something the user is trusted
     * to set without admin involvement.
     */
    static final Set<String> ALLOWED_EXTRA_KEYS = Set.of(
            LanguageResolver.Keys.CHAT_LANGUAGE,
            de.mhus.vance.shared.settings.TimezoneResolver.Keys.DISPLAY_TIMEZONE);

    private final UserService userService;
    private final SettingService settingService;
    private final TeamService teamService;
    private final WebUiCookieService webUiCookieService;

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
    public ResponseEntity<ProfileDto> update(
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
                    tenant, username, request.getTitle(), request.getEmail(), null, null);
            log.info("Profile updated tenant='{}' user='{}'", tenant, username);
            return withRefreshedDataCookie(httpRequest, saved,
                    toDto(saved, loadTeams(tenant, username), loadSettings(tenant, username)));
        } catch (UserService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<ProfileDto> setSetting(
            @PathVariable("tenant") String tenant,
            @PathVariable("key") String key,
            @Valid @RequestBody ProfileSettingWriteRequest request,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        requireSelfServiceKey(key);
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
        return withRefreshedDataCookie(httpRequest, user,
                toDto(user, loadTeams(tenant, username), loadSettings(tenant, username)));
    }

    @DeleteMapping("/settings/{key}")
    public ResponseEntity<Void> deleteSetting(
            @PathVariable("tenant") String tenant,
            @PathVariable("key") String key,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        requireSelfServiceKey(key);
        settingService.delete(tenant,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + username,
                key);
        log.info("Profile setting deleted tenant='{}' user='{}' key='{}'",
                tenant, username, key);
        UserDocument user = userService.findByTenantAndName(tenant, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Profile not found for current user"));
        ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
        webUiCookieService.refreshDataCookie(httpRequest, builder, user);
        return builder.build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /**
     * Wrap {@code body} in a 200-response that also carries a freshly
     * minted {@code vance_data} cookie. Profile mutations must update
     * the cookie so subsequent page loads pick up the new settings
     * snapshot instead of re-applying the stale login-time view.
     */
    private ResponseEntity<ProfileDto> withRefreshedDataCookie(
            HttpServletRequest httpRequest, UserDocument user, ProfileDto body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        webUiCookieService.refreshDataCookie(httpRequest, builder, user);
        return builder.body(body);
    }

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }

    private static void requireSelfServiceKey(String key) {
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Profile settings key required");
        }
        if (key.startsWith(WebUiCookies.SETTINGS_PREFIX)) return;
        if (ALLOWED_EXTRA_KEYS.contains(key)) return;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Profile settings keys must start with '"
                        + WebUiCookies.SETTINGS_PREFIX + "' or be in the "
                        + "self-service allowlist (" + ALLOWED_EXTRA_KEYS + ")");
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
        Map<String, String> merged = new LinkedHashMap<>(
                settingService.findUserSettingsByPrefix(
                        tenant, username, WebUiCookies.SETTINGS_PREFIX));
        // Pull each allowlisted self-service key from the user scope
        // explicitly. They live outside the webui.* prefix so the
        // prefix query above misses them; we still want them in the
        // profile DTO so the UI can render the current value.
        for (String key : ALLOWED_EXTRA_KEYS) {
            String v = settingService.getUserStringValue(tenant, username, key);
            if (v != null) merged.put(key, v);
        }
        return merged;
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
