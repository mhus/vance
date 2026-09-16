package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;

/**
 * Shared base for the {@code wowbagger_*} self-steering tools: role-gated
 * (only the Wowbagger engine sees them), each operating on the calling
 * process's run in the {@link WowbaggerPoolService}.
 */
abstract class WowbaggerBaseTool implements Tool {

    @Override
    public java.util.Set<String> requiresEngineRoles() {
        return java.util.Set.of(WowbaggerEngine.ROLE);
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        return false;
    }

    static ThinkProcessDocument process(ThinkProcessService thinkProcessService, ToolInvocationContext ctx) {
        return thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("wowbagger: process '" + ctx.processId() + "' not found"));
    }

    static String stringParam(Map<String, Object> params, String key, boolean required) throws ToolException {
        Object v = params == null ? null : params.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        if (required) {
            throw new ToolException("'" + key + "' is required");
        }
        return null;
    }

    static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    static boolean booleanParam(Map<String, Object> params, String key, boolean fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }
}
