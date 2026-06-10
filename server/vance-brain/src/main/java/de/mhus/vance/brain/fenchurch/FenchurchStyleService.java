package de.mhus.vance.brain.fenchurch;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Style-prefix layer service for Fenchurch.
 *
 * <p>Concatenative cascade over four scopes (outer → inner):
 * {@code tenant → user → project → session}. Each scope stores its
 * own value under the setting key {@code ai.fenchurch.style_prefix}.
 * The merged prompt joins every non-blank layer with {@code ", "} —
 * users compose styles additively without losing outer context.
 *
 * <p><b>None-Marker:</b> The sentinel value {@value #NONE_MARKER} in
 * any scope suppresses all <i>outer</i> layers. Inner layers (closer
 * to the session) are still applied. The sentinel itself never
 * appears in the merged output. Use case: a project that wants to
 * start from a clean slate ignoring tenant defaults.
 *
 * <p>Scope mapping to {@link SettingService}:
 * <ul>
 *   <li>{@code tenant} → {@code SCOPE_PROJECT} with project
 *       {@code _tenant}</li>
 *   <li>{@code user} → {@code SCOPE_PROJECT} with project
 *       {@code _user_<userId>}</li>
 *   <li>{@code project} → {@code SCOPE_PROJECT} with the actual
 *       project id</li>
 *   <li>{@code session} → {@code SCOPE_THINK_PROCESS} with the
 *       process id</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FenchurchStyleService {

    public static final String SETTING_KEY = "ai.fenchurch.style_prefix";

    /** Sentinel that suppresses all outer scopes when present in a layer. */
    public static final String NONE_MARKER = "__none__";

    /** Logical scope identifier, used by the {@code image_style_*} tools. */
    public enum Scope {
        TENANT, USER, PROJECT, SESSION
    }

    /** One resolved layer in the cascade — used by callers that want
     *  to introspect "which scope contributes what". */
    public record Layer(Scope scope, String prefix) {}

    private final SettingService settingService;

    /**
     * Read all four layers as they sit in the cascade — including
     * the {@value #NONE_MARKER} sentinel. Layers with no value are
     * omitted. {@code userId} / {@code projectId} / {@code processId}
     * may be {@code null} — the corresponding layer is then absent.
     *
     * <p>Output order is outer → inner (tenant first, session last).
     */
    public List<Layer> readLayers(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        List<Layer> layers = new ArrayList<>();
        addIfPresent(layers, Scope.TENANT, readTenant(tenantId));
        if (userId != null && !userId.isBlank()) {
            addIfPresent(layers, Scope.USER, readUser(tenantId, userId));
        }
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            addIfPresent(layers, Scope.PROJECT, readProject(tenantId, projectId));
        }
        if (processId != null && !processId.isBlank()) {
            addIfPresent(layers, Scope.SESSION, readSession(tenantId, processId));
        }
        return layers;
    }

    /**
     * Compose the effective style prefix from the cascade by joining
     * non-blank layers with {@code ", "}. A {@value #NONE_MARKER}
     * entry truncates the cascade: every layer further out (i.e.
     * appearing before the marker) is dropped, the marker itself is
     * removed, and inner layers are appended verbatim.
     *
     * <p>Returns the empty string when no layer carries a value.
     */
    public String composeMergedPrompt(List<Layer> layers) {
        List<Layer> effective = applyNoneCutoff(layers);
        StringBuilder out = new StringBuilder();
        for (Layer l : effective) {
            String p = l.prefix();
            if (p == null || p.isBlank()) continue;
            if (NONE_MARKER.equals(p.trim())) continue;
            if (!out.isEmpty()) out.append(", ");
            out.append(p.trim());
        }
        return out.toString();
    }

    /**
     * Convenience entry point used by {@link FenchurchService}:
     * read + compose in one call. See
     * {@link #composeMergedPrompt(List)} for the formatting contract.
     */
    public String mergedPrompt(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        return composeMergedPrompt(readLayers(tenantId, userId, projectId, processId));
    }

    /**
     * Read a single scope's stored prefix verbatim ({@value #NONE_MARKER}
     * included). Returns {@code null} when nothing is stored for the
     * scope — caller distinguishes "explicit empty" (rejected at write
     * time) from "never set".
     */
    public @Nullable String readScope(
            String tenantId,
            Scope scope,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        return switch (scope) {
            case TENANT -> readTenant(tenantId);
            case USER -> userId == null || userId.isBlank()
                    ? null : readUser(tenantId, userId);
            case PROJECT -> projectId == null || projectId.isBlank()
                    ? null : readProject(tenantId, projectId);
            case SESSION -> processId == null || processId.isBlank()
                    ? null : readSession(tenantId, processId);
        };
    }

    /**
     * Write the {@value SETTING_KEY} value into one scope. {@code prefix}
     * may be {@value #NONE_MARKER} (the suppression sentinel) or any
     * non-blank string up to 500 characters. {@code null} or blank is
     * rejected — clearing a layer goes through a dedicated method
     * (not implemented in v1).
     *
     * @throws IllegalArgumentException on blank input, oversize prefix,
     *         or missing scope-id (e.g. {@code Scope.USER} with
     *         {@code userId == null})
     */
    public void writeScope(
            String tenantId,
            Scope scope,
            String prefix,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is blank");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException(
                    "style prefix must not be blank — use a non-empty string or '"
                            + NONE_MARKER + "' to suppress outer layers");
        }
        if (prefix.length() > 500) {
            throw new IllegalArgumentException(
                    "style prefix exceeds 500-character limit ("
                            + prefix.length() + ")");
        }
        switch (scope) {
            case TENANT -> writeTenant(tenantId, prefix);
            case USER -> {
                if (userId == null || userId.isBlank()) {
                    throw new IllegalArgumentException(
                            "userId required for USER scope");
                }
                writeUser(tenantId, userId, prefix);
            }
            case PROJECT -> {
                if (projectId == null || projectId.isBlank()) {
                    throw new IllegalArgumentException(
                            "projectId required for PROJECT scope");
                }
                writeProject(tenantId, projectId, prefix);
            }
            case SESSION -> {
                if (processId == null || processId.isBlank()) {
                    throw new IllegalArgumentException(
                            "processId required for SESSION scope");
                }
                writeSession(tenantId, processId, prefix);
            }
        }
    }

    // ──────────────────── Internals ────────────────────

    /**
     * Compute the effective layer list after applying the
     * {@value #NONE_MARKER} cutoff: the <i>innermost</i> marker wins
     * (if the project says "__none__" and the user also says "__none__",
     * the project's marker is what truncates the cascade).
     */
    public static List<Layer> applyNoneCutoff(List<Layer> layers) {
        int cutoff = -1;
        for (int i = layers.size() - 1; i >= 0; i--) {
            String p = layers.get(i).prefix();
            if (p != null && NONE_MARKER.equals(p.trim())) {
                cutoff = i;
                break;
            }
        }
        if (cutoff < 0) {
            return layers;
        }
        return layers.subList(cutoff + 1, layers.size());
    }

    private @Nullable String readTenant(String tenantId) {
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, SETTING_KEY);
    }

    private @Nullable String readUser(String tenantId, String userId) {
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, SETTING_KEY);
    }

    private @Nullable String readProject(String tenantId, String projectId) {
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT, projectId, SETTING_KEY);
    }

    private @Nullable String readSession(String tenantId, String processId) {
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_THINK_PROCESS, processId, SETTING_KEY);
    }

    private void writeTenant(String tenantId, String prefix) {
        settingService.setStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, SETTING_KEY, prefix);
    }

    private void writeUser(String tenantId, String userId, String prefix) {
        settingService.setStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId,
                SETTING_KEY, prefix);
    }

    private void writeProject(String tenantId, String projectId, String prefix) {
        settingService.setStringValue(
                tenantId, SettingService.SCOPE_PROJECT, projectId,
                SETTING_KEY, prefix);
    }

    private void writeSession(String tenantId, String processId, String prefix) {
        settingService.setStringValue(
                tenantId, SettingService.SCOPE_THINK_PROCESS, processId,
                SETTING_KEY, prefix);
    }

    private static void addIfPresent(
            List<Layer> layers, Scope scope, @Nullable String value) {
        if (value == null || value.isBlank()) return;
        layers.add(new Layer(scope, value));
    }
}
