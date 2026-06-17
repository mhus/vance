package de.mhus.vance.brain.settings;

import de.mhus.vance.shared.settings.SettingService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only access to scope-cascaded settings for the Web-UI. Used
 * when the SPA needs an infrastructure-style value that depends on
 * the current project (project → {@code _vance} tenant default),
 * not on the user.
 *
 * <p>The data cookie covers user-scoped preferences ({@code webui.*}
 * + the {@code EXTRA_COOKIE_SETTING_KEYS} allowlist). Project-scoped
 * settings <b>cannot</b> live there because the cookie is minted at
 * login and the SPA may switch projects without re-login.
 *
 * <p>{@link #PUBLIC_CASCADE_KEYS} is a hard allowlist — anything else
 * returns 400. This prevents the endpoint from becoming a generic
 * read-channel that leaks credential settings or other sensitive
 * tenant configuration. Add new entries explicitly when a new
 * Web-UI feature needs project-scoped config.
 */
@RestController
@RequestMapping("/brain/{tenant}/settings/cascade")
@RequiredArgsConstructor
@Slf4j
public class SettingsCascadeController {

    /**
     * Keys the SPA is allowed to read via this endpoint. Add a key
     * here when a new Web-UI feature needs project-scoped infra
     * config; never expose generic Setting prefixes. Password
     * settings are filtered separately by
     * {@link SettingService#getStringValueCascade} (which skips them
     * by design), but keeping this allowlist tight is the primary
     * defense-in-depth.
     */
    private static final Set<String> PUBLIC_CASCADE_KEYS = Set.of(
            "maps.tile.url",
            "maps.tile.attribution");

    private final SettingService settingService;

    /**
     * Resolve the requested keys for the given project scope. Keys
     * that resolve to {@code null} (nothing set anywhere in the
     * cascade) are omitted from the response — the SPA falls back
     * to its own hard-coded defaults.
     *
     * <p>{@code projectId} is optional; when absent the cascade
     * starts at the {@code _vance} tenant default directly.
     */
    @GetMapping
    public Map<String, String> resolve(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam("key") List<String> keys) {
        if (keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one 'key' parameter is required");
        }
        for (String key : keys) {
            if (!PUBLIC_CASCADE_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Key not exposed via cascade endpoint: " + key);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String v = settingService.getStringValueCascade(tenant, projectId, null, key);
            if (v != null) out.put(key, v);
        }
        return out;
    }
}
