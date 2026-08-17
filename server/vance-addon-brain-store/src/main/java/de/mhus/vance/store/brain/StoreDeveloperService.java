package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitWorkspace;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Publishing a project as a release.
 *
 * <p>The way in is the <b>existing export</b>: a kit tree is written the
 * same way it would be written into a git repository, then packed and
 * handed to the store. Nothing here knows how to build a kit — that
 * knowledge is in {@link KitService} and would rot if it were copied.
 *
 * <p><b>Why the addon and not the brain.</b> {@code KitExporter} already
 * has a strategy for destinations, and a store-aware one would fit. It
 * would also put knowledge of stores, link tokens and multipart uploads
 * into the public core, which has no business with any of it. Exporting to
 * a folder and packing that folder gets the same result and leaves the
 * boundary where it is.
 *
 * <p>Spec: {@code planning/kit-store.md} §3 S15, §7 Phase D1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreDeveloperService {

    private final KitService kitService;
    private final KitWorkspace workspace;
    private final StoreClient client;

    /**
     * Export this project and submit it as a version.
     *
     * <p>The project has to be a kit source — that is, carry an authoring
     * manifest. Exporting one that is not says so in the export's own
     * words, which name the thing to do about it.
     */
    public StoreClient.ReleaseRequest publish(
            String tenantId, String projectId, String actor,
            KitSourceDto source, String linkToken,
            String vendorName, String kitId, String version,
            @Nullable String vaultPassword) {

        Path staging = workspace.allocate("store-publish");
        Path archive = null;
        try {
            kitService.export(tenantId, KitExportRequestDto.builder()
                    .projectId(projectId)
                    // A folder url: the exporter's folder target writes
                    // straight into it, with no git anywhere.
                    .url(staging.toUri().toString())
                    .vaultPassword(vaultPassword)
                    .build(), actor);

            archive = Files.createTempFile("kit-", ".zip");
            zip(kitRootOf(staging), archive);
            log.info("StoreDeveloperService: publishing {}/{} {} from project {}",
                    vendorName, kitId, version, projectId);
            return client.uploadRelease(
                    source, linkToken, vendorName, kitId, version, archive);
        } catch (IOException e) {
            throw new UncheckedIOException("could not pack the kit for upload", e);
        } finally {
            workspace.remove(staging);
            deleteQuietly(archive);
        }
    }

    /**
     * Where the kit tree actually landed.
     *
     * <p>The export writes into the sub-path its manifest names — a kit
     * repository usually holds several kits, so {@code origin.path} is a
     * real directory in the exported tree. An upload is a <b>release</b>
     * and not a repository: the store unpacks it and expects
     * {@code kit.yaml} at the top. Packing the staging root instead
     * shipped {@code acmelabs/widgets/kit.yaml}, and delivery refused the
     * download with "no kit.yaml" — the archive was well-formed and about
     * the wrong thing.
     *
     * <p>Found rather than derived, so this does not have to repeat the
     * exporter's rule for where the sub-path comes from.
     */
    private static Path kitRootOf(Path staging) throws IOException {
        try (Stream<Path> walk = Files.walk(staging)) {
            return walk.filter(path -> path.getFileName().toString().equals("kit.yaml"))
                    .filter(Files::isRegularFile)
                    // Shallowest wins: an inherited kit may carry its own.
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .map(Path::getParent)
                    // A KitException and not an IOException: the caller wraps
                    // those into "could not pack the kit for upload", and the
                    // one sentence worth reading — which file is missing —
                    // would never reach the person who has to fix it.
                    .orElseThrow(() -> new KitException("the export produced no kit.yaml"
                            + " — check that this project is a kit source"));
        }
    }

    /**
     * Pack a directory, paths relative.
     *
     * <p>Symlinks are not followed and directories are not entries: the
     * store unpacks this into its release storage, and an archive that can
     * write outside its own directory is exactly what that side refuses.
     * Producing one here would only turn a clear upload into a rejected
     * one.
     */
    private static void zip(Path tree, Path archive) throws IOException {
        try (OutputStream out = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(out);
                Stream<Path> walk = Files.walk(tree)) {

            for (Path file : walk.sorted().toList()) {
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                String name = tree.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // A leftover file in a temp directory is not worth failing a
            // publish that already succeeded.
            log.debug("StoreDeveloperService: could not remove {}: {}", path, e.getMessage());
        }
    }
}
