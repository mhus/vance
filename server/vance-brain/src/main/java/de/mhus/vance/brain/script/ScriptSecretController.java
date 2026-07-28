package de.mhus.vance.brain.script;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.vault.ScriptSecretAccumulator;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
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
 * REST surface backing {@code vance.secret(ref)} for out-of-process scripts
 * (Python via {@code vance.py}). JS runs resolve in-JVM and never hit this.
 *
 * <p>Only reachable with a {@code SCRIPT_RUN} token (the {@code BrainAccessFilter}
 * validates it and confirms the run is still RUNNING). Scope — tenant / project /
 * user — comes from the token claims, never from a script-supplied value; the
 * resolver enforces the reference's scope on top of a project-{@code READ} gate.
 * Resolved values are recorded per run so {@code ExecJobRenderer} can mask them
 * out of the run's stdout ({@link ScriptSecretAccumulator}).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ScriptSecretController {

    private final SecretResolver secretResolver;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/script/secret")
    public Map<String, Object> getSecret(
            @PathVariable("tenant") String tenant,
            @RequestParam("ref") String ref,
            HttpServletRequest httpRequest) {
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ref must not be blank");
        }
        VanceJwtClaims claims =
                (VanceJwtClaims) httpRequest.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        if (claims == null || claims.runId() == null || claims.projectId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "script secret access requires a SCRIPT_RUN token");
        }
        // Same project-READ gate as any project read; the token subject must hold it.
        authority.enforce(httpRequest, new Resource.Project(tenant, claims.projectId()), Action.READ);

        ToolInvocationContext scope = new ToolInvocationContext(
                claims.tenantId(), claims.projectId(), claims.sessionId(), null, claims.username());
        String wrapped = "{{secret:" + ref + "}}";
        String resolved = secretResolver.resolve(wrapped, scope);
        // resolved.equals(wrapped) == no substitution (unbound / non-matching ref).
        @Nullable String value =
                (resolved == null || resolved.isEmpty() || resolved.equals(wrapped)) ? null : resolved;
        if (value != null) {
            ScriptSecretAccumulator.record(claims.runId(), value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        return out;
    }
}
