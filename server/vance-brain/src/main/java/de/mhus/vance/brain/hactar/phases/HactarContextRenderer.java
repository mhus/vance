package de.mhus.vance.brain.hactar.phases;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared prompt-context renderer for Hactar's FRAMING and
 * DRAFTING phases. Centralises:
 *
 * <ul>
 *   <li>Tool inventory — resolves {@code engineParams.scriptAllowedTools}
 *       against the live {@link ToolDispatcher} and formats each entry
 *       as Markdown with name/description/Required-args.</li>
 *   <li>Manual inventory — enumerates the document cascade under
 *       {@code engineParams.manualPaths} plus any skill-supplied paths
 *       (recipe-first precedence).</li>
 *   <li>Skill guidance — concatenates {@code promptExtension} and INLINE
 *       {@code referenceDocs} from skills carrying the
 *       {@code script-architect} tag.</li>
 *   <li>Skill resolution — looks up active skills filtered to that
 *       same tag; no trigger fires (FRAMING/DRAFTING have no user-input
 *       string to match against).</li>
 * </ul>
 *
 * <p>Phases pass {@code architectSkills} between renderers so the
 * SkillResolver round-trip happens at most once per turn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarContextRenderer {

    /** Engine-param key for the script's allowed tool surface. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY = "scriptAllowedTools";

    /** Engine-param key for the manual-folder list. */
    public static final String MANUAL_PATHS_KEY = "manualPaths";

    /** Tag a skill must carry to participate in DRAFTING/FRAMING. */
    public static final String SCRIPT_ARCHITECT_TAG = "script-architect";

    private final ToolDispatcher toolDispatcher;
    private final DocumentService documentService;
    private final SkillResolver skillResolver;
    private final SessionService sessionService;

    // ──────────────────── Tool inventory ────────────────────

    /**
     * Renders the {@code scriptAllowedTools} list as a Markdown
     * inventory. Returns "" when no tools are configured — the Pebble
     * template's {@code {% if toolInventory %}} gates the section.
     */
    public String renderToolInventory(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        List<String> wanted = scriptAllowedTools(process);
        if (wanted.isEmpty()) return "";

        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                /*userId*/ null);

        StringBuilder sb = new StringBuilder();
        for (String name : wanted) {
            ToolDispatcher.Resolved resolved =
                    toolDispatcher.resolve(name, scope).orElse(null);
            if (resolved == null) {
                sb.append("- **").append(name).append("** — ")
                        .append("(tool not registered in this scope)\n");
                continue;
            }
            Tool tool = resolved.tool();
            sb.append("- **").append(tool.name()).append("** — ")
                    .append(oneLine(tool.description())).append("\n");
            String args = describeParams(tool.paramsSchema());
            if (!args.isEmpty()) {
                sb.append("  ").append(args).append("\n");
            }
        }
        return sb.toString();
    }

    public static List<String> scriptAllowedTools(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_ALLOWED_TOOLS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String describeParams(@Nullable Map<String, Object> schema) {
        if (schema == null) return "";
        Object propsRaw = schema.get("properties");
        if (!(propsRaw instanceof Map<?, ?> props) || props.isEmpty()) return "";
        Object reqRaw = schema.get("required");
        List<String> required = reqRaw instanceof List<?> rl
                ? rl.stream().filter(o -> o instanceof String)
                    .map(o -> (String) o).toList()
                : List.of();
        List<String> requiredOut = new ArrayList<>(required);
        List<String> optionalOut = new ArrayList<>();
        for (Map.Entry<?, ?> e : props.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (required.contains(key)) continue;
            optionalOut.add(key);
        }
        StringBuilder sb = new StringBuilder();
        if (!requiredOut.isEmpty()) {
            sb.append("Required: ").append(String.join(", ", requiredOut)).append(".");
        }
        if (!optionalOut.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Optional: ").append(String.join(", ", optionalOut)).append(".");
        }
        return sb.toString();
    }

    private static String oneLine(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 197) + "..." : s;
    }

    // ──────────────────── Manual inventory ────────────────────

    /**
     * Lists Markdown manuals reachable from the configured folders
     * (recipe-paths + script-architect skill paths, recipe-first
     * precedence). Listing only — content stays out of the prompt.
     */
    public String renderManualInventory(
            ThinkProcessDocument process, List<ResolvedSkill> architectSkills) {
        LinkedHashSet<String> folders = new LinkedHashSet<>(manualPaths(process));
        for (ResolvedSkill skill : architectSkills) {
            if (skill.manualPaths() == null) continue;
            for (String p : skill.manualPaths()) {
                if (p == null || p.isBlank()) continue;
                String norm = p.trim();
                if (!norm.endsWith("/")) norm = norm + "/";
                folders.add(norm);
            }
        }
        if (folders.isEmpty()) return "";
        if (process.getTenantId() == null || process.getTenantId().isBlank()) {
            return "";
        }

        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String folder : folders) {
            Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                    process.getTenantId(), process.getProjectId(), folder);
            List<String> paths = new ArrayList<>(hits.keySet());
            paths.sort(String::compareTo);
            for (String path : paths) {
                if (!path.endsWith(".md")) continue;
                String filename = path.substring(folder.length());
                String stem = filename.substring(
                        0, filename.length() - ".md".length());
                if (stem.isBlank()) continue;
                if (!seen.add(stem)) continue;
                LookupResult result = hits.get(path);
                sb.append("- **").append(stem).append("** ")
                        .append("(folder: ").append(folder)
                        .append(", source: ")
                        .append(result.source().name().toLowerCase())
                        .append(")\n");
            }
        }
        return sb.toString();
    }

    public static List<String> manualPaths(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(MANUAL_PATHS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String s) || s.isBlank()) continue;
            String norm = s.trim();
            if (!norm.endsWith("/")) norm = norm + "/";
            out.add(norm);
        }
        return out;
    }

    // ──────────────────── Skills ────────────────────

    /**
     * Resolves active skills filtered to those carrying the
     * {@link #SCRIPT_ARCHITECT_TAG} tag. Engine-driven push: no
     * trigger matching, only recipe-bound or out-of-band-activated
     * skills reach this list. Unknown references are logged + skipped.
     */
    public List<ResolvedSkill> resolveScriptArchitectSkills(
            ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return List.of();
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref == null || ref.getName() == null) continue;
            try {
                Optional<ResolvedSkill> resolved =
                        skillResolver.resolve(scope, ref.getName());
                if (resolved.isEmpty()) {
                    log.warn("Hactar id='{}' active skill '{}' no longer "
                                    + "resolves — skipping",
                            process.getId(), ref.getName());
                    continue;
                }
                ResolvedSkill skill = resolved.get();
                if (skill.tags() != null
                        && skill.tags().contains(SCRIPT_ARCHITECT_TAG)) {
                    out.add(skill);
                }
            } catch (UnknownSkillException e) {
                log.warn("Hactar id='{}' active skill '{}' unknown — skipping",
                        process.getId(), ref.getName());
            }
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = process.getSessionId() == null
                ? null
                : sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null && session.getUserId() != null
                && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && session.getProjectId() != null
                && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    // ──────────────────── Skill guidance ────────────────────

    /**
     * Builds the Markdown body for the prompt's
     * {@code {{ skillGuidance }}} variable. Concatenates each matching
     * skill's {@code promptExtension} plus its INLINE
     * {@code referenceDocs}. ON_DEMAND treated as INLINE in v1 (same
     * as {@code SkillPromptComposer} on the running-LLM side).
     */
    public String renderSkillGuidance(List<ResolvedSkill> architectSkills) {
        if (architectSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedSkill skill : architectSkills) {
            String prompt = skill.promptExtension();
            boolean hasPrompt = prompt != null && !prompt.isBlank();
            boolean hasDocs = skill.referenceDocs() != null
                    && !skill.referenceDocs().isEmpty();
            if (!hasPrompt && !hasDocs) continue;
            sb.append("### ").append(skill.title() == null
                    ? skill.name() : skill.title()).append('\n');
            if (hasPrompt) {
                sb.append(prompt.trim()).append('\n');
            }
            if (hasDocs) {
                for (ResolvedSkill.ReferenceDoc doc : skill.referenceDocs()) {
                    if (doc.loadMode() == SkillReferenceDocLoadMode.INLINE
                            || doc.loadMode() == SkillReferenceDocLoadMode.ON_DEMAND) {
                        sb.append("\n#### ").append(doc.title()).append('\n');
                        if (doc.content() != null && !doc.content().isBlank()) {
                            sb.append(doc.content().trim()).append('\n');
                        }
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ──────────────────── Shared param-read helpers ────────────────────

    public static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> p = process.getEngineParams();
        Object v = p == null ? null : p.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
