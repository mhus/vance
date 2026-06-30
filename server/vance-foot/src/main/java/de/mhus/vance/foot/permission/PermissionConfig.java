package de.mhus.vance.foot.permission;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Raw, file-bound shape of {@code ~/.vance/permissions.yaml}. Mutable
 * POJO so Jackson's relaxed binding (and the "always" writer) can fill
 * and append fields directly. Compiled into an immutable
 * {@link PermissionPolicy} by {@link PermissionPolicy#compile}.
 *
 * <pre>
 * permissions:
 *   sandbox: true
 *   paths:
 *     deny:  ["~/.ssh/**", "/etc/**"]
 *     allow: ["~/projects/**", "./**"]
 *   commands:
 *     deny:  ["^\\s*rm\\s+-rf\\s+/"]
 *     allow: ["^git( |$)", "^ls( |$)"]
 * </pre>
 *
 * <p>The YAML document has a single top-level {@code permissions:} key
 * which maps to this object (see {@link PermissionConfigLoader}).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionConfig {

    /**
     * Master switch. {@code null} = not specified (defaults to on at the
     * central scope). {@code false} ≙ {@code --no-sandbox} (everything
     * allowed) — only meaningful in the central file; a local file may
     * only set {@code true} (tighten), never {@code false}. Nullable so
     * an <em>absent</em> local file (POJO defaults) does not accidentally
     * force the sandbox on.
     */
    private @Nullable Boolean sandbox;

    private DomainRules paths = new DomainRules();
    private DomainRules commands = new DomainRules();

    /**
     * Allow/deny rule lists for one domain (paths use globs, commands
     * use regex — see {@link PermissionPolicy}).
     */
    @Data
    public static class DomainRules {
        private List<String> deny = new ArrayList<>();
        private List<String> allow = new ArrayList<>();
    }
}
