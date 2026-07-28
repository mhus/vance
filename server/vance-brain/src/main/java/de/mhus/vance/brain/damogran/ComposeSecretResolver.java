package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a task's {@code secrets:} map (env-var name → secret reference) to
 * concrete env-var values at run time, reusing the shared {@link SecretResolver}
 * so the full reference grammar ({@code vault:}, {@code project:}, {@code tenant:},
 * {@code user:}, cascade default) works identically to {@code {{secret:…}}}
 * elsewhere.
 *
 * <p>References are bare in the manifest ({@code vault:jira-token}); they are
 * wrapped as {@code {{secret:…}}} to feed the resolver. The scope comes from the
 * {@link DamogranContext}: tenant/project/process directly, and the user only
 * when the run's {@code caller} is a real {@link SubjectType#USER} — a headless
 * ({@code _damogran}) or SYSTEM run has no user layer, so {@code user:} refs
 * resolve empty and fall through to project/tenant.
 *
 * <p>Unresolvable references are dropped (not injected as a blank env var) with a
 * warning; the dependent command then fails visibly rather than silently running
 * with an empty credential.
 */
@Component
@Slf4j
class ComposeSecretResolver {

    private final SecretResolver secretResolver;

    ComposeSecretResolver(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * @return env-var name → resolved value for every reference that resolved to
     *         a non-empty value; iteration order follows the manifest
     */
    Map<String, String> resolve(Map<String, String> secrets, DamogranContext ctx) {
        if (secrets.isEmpty()) {
            return Map.of();
        }
        ToolInvocationContext tc = toolContext(ctx);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            String envName = e.getKey();
            String ref = e.getValue();
            String resolved = secretResolver.resolve("{{secret:" + ref + "}}", tc);
            if (resolved == null || resolved.isEmpty()) {
                log.warn("compose secrets: '{}' -> '{}' resolved to empty "
                                + "(tenant='{}', project='{}') — not injecting",
                        envName, ref, ctx.tenantId(), ctx.projectId());
                continue;
            }
            out.put(envName, resolved);
        }
        return out;
    }

    private static ToolInvocationContext toolContext(DamogranContext ctx) {
        String userId = userIdOf(ctx.caller());
        return new ToolInvocationContext(
                ctx.tenantId(), ctx.projectId(), null, ctx.processId(), userId);
    }

    private static @Nullable String userIdOf(@Nullable SecurityContext caller) {
        return (caller != null && caller.subjectType() == SubjectType.USER)
                ? caller.subjectId() : null;
    }
}
