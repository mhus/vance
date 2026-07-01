package de.mhus.vance.brain.uistate;

import de.mhus.vance.api.uistate.SessionGroupsUiStateDto;
import de.mhus.vance.api.uistate.SidebarUiStateDto;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-service per-user Web-UI state endpoint. Holds purely cosmetic
 * preferences that the client wants to persist across sessions and
 * devices — currently only which sidebar project-groups are
 * collapsed in the shared {@code ProjectListSidebar} component.
 *
 * <p>Authorisation model matches {@code ProfileController}: <b>only
 * the caller's own state</b>. No username path parameter; the subject
 * is resolved from the authenticated JWT claim, so a user can never
 * read or overwrite someone else's UI state via this surface.
 *
 * <p>Storage lives on the per-user {@code _user_<login>} system
 * project under {@code webui.*} setting keys (same physical layer as
 * {@code ProfileController} writes to). The list of collapsed group
 * names is serialised as a JSON array string at the setting boundary
 * so the on-the-wire DTO stays typed.
 */
@RestController
@RequestMapping("/brain/{tenant}/me/ui-state")
@RequiredArgsConstructor
@Slf4j
public class MeUiStateController {

    static final String KEY_SIDEBAR_COLLAPSED_GROUPS =
            "webui.sidebar.collapsedProjectGroups";

    static final String KEY_SESSION_GROUPS_COLLAPSED =
            "webui.sessionGroups.collapsed";

    private final SettingService settingService;
    private final ObjectMapper objectMapper;

    @GetMapping("/sidebar")
    public SidebarUiStateDto getSidebar(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        String raw = settingService.getUserStringValue(
                tenant, username, KEY_SIDEBAR_COLLAPSED_GROUPS);
        return SidebarUiStateDto.builder()
                .collapsedProjectGroups(parseGroupList(raw))
                .build();
    }

    @PutMapping("/sidebar")
    public SidebarUiStateDto putSidebar(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody SidebarUiStateDto request,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        List<String> normalised = normalise(request.getCollapsedProjectGroups());
        String value;
        try {
            value = objectMapper.writeValueAsString(normalised);
        } catch (JacksonException e) {
            // The list contains only strings supplied by the caller — Jackson
            // serialising a List<String> to JSON should never fail. If it
            // does, it's a server bug, not a client bug.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialise sidebar UI state", e);
        }
        settingService.setStringValue(
                tenant,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + username,
                KEY_SIDEBAR_COLLAPSED_GROUPS,
                value);
        return SidebarUiStateDto.builder()
                .collapsedProjectGroups(normalised)
                .build();
    }

    @GetMapping("/session-groups")
    public SessionGroupsUiStateDto getSessionGroups(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        String raw = settingService.getUserStringValue(
                tenant, username, KEY_SESSION_GROUPS_COLLAPSED);
        return SessionGroupsUiStateDto.builder()
                .collapsedKeys(parseGroupList(raw))
                .build();
    }

    @PutMapping("/session-groups")
    public SessionGroupsUiStateDto putSessionGroups(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody SessionGroupsUiStateDto request,
            HttpServletRequest httpRequest) {
        String username = currentUser(httpRequest);
        List<String> normalised = normalise(request.getCollapsedKeys());
        String value;
        try {
            value = objectMapper.writeValueAsString(normalised);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialise session-group UI state", e);
        }
        settingService.setStringValue(
                tenant,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + username,
                KEY_SESSION_GROUPS_COLLAPSED,
                value);
        return SessionGroupsUiStateDto.builder()
                .collapsedKeys(normalised)
                .build();
    }

    private List<String> parseGroupList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            return normalise(parsed);
        } catch (JacksonException e) {
            // Stored value got corrupted (manual mongo edit, format change).
            // Don't fail the GET — just behave as if nothing was stored, so
            // the user can recover by toggling groups again.
            log.warn("Failed to parse stored sidebar UI state: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Drop nulls/blanks and de-duplicate while preserving caller order. */
    private static List<String> normalise(List<String> in) {
        if (in == null || in.isEmpty()) return new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>(in.size());
        for (String s : in) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            seen.add(trimmed);
        }
        return new ArrayList<>(seen);
    }

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }
}
