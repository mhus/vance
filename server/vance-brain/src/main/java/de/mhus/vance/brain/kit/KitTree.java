package de.mhus.vance.brain.kit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Symlink-safe traversal of a kit build/layer tree.
 *
 * <p><b>Security (code-review B1).</b> Kit bundles come from arbitrary,
 * untrusted git repositories. An earlier version walked the tree with
 * {@code FileVisitOption.FOLLOW_LINKS} and read/copied whatever a
 * symlink resolved to — a malicious kit could ship
 * {@code documents/leak.md → /path/to/application.yml} (or
 * {@code → /etc/…}) and have the installer read the target and store it
 * as a normal, readable project document, exfiltrating server secrets
 * such as {@code vance.encryption.password}.
 *
 * <p>Both helpers therefore walk <em>without</em> following links (so a
 * symlinked directory is never descended) and <em>hard-reject</em> any
 * symbolic link they encounter — a legitimate kit never contains one, so
 * failing closed with a clear error is safe and loud. Note that
 * {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)} follows
 * links by default, which is why rejecting the link entry up-front (not
 * just dropping non-regular files) is required.
 */
final class KitTree {

    private KitTree() {}

    /**
     * All entries (directories and files) under {@code root}, recursively,
     * in natural sort order, with symbolic links rejected. The caller
     * applies its own regular-file / directory filter.
     */
    static List<Path> walkNoSymlinks(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) { // no FOLLOW_LINKS
            stream.sorted(Comparator.naturalOrder()).forEach(p -> {
                rejectSymlink(root, p);
                out.add(p);
            });
        } catch (IOException e) {
            throw new KitException("failed to walk " + root, e);
        }
        return out;
    }

    /**
     * Direct children of {@code root} (non-recursive), with symbolic
     * links rejected. Mirrors {@link Files#list(Path)} semantics for the
     * flat {@code settings/} directory.
     */
    static List<Path> listNoSymlinks(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.forEach(p -> {
                rejectSymlink(root, p);
                out.add(p);
            });
        } catch (IOException e) {
            throw new KitException("failed to list " + root, e);
        }
        return out;
    }

    private static void rejectSymlink(Path root, Path entry) {
        if (Files.isSymbolicLink(entry)) {
            throw new KitException("kit contains a symbolic link, which is not "
                    + "allowed: " + safeRelativize(root, entry));
        }
    }

    private static String safeRelativize(Path root, Path entry) {
        try {
            return root.relativize(entry).toString();
        } catch (RuntimeException e) {
            return entry.toString();
        }
    }
}
