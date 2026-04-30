package de.mhus.vance.brain.tools.manual;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code params.manualPaths} from the running process — the
 * recipe-configured folder list that {@link ManualListTool} and
 * {@link ManualReadTool} union over. Empty / missing list yields
 * an empty result and the calling tool surfaces "no manuals
 * configured" rather than fabricating a default.
 *
 * <p>Path normalisation: all entries are forced to end with
 * {@code "/"} (folder semantics) and de-duplicated while preserving
 * order — first occurrence wins on later cascade merges, so the
 * recipe author controls precedence by listing more-specific paths
 * first.
 */
final class ManualPaths {

    static final String PARAM_KEY = "manualPaths";

    private ManualPaths() {}

    /**
     * Reads the path list for {@code ctx}. Requires {@code processId}
     * — manuals are always configured per running process, never at
     * tenant scope.
     */
    @SuppressWarnings("unchecked")
    static List<String> readFor(
            ToolInvocationContext ctx,
            ThinkProcessService thinkProcessService) {
        if (ctx == null || ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("manual tools require a process scope");
        }
        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElseThrow(() -> new ToolException(
                        "Process " + ctx.processId() + " not found"));
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return List.of();
        Object raw = params.get(PARAM_KEY);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("'" + PARAM_KEY
                    + "' on process must be a list of folder paths");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) continue;
            String norm = s.trim();
            if (!norm.endsWith("/")) norm = norm + "/";
            if (seen.add(norm)) out.add(norm);
        }
        return out;
    }
}
