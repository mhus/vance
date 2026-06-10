package de.mhus.vance.brain.tools.fenchurch;

import de.mhus.vance.brain.fenchurch.FenchurchStyleService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code image_style_set} tool — writes a style-prefix value into
 * one of the four Fenchurch scopes. Default scope is {@code session}
 * (lowest spillover risk); broader scopes trip the SettingService
 * permission check and surface a typed error so the LLM can fall
 * back to a narrower scope or ask the user.
 *
 * <p>The sentinel value {@code __none__} suppresses every outer style
 * layer when read through {@link FenchurchStyleService#mergedPrompt}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageStyleSetTool implements Tool {

    private final FenchurchStyleService styleService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prefix", Map.of(
                            "type", "string",
                            "description",
                                    "Style prefix to set. Non-blank, max 500 "
                                            + "characters. Use the literal "
                                            + "string '__none__' to suppress "
                                            + "every outer style layer for this "
                                            + "scope."),
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("session", "project", "user", "tenant"),
                            "description",
                                    "Cascade scope to write into. Defaults to "
                                            + "'session' — persistent only for "
                                            + "the current chat. Use 'user' for "
                                            + "the calling user's persona, "
                                            + "'project' for a project-wide "
                                            + "preference, 'tenant' only if the "
                                            + "user is an administrator.")),
            "required", List.of("prefix"));

    @Override public String name() { return "image_style_set"; }

    @Override
    public String description() {
        return "Persist a Fenchurch image-generation style prefix in one of "
                + "four scopes (session, project, user, tenant). The merged "
                + "prefix from all active scopes is prepended to every "
                + "image_generate prompt — additive, not replacing. Useful "
                + "when the user says 'all my images should be watercolour' "
                + "or 'this project is medieval'. Default scope is `session`; "
                + "ask the user before writing to a broader scope.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_style_set requires a tenant scope");
        }
        String prefix = readRequiredString(params, "prefix");
        FenchurchStyleService.Scope scope = parseScope(readString(params, "scope"));

        try {
            styleService.writeScope(
                    ctx.tenantId(), scope, prefix,
                    ctx.userId(), ctx.projectId(), ctx.processId());
        } catch (IllegalArgumentException e) {
            return error("invalid_argument", e.getMessage(), false);
        } catch (RuntimeException e) {
            log.info("image_style_set permission/IO failure on scope {}: {}",
                    scope, e.toString());
            return error("permission_denied", e.getMessage(), false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope.name().toLowerCase(Locale.ROOT));
        out.put("prefix", prefix);
        return out;
    }

    private static FenchurchStyleService.Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return FenchurchStyleService.Scope.SESSION;
        }
        try {
            return FenchurchStyleService.Scope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException(
                    "'scope' must be one of session/project/user/tenant — got '"
                            + raw + "'");
        }
    }

    private static String readRequiredString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    private static String readString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    private static Map<String, Object> error(String code, String msg, boolean retryable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("message", msg);
        out.put("retryable", retryable);
        return out;
    }
}
