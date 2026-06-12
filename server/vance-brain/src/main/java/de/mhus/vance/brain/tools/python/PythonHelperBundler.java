package de.mhus.vance.brain.tools.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drops the bundled {@code vance.py} helper next to the script being
 * run, so the script can {@code import vance} and reach the brain via
 * the {@code VANCE_*} env vars + {@code VANCE_TOKEN} JWT set by
 * {@link de.mhus.vance.brain.access.ScriptRunEnvironmentBuilder}.
 *
 * <p>Idempotent: overwrites the existing file every spawn so helper
 * updates propagate without manual cleanup. Users who name their own
 * file {@code vance.py} have it replaced — documented anti-pattern.
 */
@Service
@Slf4j
public class PythonHelperBundler {

    private static final String RESOURCE_PATH = "/python-helpers/vance.py";
    private static final String FILE_NAME = "vance.py";

    /**
     * Writes the bundled {@code vance.py} into {@code workspaceDir}.
     * Caller has ensured the directory exists.
     */
    public void installInto(Path workspaceDir) {
        Path target = workspaceDir.resolve(FILE_NAME);
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream(RESOURCE_PATH),
                "Bundled vance.py helper missing at classpath:" + RESOURCE_PATH)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to install vance.py helper into " + target, e);
        }
        log.debug("Installed vance.py helper to {}", target);
    }

    /** Read the bundled helper as a string — used by tests. */
    public String helperSource() {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream(RESOURCE_PATH),
                "Bundled vance.py helper missing at classpath:" + RESOURCE_PATH)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read vance.py helper", e);
        }
    }
}
