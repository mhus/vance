package de.mhus.vance.brain.tools.python;

import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared Python-execution helper used by both the LLM-facing
 * {@link ExecutePythonTool} and the REST-facing
 * {@code PythonCortexController}.
 *
 * <p>Encapsulates the steps that must happen the same way regardless
 * of caller: ensure a default {@code _python} RootDir exists for the
 * project (with venv on first call), write the source as a transient
 * file, build the interpreter command, and submit it to
 * {@link ExecManager}.
 *
 * <p>The tool's existing synchronous {@code submitTrackedAndRender}
 * pathway stays intact; the Cortex REST surface uses the async
 * {@link ExecManager#submitTracked} variant so the HTTP call returns
 * an execution id without blocking on script completion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonExecutionService {

    /** Label of the default Python RootDir created on first
     *  invocation. Underscore prefix marks it as system-managed
     *  (same convention as {@code _user_*} / {@code _tenant} projects). */
    public static final String DEFAULT_LABEL = "_python";

    /** Marker file inside the RootDir that records which inline-deps
     *  hash was last successfully installed. Lets us skip the pip
     *  install step when the {@code # /// script} block hasn't
     *  changed between runs. */
    private static final String DEPS_HASH_MARKER = ".vance_inline_deps_hash";

    private final WorkspaceService workspaceService;
    private final ExecManager execManager;

    /**
     * Submit a Python script for async execution. Returns the
     * execution id; the caller polls {@link ExecManager#renderJob} (or
     * the higher-level REST endpoint) for status + output.
     *
     * @param sessionId chat session id — bound to the RootDir on
     *                  first creation so the workspace lifecycle
     *                  tracks the session, not just the tenant.
     * @param processId think-process id if the execution is owned by
     *                  one — drives EXEC_FINISHED notifications. Can
     *                  be {@code null} for REST-driven runs.
     */
    public String executeAsync(
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String code,
            List<String> args,
            @Nullable String flags) {
        RootDirHandle handle = ensureDefaultPythonRootDir(
                tenantId, projectId,
                StringUtils.defaultIfBlank(processId,
                        StringUtils.defaultIfBlank(sessionId, "_cortex")),
                sessionId);
        String dirName = handle.getDirName();

        String fileName = "_inline_" + System.currentTimeMillis() + ".py";
        try {
            Path written = workspaceService.write(tenantId, projectId, dirName, fileName, code);
            log.debug("PythonExecutionService: wrote {} chars to {}/{}",
                    code.length(), dirName, written.getFileName());
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Python execute: failed to write script: " + e.getMessage(), e);
        }

        // PEP 723 inline-script-metadata — if the file declares
        // dependencies and they've changed since the last successful
        // install, prepend a pip-install step so the user can rely on
        // "just hit Run, the venv catches up". When deps are
        // unchanged we skip the install entirely (hash marker check).
        List<String> inlineDeps = PythonInlineMetadata.parseDependencies(code);
        boolean needsInstall = !inlineDeps.isEmpty()
                && !installedHashMatches(tenantId, projectId, dirName, inlineDeps);

        StringBuilder cmd = new StringBuilder();
        if (needsInstall) {
            String depsHash = hashDeps(inlineDeps);
            cmd.append(".venv/bin/python -m pip install --quiet");
            for (String dep : inlineDeps) {
                cmd.append(' ').append(PythonShellEscape.quote(dep));
            }
            // Persist the hash only on a successful pip install — a
            // failed install leaves the marker untouched so the next
            // run retries.
            cmd.append(" && echo ").append(PythonShellEscape.quote(depsHash))
                    .append(" > ").append(DEPS_HASH_MARKER);
            cmd.append(" && ");
        }
        cmd.append(".venv/bin/python");
        if (StringUtils.isNotBlank(flags)) {
            cmd.append(' ').append(flags);
        }
        cmd.append(' ').append(PythonShellEscape.quote(fileName));
        for (String arg : args) {
            cmd.append(' ').append(PythonShellEscape.quote(arg));
        }

        return execManager.submitTracked(
                tenantId, projectId, sessionId, processId, dirName, cmd.toString());
    }

    /**
     * Stable hash for the dependency list. Sensitive to order so
     * {@code [A, B]} → {@code [B, A]} re-triggers install (cheap, and
     * pip handles existing-package no-ops in seconds).
     */
    private static String hashDeps(List<String> deps) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String d : deps) {
                md.update(d.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            byte[] raw = md.digest();
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16); // 8-byte prefix is plenty for change detection
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK; treat as fatal.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Read the marker file from the RootDir and compare against the
     * hash of the freshly-parsed dependency list. Returns {@code true}
     * only when the marker exists and matches — any read error /
     * absent marker / mismatch reports "doesn't match" so the install
     * runs.
     */
    private boolean installedHashMatches(
            String tenantId, String projectId, String dirName, List<String> deps) {
        try {
            Path marker = workspaceService.resolve(tenantId, projectId, dirName, DEPS_HASH_MARKER);
            if (!Files.isReadable(marker)) return false;
            String stored = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return stored.equals(hashDeps(deps));
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("PythonExecutionService: deps-hash marker unreadable, will reinstall: {}",
                    e.toString());
            return false;
        }
    }

    /**
     * Idempotent RootDir bootstrap. Same logic as
     * {@code ExecutePythonTool#ensureDefaultPythonRootDir} — looks up
     * the project's {@code _python} RootDir by label, creates one with
     * a fresh venv when absent.
     */
    private RootDirHandle ensureDefaultPythonRootDir(
            String tenantId, String projectId, String creator, @Nullable String sessionId) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (!PythonHandler.TYPE.equals(h.getType())) continue;
            String label = h.getDescriptor() == null ? null : h.getDescriptor().getLabel();
            if (DEFAULT_LABEL.equals(label)) {
                return h;
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(PythonHandler.META_PYTHON_PATH, PythonHandler.DEFAULT_PYTHON_PATH);
        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(PythonHandler.TYPE)
                .creatorProcessId(creator)
                .sessionId(sessionId)
                .labelHint(DEFAULT_LABEL)
                .deleteOnCreatorClose(false)
                .metadata(metadata)
                .build();
        try {
            RootDirHandle handle = workspaceService.createRootDir(spec);
            log.info("PythonExecutionService: created default Python RootDir tenant='{}' "
                    + "project='{}' dirName='{}'", tenantId, projectId, handle.getDirName());
            return handle;
        } catch (WorkspaceException e) {
            throw new RuntimeException(
                    "Python execute: failed to provision Python RootDir: "
                            + e.getMessage(), e);
        }
    }
}
