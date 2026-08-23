package de.mhus.vance.brain.jaglan.protocols;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import de.mhus.vance.shared.workspace.WorkspaceRootService;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <h2>The operator decides which directories exist at all</h2>
 * {@code rootDir} arrives from a <em>project-scoped setting</em>, and settings
 * are reachable by a project admin, by a setting form and — before the deny
 * list closed that door — by an installed kit. Confinement answers "inside the
 * rootDir", never "which rootDir", so on its own this protocol turned
 * {@code jaglan.mount.x.rootDir=/} plus {@code writable=true} into read/write
 * access to the brain pod's whole file system, {@code /proc/self/environ}
 * included.
 *
 * <p>So the set of permissible roots is a <b>property</b>, not a setting:
 * {@code vance.jaglan.local.allowed-roots}, comma-separated absolute paths.
 * Same character as {@code vance.exec.isolation} and
 * {@code vance.settings.agentWriteDenyKeys} — operator territory, never
 * writable by anything running inside the product. <b>Empty is the default and
 * means the protocol is off</b>: a capability this wide is not something one
 * should get by omission.
 */
@Component
@Slf4j
public class LocalFileJaglanProtocol implements JaglanProtocol {

    public static final String ID = "local";

    static final String EXTRA_ROOT_DIR = "rootDir";
    static final String EXTRA_WRITABLE = "writable";
    static final String EXTRA_TTL_SECONDS = "metadataTtlSeconds";

    /** The operator property that enables this protocol at all. */
    public static final String ALLOWED_ROOTS_PROPERTY = "vance.jaglan.local.allowed-roots";

    /** Local stats are cheap; a short TTL keeps hand-edited folders honest. */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final WorkspaceRootService confinement;

    /** Absolute, normalised directories a mount's {@code rootDir} may sit in
     *  or under. Empty = protocol disabled. */
    private final List<Path> allowedRoots;

    public LocalFileJaglanProtocol(
            WorkspaceRootService confinement,
            @Value("${" + ALLOWED_ROOTS_PROPERTY + ":}") String allowedRootsRaw) {
        this.confinement = confinement;
        this.allowedRoots = parseAllowedRoots(allowedRootsRaw);
        if (allowedRoots.isEmpty()) {
            log.info("Jaglan '{}' protocol is disabled — no {} configured",
                    ID, ALLOWED_ROOTS_PROPERTY);
        } else {
            log.info("Jaglan '{}' protocol enabled for {}", ID, allowedRoots);
        }
    }

    private static List<Path> parseAllowedRoots(String raw) {
        List<Path> out = new ArrayList<>();
        if (StringUtils.isBlank(raw)) return List.copyOf(out);
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Path candidate = Path.of(trimmed).toAbsolutePath().normalize();
                if (!Files.isDirectory(candidate)) {
                    // Logged, not fatal: an operator listing a root that is not
                    // mounted on this pod should not stop the pod from booting,
                    // and the entry simply grants nothing.
                    log.warn("{}: '{}' is not an existing directory — ignored",
                            ALLOWED_ROOTS_PROPERTY, trimmed);
                    continue;
                }
                out.add(candidate);
            } catch (InvalidPathException e) {
                log.warn("{}: '{}' is not a valid path — ignored",
                        ALLOWED_ROOTS_PROPERTY, trimmed);
            }
        }
        return List.copyOf(out);
    }

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
        if (allowedRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': the '" + ID + "' protocol is disabled on this"
                            + " installation — an operator has to list the permissible base"
                            + " directories in " + ALLOWED_ROOTS_PROPERTY);
        }
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
        // Symlink-aware, so a link inside an allowed root that points at /etc
        // does not smuggle the whole file system back in.
        if (allowedRoots.stream().noneMatch(base -> confinement.isWithin(base, root))) {
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': " + EXTRA_ROOT_DIR + " '" + root
                            + "' is outside every directory the operator permitted in "
                            + ALLOWED_ROOTS_PROPERTY + " " + allowedRoots);
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
