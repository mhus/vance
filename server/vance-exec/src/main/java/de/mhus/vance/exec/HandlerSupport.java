package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** What the git, node and python RootDir handlers have in common. */
@Slf4j
final class HandlerSupport {

    private HandlerSupport() {}

    /** The descriptor's metadata as a map the handler may write to. */
    static Map<String, Object> mutableMetadata(WorkspaceDescriptor d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) {
            return new LinkedHashMap<>();
        }
        if (meta instanceof HashMap<?, ?> || meta instanceof LinkedHashMap<?, ?>) {
            return meta;
        }
        return new LinkedHashMap<>(meta);
    }

    /** A non-blank string value, else {@code fallback}, else the empty string. */
    static String stringOr(Map<String, Object> meta, String key, @Nullable String fallback) {
        Object v = meta.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback == null ? "" : fallback;
    }

    static String stringOrThrow(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new RootDirException("git metadata is missing required field '" + key + "'");
        }
        return s;
    }

    /** Writes {@code .gitignore} unless the RootDir already has one. */
    static void writeDefaultGitignore(Path rootDirPath, String content) {
        Path gitignore = rootDirPath.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            return;
        }
        try {
            Files.writeString(gitignore, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Failed to write default .gitignore in {}: {}", rootDirPath, e.toString());
        }
    }

    /**
     * Runs a program in the RootDir through the {@link ProcessLauncher}, scoped to its
     * project. {@code network}: only installs reach a registry. A missing launcher
     * (an application that never starts programs, like the admin shell) fails with a
     * clear message instead of spawning an unsealed child.
     */
    static void runProcess(
            Supplier<@Nullable ProcessLauncher> launcherSupplier,
            RootDirHandle handle,
            String[] command,
            long timeoutSeconds,
            String label,
            boolean network) {
        Path cwd = handle.getPath();
        ProcessLauncher launcher = launcherSupplier.get();
        if (launcher == null) {
            throw new RootDirException(
                    "starting " + label + " is not available in this application (no ProcessLauncher)");
        }
        ProcessSpec spec = ProcessSpec.of(List.of(command), cwd).withScope(handle.getTenantId(), handle.getProjectId());
        Process p;
        try {
            p = launcher.start(network ? spec.withNetwork() : spec);
        } catch (IOException e) {
            throw new RootDirException(label + " failed to start: " + e.getMessage(), e);
        }
        String output;
        try {
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RootDirException(label + " timed out after " + timeoutSeconds + "s");
            }
        } catch (IOException e) {
            throw new RootDirException(label + " I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RootDirException(label + " interrupted", e);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            throw new RootDirException(
                    label + " exited with code " + exit + (output.isBlank() ? "" : ": " + output.strip()));
        }
        log.debug("{} ok (cwd={})", label, cwd);
    }
}
