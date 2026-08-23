package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.KitTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final KitSourceLoaders sourceLoaders;
    private final KitWorkspace workspace;
    /** Only to answer "does this inherit come from the same source" — see
     *  {@link #accessFor}. */
    private final KitSourceRegistry sources;

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
            List<String> warnings,
            KitSignatureStatus topLayerSignature,
            String topLayerSourceId,
            /**
             * How the top layer was fetched. Decides the policy default when
             * neither the user nor the kit author wrote one — a bundle that a
             * host assembles per request has different expectations than a
             * checkout somebody edits.
             */
            KitSourceType topLayerSourceType) {

        public void cleanup(KitWorkspace ws) {
            for (Path p : temporaryPaths) ws.remove(p);
        }
    }

    /**
     * Resolve {@code source} against its inherits and build the merged
     * tree. {@code access} authenticates remote clones; layers may
     * supply their own auth in the future, but v1 reuses the top-level
     * credential across all inherits.
     *
     * <p>An inherit that cannot be loaded aborts the whole resolve
     * ({@code KitException}) — kits.md §11, no partial import. It used to be a
     * warning nobody read, which turned an unreachable inherit host into an
     * install missing a layer, and on {@code --prune} into the deletion of that
     * layer's documents.
     */
    public ResolvedKit resolve(KitAccess access, KitInheritDto source) {
        List<Path> tmp = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        LinkedHashSet<String> resolvedNames = new LinkedHashSet<>();

        Path topLoadDir = workspace.allocate("kit-top");
        tmp.add(topLoadDir);
        KitSourceLoaders.LoadResult topResult =
                sourceLoaders.loadFrom(access, source, topLoadDir);
        KitRepoLoader.LoadedKit top = topResult.kit();
        markVisited(visited, source);
        // Active DFS path for TRUE cycle detection (a key that is its own
        // ancestor), kept distinct from `visited` which now only dedupes
        // already-merged layers (first-seen-wins for a diamond). Seed it with
        // the top so an inherit pointing back at the top is caught as a cycle.
        Set<String> onPath = new HashSet<>();
        onPath.add(visitKey(source));

        // 1. Recursively gather the merge order: outermost inherit first,
        //    top layer last. We build a stack-style list so DFS-order
        //    becomes the desired application order.
        List<KitRepoLoader.LoadedKit> mergeOrder = new ArrayList<>();
        collectInherits(access, topResult.config().getId(), top, visited, onPath,
                resolvedNames, tmp, mergeOrder);
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
                warnings,
                topResult.signature(),
                topResult.config().getId(),
                topResult.config().getType());
    }

    // ──────────────────── ownership ────────────────────

    private record OwnershipResult(
            LayerArtefacts topLayer,
            LinkedHashMap<String, LayerArtefacts> inheritLayers) {}

    private static OwnershipResult computeOwnership(
            List<KitRepoLoader.LoadedKit> mergeOrder, String topLayerName) {
        // Scan each layer's declared paths once, indexed by position rather
        // than by descriptor name: the name is explicitly not an identifier
        // (kits.md §4.1 — two vendors may both ship a kit called `security`),
        // and a name-keyed map silently made the second layer's scan stand in
        // for the first one's.
        List<LayerArtefacts> perLayerScan = new ArrayList<>(mergeOrder.size());
        LinkedHashSet<String> layerNames = new LinkedHashSet<>();
        for (KitRepoLoader.LoadedKit layer : mergeOrder) {
            perLayerScan.add(scanLayer(layer.root()));
            layerNames.add(layer.descriptor().getName());
        }

        // Walk in merge order, last-writer-wins per (category, path).
        Map<String, String> docOwner = new LinkedHashMap<>();
        Map<String, String> settingOwner = new LinkedHashMap<>();
        Map<String, String> toolOwner = new LinkedHashMap<>();
        for (int i = 0; i < mergeOrder.size(); i++) {
            String name = mergeOrder.get(i).descriptor().getName();
            LayerArtefacts s = perLayerScan.get(i);
            for (String d : s.documents()) docOwner.put(d, name);
            for (String k : s.settings()) settingOwner.put(k, name);
            for (String t : s.tools()) toolOwner.put(t, name);
        }

        // Invert: build per-layer artefact lists out of the owner maps.
        LayerArtefacts topArtefacts = LayerArtefacts.empty();
        LinkedHashMap<String, LayerArtefacts> inheritArtefacts = new LinkedHashMap<>();
        for (String layerName : layerNames) {
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
                /*tools*/ new ArrayList<>());
    }

    private static List<String> scanDocuments(Path docsRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(docsRoot)) return out;
        for (Path file : KitTree.walkNoSymlinks(docsRoot)) {
            if (!Files.isRegularFile(file)) continue;
            out.add(docsRoot.relativize(file).toString().replace('\\', '/'));
        }
        return out;
    }

    private static List<String> scanSettings(Path settingsRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(settingsRoot)) return out;
        for (Path file : KitTree.listNoSymlinks(settingsRoot)) {
            if (!Files.isRegularFile(file)) continue;
            String filename = file.getFileName().toString();
            if (!filename.endsWith(KitInstaller.SETTING_FILE_SUFFIX)) continue;
            out.add(filename.substring(
                    0, filename.length() - KitInstaller.SETTING_FILE_SUFFIX.length()));
        }
        return out;
    }

    // ──────────────────── private ────────────────────

    private void collectInherits(
            KitAccess access,
            String topSourceId,
            KitRepoLoader.LoadedKit layer,
            Set<String> visited,
            Set<String> onPath,
            LinkedHashSet<String> resolvedNames,
            List<Path> tmp,
            List<KitRepoLoader.LoadedKit> mergeOrder) {
        List<KitInheritDto> inherits = layer.descriptor().getInherits();
        if (inherits == null || inherits.isEmpty()) return;
        for (KitInheritDto parent : inherits) {
            String key = visitKey(parent);
            // True cycle: this key is its own ancestor on the current DFS path.
            if (onPath.contains(key)) {
                throw new KitException("inherit cycle detected at " + key);
            }
            // Diamond (two branches reach the same base): NOT a cycle — merge it
            // once, first-seen-wins (kits.md §11), and skip the re-visit.
            if (!visited.add(key)) {
                log.debug("kit inherit '{}' already resolved on another branch "
                        + "— skipping re-merge (diamond)", key);
                continue;
            }
            onPath.add(key);
            try {
                Path dir = workspace.allocate("kit-inherit");
                tmp.add(dir);
                KitRepoLoader.LoadedKit loaded;
                KitAccess layerAccess = accessFor(access, topSourceId, parent);
                try {
                    loaded = sourceLoaders.load(layerAccess, parent, dir);
                } catch (KitException e) {
                    // Hard failure, not a warning. Nobody read the warnings —
                    // the operation counted as successful — and the build tree
                    // was then assembled *without* this layer. On an
                    // `update --prune` every artefact the record attributes to
                    // the missing layer is absent from the scan, so prune
                    // deletes it: a thirty-second outage at the inherit's host
                    // would wipe a whole layer's documents out of the project.
                    // kits.md §11 says it plainly — no partial import, the whole
                    // kit including its inherits or nothing.
                    throw new KitException("failed to load inherit " + key
                            + " — refusing a partial import (a missing layer would look"
                            + " like artefacts the kit dropped): " + e.getMessage(), e);
                }
                // Spec kits.md §3.2 — sealed kits refuse to be inherited
                // from. Hard fail so the user gets the actual reason rather
                // than a confusing missing-artefact message later.
                if (loaded.descriptor().isSealed()) {
                    throw new KitException("kit '" + loaded.descriptor().getName()
                            + "' is sealed and cannot be inherited from (referenced as "
                            + key + ")");
                }
                collectInherits(layerAccess, topSourceId, loaded, visited, onPath,
                        resolvedNames, tmp, mergeOrder);
                mergeOrder.add(loaded);
                resolvedNames.add(loaded.descriptor().getName());
            } finally {
                onPath.remove(key);
            }
        }
    }

    /**
     * The credential an inherit layer may be fetched with.
     *
     * <p>The token travels only as far as the source it belongs to. For a git
     * kit the {@code inherits:} list is repository content somebody chose; for
     * an {@code ode} kit the host composes it <b>per request</b>, so passing
     * the top-level token down let the far end decide where our bearer token
     * goes — {@code GitKitSourceLoader} hands it straight to JGit as a
     * credential, so an inherit pointing at {@code attacker.example} would have
     * been an exfiltration of the token in a git clone.
     *
     * <p>Compared over the resolved source id, not over the url: that is the
     * unit the credential was configured for, and it keeps a host-level source
     * covering several of its own repositories working. A layer from anywhere
     * else is fetched anonymously — a private inherit under a different source
     * then fails to clone, and that is the correct, visible outcome rather than
     * a silent credential hand-off.
     */
    private KitAccess accessFor(KitAccess access, String topSourceId, KitInheritDto parent) {
        if (access.token() == null || access.token().isBlank()) return access;
        String parentSourceId;
        try {
            parentSourceId = sources.resolve(access.tenantId(), parent.getUrl()).getId();
        } catch (KitException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("Could not resolve a kit source for inherit '{}': {}",
                    parent.getUrl(), e.toString());
            return access.withToken(null);
        }
        if (topSourceId.equals(parentSourceId)) return access;
        log.debug("kit inherit '{}' resolves to source '{}' rather than '{}' — fetching it"
                + " without the top layer's credential", parent.getUrl(), parentSourceId,
                topSourceId);
        return access.withToken(null);
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
        for (Path src : KitTree.walkNoSymlinks(layerRoot)) {
            Path rel = layerRoot.relativize(src);
            if (rel.toString().isEmpty()) continue; // root itself
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
        }
    }
}
