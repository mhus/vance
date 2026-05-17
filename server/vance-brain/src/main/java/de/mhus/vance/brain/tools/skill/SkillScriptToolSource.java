package de.mhus.vance.brain.tools.skill;

import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Process-scoped {@link ToolSource} that yields one virtual
 * {@link SkillScriptTool} per script entry on the active skills of
 * the calling process. Connected to
 * {@code specification/skills.md} §13 Phase 2 — Tool-Mount.
 *
 * <p>The source reads the {@link ToolInvocationContext#processId()} to
 * look up the process row, walks its {@code activeSkills}, and for
 * each skill calls {@link SkillResolver#resolve} to find the
 * cascade-tier-winning {@link ResolvedSkill}. Each resolved skill's
 * {@code scripts} contributes one tool. Stateless per call —
 * matches the existing {@link ToolSource} contract.
 *
 * <p>Identity is taken strictly from the {@code ctx}; the process
 * cannot influence the resolver via tool params. If a process is
 * not present (top-level run without a row, system spawn, …) the
 * source yields nothing — never fails.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillScriptToolSource implements ToolSource {

    public static final String SOURCE_ID = "skill-script";

    private final ThinkProcessService thinkProcessService;
    private final SkillResolver skillResolver;
    private final ScriptExecutor scriptExecutor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<Tool> tools(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) {
            return List.of();
        }
        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElse(null);
        if (process == null || process.getActiveSkills() == null
                || process.getActiveSkills().isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = SkillScopeContext.of(
                ctx.tenantId(), ctx.userId(), ctx.projectId());
        List<Tool> out = new ArrayList<>();
        for (ActiveSkillRefEmbedded active : process.getActiveSkills()) {
            String name = active.getName();
            if (name == null || name.isBlank()) continue;
            Optional<ResolvedSkill> resolved = safeResolve(scope, name);
            if (resolved.isEmpty()) continue;
            for (ResolvedSkill.Script s : resolved.get().scripts()) {
                out.add(new SkillScriptTool(resolved.get().name(), s, scriptExecutor));
            }
        }
        return out;
    }

    @Override
    public Optional<Tool> find(String name, ToolInvocationContext ctx) {
        // Fast path: only attempt resolution for names that look like a
        // skill-script tool (`skill_<skill>__<script>`). Anything else
        // can't possibly match, so we save the process / resolver
        // round-trip for the dispatcher's name-resolution cascade.
        if (name == null || !name.startsWith(SkillScriptTool.NAME_PREFIX)) {
            return Optional.empty();
        }
        int sep = name.indexOf(SkillScriptTool.NAME_SEPARATOR,
                SkillScriptTool.NAME_PREFIX.length());
        if (sep <= SkillScriptTool.NAME_PREFIX.length()) {
            return Optional.empty();
        }
        // Fall through to the full listing — it's already cheap for
        // a process with a small active-skills set, and avoids
        // re-implementing the resolution path twice.
        return tools(ctx).stream()
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    private Optional<ResolvedSkill> safeResolve(SkillScopeContext scope, String name) {
        try {
            return skillResolver.resolve(scope, name);
        } catch (RuntimeException e) {
            log.warn("SkillScriptToolSource: resolve failed for skill '{}' "
                            + "(tenant={}, project={}): {}",
                    name, scope.tenantId(), scope.projectId(), e.toString());
            return Optional.empty();
        }
    }
}
