package de.mhus.vance.brain.jaglan.protocols;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;

import de.mhus.vance.shared.workspace.WorkspaceRootService;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Serves a directory on the brain's own machine.
 *
 * <p>Configuration, all under {@code jaglan.mount.<name>.}:
 *
 * <ul>
 *   <li>{@code protocol = local}</li>
 *   <li>{@code rootDir} — absolute path to an existing directory. Required.</li>
 *   <li>{@code writable} — {@code true} to allow writes and deletes.
 *       Defaults to <b>false</b>.</li>
 *   <li>{@code metadataTtlSeconds} — how long listings and stats stay valid.
 *       Defaults to 60: a local stat is nearly free, so there is little to buy
 *       by holding it longer, and staleness on a folder someone is editing by
 *       hand is the more annoying failure.</li>
 * </ul>
 *
 * <p><b>Refuses at instantiation rather than at access.</b> A missing or
 * relative {@code rootDir} throws here, so the factory drops the mount with a
 * log line and it never appears in the tree — better than a folder that opens
 * and then fails on every read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalFileJaglanProtocol implements JaglanProtocol {

    public static final String ID = "local";

    static final String EXTRA_ROOT_DIR = "rootDir";
    static final String EXTRA_WRITABLE = "writable";
    static final String EXTRA_TTL_SECONDS = "metadataTtlSeconds";

    /** Local stats are cheap; a short TTL keeps hand-edited folders honest. */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final WorkspaceRootService confinement;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Local directory";
    }

    @Override
    public JaglanInstance instantiate(JaglanInstanceConfig cfg) {
        String rootDir = cfg.extraString(EXTRA_ROOT_DIR);
        if (StringUtils.isBlank(rootDir)) {
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': " + EXTRA_ROOT_DIR + " is required");
        }
        Path root;
        try {
            root = Path.of(rootDir).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': " + EXTRA_ROOT_DIR
                            + " is not a valid path: " + rootDir, e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': " + EXTRA_ROOT_DIR
                            + " is not an existing directory: " + root);
        }

        boolean writable = Boolean.parseBoolean(cfg.extraString(EXTRA_WRITABLE));
        Duration ttl = parseTtl(cfg);
        log.info("Jaglan local mount '{}' → {} ({}, ttl {}s)",
                cfg.mount(), root, writable ? "read-write" : "read-only", ttl.toSeconds());
        return new LocalFileJaglanInstance(
                cfg.mount(), root, writable, ttl, "Local: " + root.getFileName(), confinement);
    }

    private static Duration parseTtl(JaglanInstanceConfig cfg) {
        String raw = cfg.extraString(EXTRA_TTL_SECONDS);
        if (raw == null) return DEFAULT_TTL;
        try {
            long seconds = Long.parseLong(raw);
            // Zero means "do not cache"; JaglanCapabilities clamps it to its
            // floor rather than folding it into a default, so pass it through.
            return seconds < 0 ? DEFAULT_TTL : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            log.warn("Jaglan local mount '{}': {} is not a number ('{}'), using {}s",
                    cfg.mount(), EXTRA_TTL_SECONDS, raw, DEFAULT_TTL.toSeconds());
            return DEFAULT_TTL;
        }
    }
}
