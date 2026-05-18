package de.mhus.vance.brain.script;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-binding for {@code vance.script.*} application properties.
 * See {@code specification/script-engine.md} §3.5.7 for the canonical
 * key list + defaults.
 *
 * <p>Per-tenant overrides go through the {@code SettingService}
 * cascade with the same keys ({@code vance.script.timeout.default}
 * etc.). This properties bag is the JVM-wide floor when no
 * tenant-setting exists.
 */
@ConfigurationProperties(prefix = "vance.script")
public class ScriptEngineProperties {

    private TimeoutLimits timeout = new TimeoutLimits();
    private StatementLimits statements = new StatementLimits();
    private Capabilities capabilities = new Capabilities();
    private Require require = new Require();

    public TimeoutLimits getTimeout() { return timeout; }
    public void setTimeout(TimeoutLimits timeout) { this.timeout = timeout; }

    public StatementLimits getStatements() { return statements; }
    public void setStatements(StatementLimits statements) {
        this.statements = statements;
    }

    public Capabilities getCapabilities() { return capabilities; }
    public void setCapabilities(Capabilities capabilities) {
        this.capabilities = capabilities;
    }

    public Require getRequire() { return require; }
    public void setRequire(Require require) { this.require = require; }

    public static class TimeoutLimits {
        /** Default wall-clock for a script run when neither header
         *  nor caller specifies one. */
        private Duration defaultValue = Duration.ofSeconds(30);
        /** Hard floor — header values below this get clamped up. */
        private Duration min = Duration.ofSeconds(1);
        /** Hard cap — header values above this get clamped down +
         *  warn-log. */
        private Duration max = Duration.ofHours(1);

        public Duration getDefault() { return defaultValue; }
        public void setDefault(Duration v) { this.defaultValue = v; }
        public Duration getMin() { return min; }
        public void setMin(Duration v) { this.min = v; }
        public Duration getMax() { return max; }
        public void setMax(Duration v) { this.max = v; }
    }

    public static class StatementLimits {
        private long defaultValue = 1_000_000L;
        private long min = 1_000L;
        private long max = 100_000_000L;

        public long getDefault() { return defaultValue; }
        public void setDefault(long v) { this.defaultValue = v; }
        public long getMin() { return min; }
        public void setMin(long v) { this.min = v; }
        public long getMax() { return max; }
        public void setMax(long v) { this.max = v; }
    }

    /**
     * Toggles the GraalJS CommonJS-require pathway. Disabled by
     * default — scripts run with {@code IOAccess.NONE} and
     * {@code require()} is undefined, identical to legacy v0
     * behaviour. When enabled, a script with a {@code @workspaceRoot}
     * header tag can {@code require()} packages from the corresponding
     * Node RootDir's {@code node_modules} folder, via a sandboxed
     * read-only FileSystem that denies access to anything else.
     *
     * <p>Even with {@code enabled=true}, a script without
     * {@code @workspaceRoot} uses the legacy IOAccess.NONE pathway —
     * there is no implicit workspace binding.
     */
    public static class Require {
        /** Master switch. Default false — feature is opt-in. */
        private boolean enabled = false;

        /** Default workspace label that resolves to a Node RootDir
         *  when a script omits {@code @workspaceRoot} but the require
         *  pathway is otherwise active. Default {@code "_jsengine"}
         *  matches {@link de.mhus.vance.shared.workspace.NodeHandler#DEFAULT_LABEL}. */
        private String defaultLabel = "_jsengine";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getDefaultLabel() { return defaultLabel; }
        public void setDefaultLabel(String v) { this.defaultLabel = v; }
    }

    public static class Capabilities {
        /** When {@code true}, {@code @requiresTools} is checked
         *  pre-eval; declared-but-not-allowed entries raise
         *  MISSING_CAPABILITY before the script runs. When
         *  {@code false}, the check defers to runtime — the
         *  individual tool call fails with a regular ToolException
         *  if the tool isn't in scope. */
        private boolean enforceRequires = true;

        public boolean isEnforceRequires() { return enforceRequires; }
        public void setEnforceRequires(boolean v) { this.enforceRequires = v; }
    }
}
