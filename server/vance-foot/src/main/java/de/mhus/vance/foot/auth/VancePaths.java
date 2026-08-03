package de.mhus.vance.foot.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for locating the {@code .vance} directory that
 * holds this run's local configuration ({@code project.eddie.yaml}) and
 * credentials ({@code access.yaml}).
 *
 * <p><b>Resolution — "project wins, else global".</b>
 * <ul>
 *   <li><b>Project-local</b> {@code ./.vancetope} (relative to the CWD) is the
 *       active location when it exists <em>and</em> local mode is enabled.
 *       This is the per-project store — the folder "belongs to" a specific
 *       brain + project.</li>
 *   <li><b>Global home</b> is the fallback when there is no usable
 *       project-local directory: {@code $VANCE_HOME} when set, otherwise
 *       {@code ~/.vancetope} (the historical location shared with permissions,
 *       history, foot-tools, …).</li>
 * </ul>
 *
 * <p>Local mode is on by default and turned off for a single run with the
 * {@code --no-local} flag (see {@code VanceFootCommand}), which calls
 * {@link #setLocalEnabled(boolean)} before any store is consulted.
 *
 * <p>Reading uses {@link #activeDir()} (project-local when present, else
 * global). Writing a fresh login uses {@link #loginTargetDir()} — the
 * project-local directory by default so {@code /login} sets up the current
 * working directory, falling back to the global home only when local mode
 * is disabled.
 */
@Component
public class VancePaths {

    public static final String DIR_NAME = ".vancetope";
    public static final String PROJECT_FILE = "project.eddie.yaml";
    public static final String ACCESS_FILE = "access.yaml";
    public static final String SESSION_FILE = "session.yaml";
    public static final String CONFIG_FILE = "config.yaml";

    /** Test-only override for the project-local {@code .vance} directory. */
    private final @Nullable String localDirOverride;
    /** Test-only override for the global home {@code .vance} directory. */
    private final @Nullable String homeOverride;
    private final @Nullable String vanceHomeEnv;
    private final String userDir;
    private final String userHome;

    /** Mutable so {@code --no-local} can flip it at command start-up. */
    private volatile boolean localEnabled = true;

    @Autowired
    public VancePaths(
            @Value("${vance.local.dir:}") String localDirOverride,
            @Value("${vance.home:}") String homeOverride) {
        this(blankToNull(localDirOverride),
                blankToNull(homeOverride),
                blankToNull(System.getenv("VANCE_HOME")),
                System.getProperty("user.dir", ""),
                System.getProperty("user.home", ""));
    }

    /** Explicit-inputs constructor for tests. */
    VancePaths(@Nullable String localDirOverride,
               @Nullable String homeOverride,
               @Nullable String vanceHomeEnv,
               String userDir,
               String userHome) {
        this.localDirOverride = localDirOverride;
        this.homeOverride = homeOverride;
        this.vanceHomeEnv = vanceHomeEnv;
        this.userDir = userDir;
        this.userHome = userHome;
    }

    /** Whether project-local {@code ./.vancetope} participates in resolution. */
    public boolean isLocalEnabled() {
        return localEnabled;
    }

    /** Turns project-local resolution off (or on) for this run. */
    public void setLocalEnabled(boolean enabled) {
        this.localEnabled = enabled;
    }

    /** The project-local directory {@code ./.vancetope} (may not exist). */
    public Path projectLocalDir() {
        if (localDirOverride != null) {
            return Path.of(localDirOverride);
        }
        return Path.of(userDir, DIR_NAME);
    }

    /** The global home directory: {@code $VANCE_HOME} else {@code ~/.vancetope} (may not exist). */
    public Path globalHomeDir() {
        if (homeOverride != null) {
            return Path.of(homeOverride);
        }
        if (vanceHomeEnv != null) {
            return Path.of(vanceHomeEnv);
        }
        return Path.of(userHome, DIR_NAME);
    }

    /**
     * The directory to read this run's config/credentials from: the
     * project-local directory when it exists and local mode is enabled,
     * otherwise the global home (which itself may not yet exist).
     */
    public Path activeDir() {
        Path local = projectLocalDir();
        if (localEnabled && Files.isDirectory(local)) {
            return local;
        }
        return globalHomeDir();
    }

    /**
     * The directory a fresh {@code /login} should write to: project-local
     * by default (created on write), or the global home when local mode is
     * disabled.
     */
    public Path loginTargetDir() {
        return localEnabled ? projectLocalDir() : globalHomeDir();
    }

    /** {@code true} when the active directory is the project-local one. */
    public boolean isActiveLocal() {
        return activeDir().equals(projectLocalDir());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
