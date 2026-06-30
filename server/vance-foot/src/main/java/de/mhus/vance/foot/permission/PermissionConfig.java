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
     * Optional exec-isolation block. Nullable so an absent block keeps the
     * file clean (no empty {@code exec:} noise written by the "always"
     * writer). See {@link ExecIsolation}.
     */
    private @Nullable Exec exec;

    /**
     * Allow/deny rule lists for one domain (paths use globs, commands
     * use regex — see {@link PermissionPolicy}).
     */
    @Data
    public static class DomainRules {
        private List<String> deny = new ArrayList<>();
        private List<String> allow = new ArrayList<>();
    }

    @Data
    public static class Exec {
        private Isolation isolation = new Isolation();
    }

    /**
     * Opt-in OS-isolation wrapper for {@code client_exec_run}.
     * {@code mode: custom} wraps the command in {@link #wrapper} (a
     * whitespace-separated argv template with {@code {workdir}} and
     * {@code {cmd}} placeholders); {@code mode: none} (default) runs it
     * unwrapped.
     */
    @Data
    public static class Isolation {
        private String mode = "none";
        private String workdir = "./";
        private @Nullable String wrapper;
    }
}
