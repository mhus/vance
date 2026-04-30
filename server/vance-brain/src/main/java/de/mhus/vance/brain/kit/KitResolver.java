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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
     * Per-layer scan: relative paths of every artefact a single kit
     * declares (before merge / before last-writer-wins resolution).
     */
    public record LayerArtefacts(
            List<String> documents,
            List<String> settings,
            List<String> tools) {

        public static LayerArtefacts empty() {
            return new LayerArtefacts(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Result of a resolve operation. {@link #buildRoot} is the merged
     * tree; the caller treats it as read-only and is responsible for
     * cleaning up via {@link KitWorkspace#remove} on every entry of
     * {@link #temporaryPaths} (build root + every per-layer load).
     *
     * <p>{@link #topLayerArtefacts} and {@link #inheritArtefacts}
     * partition the merged tree by ownership after last-writer-wins:
     * each path/key/name appears in exactly one entry.
     */
    public record ResolvedKit(
            Path buildRoot,
            KitDescriptorDto topLayer,
            String sourceCommit,
            List<String> resolvedInherits,
            LayerArtefacts topLayerArtefacts,
            LinkedHashMap<String, LayerArtefacts> inheritArtefacts,
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

        // 3. Compute per-layer ownership over the merged tree —
        //    last-writer-wins per category. The result is what ends
        //    up persisted in the kit-manifest so prune-on-update can
        //    track inherit-side removals.
        OwnershipResult ownership = computeOwnership(mergeOrder, top.descriptor().getName());

        return new ResolvedKit(
                buildRoot,
                top.descriptor(),
                top.commit(),
                new ArrayList<>(resolvedNames),
                ownership.topLayer,
                ownership.inheritLayers,
                tmp,
                warnings);
    }

    // ──────────────────── ownership ────────────────────

    private record OwnershipResult(
            LayerArtefacts topLayer,
            LinkedHashMap<String, LayerArtefacts> inheritLayers) {}

    private static OwnershipResult computeOwnership(
            List<KitRepoLoader.LoadedKit> mergeOrder, String topLayerName) {
        // Scan each layer's declared paths once.
        LinkedHashMap<String, LayerArtefacts> perLayerScan = new LinkedHashMap<>();
        for (KitRepoLoader.LoadedKit layer : mergeOrder) {
            perLayerScan.put(layer.descriptor().getName(), scanLayer(layer.root()));
        }

        // Walk in merge order, last-writer-wins per (category, path).
        Map<String, String> docOwner = new LinkedHashMap<>();
        Map<String, String> settingOwner = new LinkedHashMap<>();
        Map<String, String> toolOwner = new LinkedHashMap<>();
        for (KitRepoLoader.LoadedKit layer : mergeOrder) {
            String name = layer.descriptor().getName();
            LayerArtefacts s = perLayerScan.get(name);
            for (String d : s.documents()) docOwner.put(d, name);
            for (String k : s.settings()) settingOwner.put(k, name);
            for (String t : s.tools()) toolOwner.put(t, name);
        }

        // Invert: build per-layer artefact lists out of the owner maps.
        LayerArtefacts topArtefacts = LayerArtefacts.empty();
        LinkedHashMap<String, LayerArtefacts> inheritArtefacts = new LinkedHashMap<>();
        for (String layerName : perLayerScan.keySet()) {
            List<String> docs = ownedFor(docOwner, layerName);
            List<String> settings = ownedFor(settingOwner, layerName);
            List<String> tools = ownedFor(toolOwner, layerName);
            if (layerName.equals(topLayerName)) {
                topArtefacts = new LayerArtefacts(docs, settings, tools);
            } else if (!docs.isEmpty() || !settings.isEmpty() || !tools.isEmpty()) {
                // Skip inherits that ended up fully shadowed — they don't own anything,
                // so no point listing them in the manifest.
                inheritArtefacts.put(layerName, new LayerArtefacts(docs, settings, tools));
            }
        }
        return new OwnershipResult(topArtefacts, inheritArtefacts);
    }

    private static List<String> ownedFor(Map<String, String> ownerMap, String layerName) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ownerMap.entrySet()) {
            if (layerName.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    private static LayerArtefacts scanLayer(Path layerRoot) {
        return new LayerArtefacts(
                scanDocuments(layerRoot.resolve(KitInstaller.DOCUMENTS_DIR)),
                scanSettings(layerRoot.resolve(KitInstaller.SETTINGS_DIR)),
                scanTools(layerRoot.resolve(KitInstaller.TOOLS_DIR)));
    }

    private static List<String> scanDocuments(Path docsRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(docsRoot)) return out;
        try (Stream<Path> stream = Files.walk(docsRoot, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String rel = docsRoot.relativize(file).toString().replace('\\', '/');
                out.add(rel);
            });
        } catch (IOException e) {
            throw new KitException("failed to walk " + docsRoot, e);
        }
        return out;
    }

    private static List<String> scanSettings(Path settingsRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(settingsRoot)) return out;
        try (Stream<Path> stream = Files.list(settingsRoot)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(KitInstaller.SETTING_FILE_SUFFIX)) return;
                out.add(filename.substring(
                        0, filename.length() - KitInstaller.SETTING_FILE_SUFFIX.length()));
            });
        } catch (IOException e) {
            throw new KitException("failed to list " + settingsRoot, e);
        }
        return out;
    }

    private static List<String> scanTools(Path toolsRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(toolsRoot)) return out;
        try (Stream<Path> stream = Files.list(toolsRoot)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(KitInstaller.TOOL_FILE_SUFFIX)) return;
                out.add(filename.substring(
                        0, filename.length() - KitInstaller.TOOL_FILE_SUFFIX.length()));
            });
        } catch (IOException e) {
            throw new KitException("failed to list " + toolsRoot, e);
        }
        return out;
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
