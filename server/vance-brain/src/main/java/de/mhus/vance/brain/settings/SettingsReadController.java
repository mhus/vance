package de.mhus.vance.brain.settings;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read project-scoped settings from a web client — the general read the
 * two-key {@link SettingsCascadeController} peephole deliberately is not.
 *
 * <p>{@code GET /brain/{tenant}/settings/{project}?prefix=…} or
 * {@code ?keys=a,b}. Values come through the ordinary cascade
 * (project → {@code _vance} tenant default), so an app sees the same
 * configuration the server does for its project.
 *
 * <p><b>Why this can be general where the other endpoint could not.</b> The
 * cascade controller's allowlist exists because it was the only guard: it hands
 * back whatever key it is asked for. The protection here is a layer down and
 * cannot be forgotten — {@link SettingService#getStringValue} and
 * {@link SettingService#findByPrefixCascade} both **refuse encrypted types**
 * ({@code PASSWORD} and {@code HIDDEN}, via the {@code encrypted()} predicate).
 * A secret does not become readable by asking for it through a different route;
 * it reads as absent.
 *
 * <p><b>Both encrypted types, and that is the point.</b> A custom app is a
 * <em>dynamic element</em> in the sense of the settings spec — a document
 * anybody with project WRITE can author, running in a browser. {@code HIDDEN}
 * exists so that scripts, compose tasks and agents can resolve a secret
 * server-side; a value handed to a browser is a value that can be rendered into
 * the page and posted anywhere. So this route is on the far side of that line
 * from both types, and it gets there by *not having its own type check* —
 * whoever adds a setting type later cannot forget this call site.
 *
 * <p><b>Absent and secret look the same here, on purpose.</b> Everywhere else in
 * this codebase the distinction is worth spelling out — an unwritten state key
 * is not an empty list, a missing recipe is not a broken one. Not here:
 * answering "that key exists but you may not have it" confirms a piece of the
 * tenant's configuration, and confirming it is itself the small leak. So an
 * encrypted setting is reported the same way as one nobody ever set.
 *
 * <p>Read only. Writing is a separate question with a separate answer — the
 * setting-form route already writes, and it enforces {@code Setting ADMIN} per
 * scope through a form document that declares which keys it binds.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SettingsReadController {

    /**
     * How many keys one call may name.
     *
     * <p>Not a security limit — the service refuses secrets regardless. It keeps
     * a single request from turning into a scan of the whole settings
     * collection, which is a cost question rather than a safety one.
     */
    private static final int MAX_KEYS = 50;

    private final SettingService settingService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/settings/{project}")
    public Map<String, String> read(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(name = "keys", required = false) @Nullable String keys,
            @RequestParam(name = "prefix", required = false) @Nullable String prefix,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        boolean hasKeys = keys != null && !keys.isBlank();
        boolean hasPrefix = prefix != null && !prefix.isBlank();
        if (hasKeys == hasPrefix) {
            // Both, or neither. "Neither" would be "give me everything", which
            // is a scan; "both" has no obvious meaning and guessing one would
            // make the answer depend on which the reader believed won.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name either 'keys' (comma-separated) or 'prefix' — exactly one.");
        }

        if (hasPrefix) {
            // Already cascade-merged and already skipping encrypted types.
            return settingService.findByPrefixCascade(tenant, project, null, prefix.trim());
        }

        String[] wanted = keys.split(",");
        if (wanted.length > MAX_KEYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many keys (" + wanted.length + "); the maximum is " + MAX_KEYS
                            + ". Use 'prefix' for a family of keys.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : wanted) {
            String key = raw.trim();
            if (key.isEmpty()) continue;
            String value = settingService.getStringValueCascade(tenant, project, null, key);
            // Omitted rather than nulled: a caller iterating the answer should
            // not have to distinguish "present as null" from "absent", and the
            // JSON is smaller for the common case of asking optimistically.
            if (value != null) out.put(key, value);
        }
        return out;
    }
}
