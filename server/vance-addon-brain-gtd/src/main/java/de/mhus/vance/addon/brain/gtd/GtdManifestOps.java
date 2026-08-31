package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Mutations of a GTD {@code _app.yaml} — today that is the per-bucket manual
 * order of §8b, and this is the only place that writes it. Everything above
 * (controller, tools) hands in what changed and gets the result back; nobody
 * else touches the manifest document.
 *
 * <p><b>Mutations of one manifest are serialised.</b> Read-modify-write on a
 * whole document has no field-level merge and no optimistic guard —
 * {@code DocumentService.update} re-reads the row by id and writes over it, so
 * the version the caller loaded is never compared. Two reorders arriving
 * together both read <i>n</i> ids and both write their own <i>n</i>: the first
 * one written is gone, with no error and no trace. The lock is per
 * {@code (tenant, project, folder)} and JVM-local — enough, because a project's
 * documents are served by its home pod, and the honest alternative (a version
 * on the update funnel) is a change to {@code DocumentService}, not to this app.
 *
 * <p><b>The scan happens inside the lock.</b> Resyncing an order needs to know
 * which Actions are in the bucket <i>now</i>; reading that outside would let a
 * concurrent capture or completion decide the answer.
 *
 * <p>Data ownership: every document touch goes through {@link DocumentService}.
 */
@Component
@Slf4j
public class GtdManifestOps {

    private static final String YAML_MIME = "application/yaml";
    private static final List<String> KINDS = List.of("application", GtdConfig.APP_NAME);

    /**
     * Striped rather than one lock per manifest: a map keyed by folder would
     * have to be pruned, and a manifest write is short enough that the
     * occasional collision between two unrelated folders costs nothing.
     */
    private static final int LOCK_STRIPES = 32;

    private static final ReentrantLock[] LOCKS = newLocks();

    private static ReentrantLock[] newLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new ReentrantLock();
        return locks;
    }

    private final DocumentService documentService;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;
    private final SecurityContextFactory contextFactory;

    public GtdManifestOps(DocumentService documentService,
                          GtdFolderReader folderReader,
                          GtdService gtdService,
                          SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.gtdService = gtdService;
        this.contextFactory = contextFactory;
    }

    /**
     * Record a new manual order for one bucket and return the fresh scan, so the
     * caller can render the result of its own write without a second round trip.
     *
     * <p>The scan is <b>read again</b> after the write rather than patched in
     * memory: what goes back to the caller is then what the database holds, not
     * what we believe we stored. A drag happens at human speed, so the second
     * read is worth that.
     *
     * @param requestedIds the ids the caller ordered — may be a subset of the
     *                     bucket when a filter narrowed the list; see
     *                     {@link GtdService#resyncBucketOrder}.
     */
    public GtdFolderReader.Scan reorderBucket(String tenantId, String projectId, String folder,
                                              GtdBucket bucket, List<String> requestedIds,
                                              LocalDate today, @Nullable String userId) {
        ReentrantLock lock = lockFor(tenantId, projectId, folder);
        lock.lock();
        try {
            GtdFolderReader.Scan scan = folderReader.scan(tenantId, projectId, folder);
            List<GtdAction> bucketed = gtdService.computeBuckets(scan, today).get(bucket);
            List<String> resynced = gtdService.resyncBucketOrder(bucket, bucketed,
                    scan.config().bucketOrder().getOrDefault(bucket, List.of()),
                    requestedIds);
            writeBucketOrder(scan.manifest(), bucket, resynced, userId);
            log.info("GtdManifestOps.reorderBucket tenant='{}' folder='{}' bucket={} ids={}",
                    tenantId, folder, bucket.wireName(), resynced.size());
            return folderReader.scan(tenantId, projectId, folder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Write {@code gtd.<bucket>Order} back into the manifest, keeping every
     * other key — title, description, the directory names, the contexts, the
     * other buckets' orders, and any block a future app face adds beside
     * {@code gtd:}. An empty order removes the key, which puts the bucket back
     * on its default sequence.
     *
     * <p>This is a full YAML round-trip through {@link ApplicationCodec} — the
     * same one every other app manifest goes through. It normalises formatting
     * and does not preserve comments; a manifest is a manifest, not a config
     * file somebody annotates.
     */
    private void writeBucketOrder(DocumentDocument manifest, GtdBucket bucket,
                                  List<String> order, @Nullable String userId) {
        ApplicationDocument parsed = parse(manifest);
        Map<String, Object> config = new LinkedHashMap<>(parsed.config());
        Map<String, Object> block = blockOf(config);
        String orderKey = orderKey(bucket);
        if (order.isEmpty()) block.remove(orderKey);
        else block.put(orderKey, new ArrayList<>(order));
        config.put(GtdConfig.APP_NAME, block);

        ApplicationDocument next = new ApplicationDocument(
                "application", GtdConfig.APP_NAME, parsed.title(), parsed.description(),
                config, new LinkedHashMap<>(parsed.extra()));
        documentService.update(manifest.getId(), manifest.getTitle(), KINDS,
                ApplicationCodec.serialize(next, YAML_MIME),
                null, null, null, null, YAML_MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(
                        manifest.getTenantId(), userId, manifest.getPath()));
    }

    /** The manifest key holding a bucket's manual order. */
    public static String orderKey(GtdBucket bucket) {
        return bucket.wireName() + "Order";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> blockOf(Map<String, Object> config) {
        return config.get(GtdConfig.APP_NAME) instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
    }

    /**
     * Parse the manifest and refuse a folder that belongs to another app. The
     * write below puts {@code app: gtd} back unconditionally, so without this a
     * reorder aimed at, say, a workbook folder would convert its manifest and
     * report success.
     */
    private ApplicationDocument parse(DocumentDocument manifest) {
        String mime = manifest.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException("GTD manifest '" + manifest.getPath()
                    + "' has mime '" + mime + "' — must be YAML or JSON.");
        }
        ApplicationDocument parsed;
        try {
            parsed = ApplicationCodec.parse(documentService.readContent(manifest), mime);
        } catch (RuntimeException e) {
            throw new ToolException("Could not parse GTD manifest '"
                    + manifest.getPath() + "': " + e.getMessage());
        }
        String app = parsed.app();
        if (!app.isBlank() && !GtdConfig.APP_NAME.equals(app)) {
            throw new ToolException("'" + manifest.getPath() + "' is an app: " + app
                    + ", not a GTD folder — refusing to overwrite its manifest.");
        }
        return parsed;
    }

    private static ReentrantLock lockFor(String tenantId, String projectId, String folder) {
        int hash = (tenantId + '\0' + projectId + '\0' + folder).hashCode();
        return LOCKS[Math.floorMod(hash, LOCK_STRIPES)];
    }
}
