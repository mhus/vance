package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves a kit's inherit chain into a single merged tree on disk.
 *
 * <p>Walks the inherits depth-first, loading each layer via
 * {@link KitRepoLoader}. The resulting build tree is the file-by-file
 * union of all layers — last-layer-wins on path collision. The
 * top-layer kit (the one being installed) is the innermost layer, so
 * its files override every inherit.
 *
 * <p>Inherit cycles are detected over {@code (url, path)} and abort
 * with a {@link KitException}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitResolver {

    private final KitRepoLoader repoLoader;
    private final KitWorkspace workspace;

    /**
     * Result of a resolve operation. {@link #buildRoot} is the merged
     * tree; the caller treats it as read-only and is responsible for
     * cleaning up via {@link KitWorkspace#remove} on every entry of
     * {@link #temporaryPaths} (build root + every per-layer load).
     */
    public record ResolvedKit(
            Path buildRoot,
            KitDescriptorDto topLayer,
            String sourceCommit,
            List<String> resolvedInherits,
            List<Path> temporaryPaths,
            List<String> warnings) {

        public void cleanup(KitWorkspace ws) {
            for (Path p : temporaryPaths) ws.remove(p);
        }
    }

    /**
     * Resolve {@code source} against its inherits and build the merged
     * tree. {@code token} authenticates remote clones; layers may
     * supply their own auth in the future, but v1 reuses the top-level
     * token across all inherits (a {@code KitException} is logged as
     * warning if a private inherit fails to clone — the caller decides
     * whether to abort).
     */
    public ResolvedKit resolve(KitInheritDto source, @Nullable String token) {
        List<Path> tmp = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        LinkedHashSet<String> resolvedNames = new LinkedHashSet<>();

        Path topLoadDir = workspace.allocate("kit-top");
        tmp.add(topLoadDir);
        KitRepoLoader.LoadedKit top = repoLoader.load(source, token, topLoadDir);
        markVisited(visited, source);

        // 1. Recursively gather the merge order: outermost inherit first,
        //    top layer last. We build a stack-style list so DFS-order
        //    becomes the desired application order.
        List<KitRepoLoader.LoadedKit> mergeOrder = new ArrayList<>();
        collectInherits(top, token, visited, resolvedNames, tmp, warnings, mergeOrder);
        mergeOrder.add(top); // top layer applied last → wins

        // 2. Build the merged tree. Each layer is copy-on-top: same
        //    relative path overwrites whatever earlier layers placed.
        Path buildRoot = workspace.allocate("kit-build");
        tmp.add(buildRoot);
        for (KitRepoLoader.LoadedKit layer : mergeOrder) {
            mergeLayer(layer.root(), buildRoot);
        }

        return new ResolvedKit(
                buildRoot,
                top.descriptor(),
                top.commit(),
                new ArrayList<>(resolvedNames),
                tmp,
                warnings);
    }

    // ──────────────────── private ────────────────────

    private void collectInherits(
            KitRepoLoader.LoadedKit layer,
            @Nullable String token,
            Set<String> visited,
            LinkedHashSet<String> resolvedNames,
            List<Path> tmp,
            List<String> warnings,
            List<KitRepoLoader.LoadedKit> mergeOrder) {
        List<KitInheritDto> inherits = layer.descriptor().getInherits();
        if (inherits == null || inherits.isEmpty()) return;
        for (KitInheritDto parent : inherits) {
            String key = visitKey(parent);
            if (!visited.add(key)) {
                throw new KitException("inherit cycle detected at " + key);
            }
            Path dir = workspace.allocate("kit-inherit");
            tmp.add(dir);
            KitRepoLoader.LoadedKit loaded;
            try {
                loaded = repoLoader.load(parent, token, dir);
            } catch (KitException e) {
                warnings.add("failed to load inherit " + key + ": " + e.getMessage());
                log.warn("inherit load failed: {}", e.getMessage());
                continue;
            }
            collectInherits(loaded, token, visited, resolvedNames, tmp, warnings, mergeOrder);
            mergeOrder.add(loaded);
            resolvedNames.add(loaded.descriptor().getName());
        }
    }

    private static void markVisited(Set<String> visited, KitInheritDto source) {
        visited.add(visitKey(source));
    }

    private static String visitKey(KitInheritDto source) {
        String url = source.getUrl() == null ? "" : source.getUrl().trim();
        String path = source.getPath() == null ? "" : source.getPath().trim();
        return url + "|" + path;
    }

    private static void mergeLayer(Path layerRoot, Path buildRoot) {
        try (Stream<Path> stream = Files.walk(layerRoot, FileVisitOption.FOLLOW_LINKS)) {
            stream.sorted(Comparator.naturalOrder()).forEach(src -> {
                Path rel = layerRoot.relativize(src);
                if (rel.toString().isEmpty()) return; // root itself
                Path dst = buildRoot.resolve(rel.toString());
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Path parent = dst.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new KitException("failed to merge " + src + " → " + dst, e);
                }
            });
        } catch (IOException e) {
            throw new KitException("failed to walk layer " + layerRoot, e);
        }
    }
}
