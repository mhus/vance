package de.mhus.vance.brain.servertool;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Provisions the bundled server-tool catalog into a tenant's
 * {@code _vance} system project. Idempotent — only documents that do
 * not yet exist by {@code (tenantId, _vance, name)} are created.
 * Existing documents are left untouched so tenant edits survive
 * across restarts (deleting a document is the documented way to
 * roll back to the bundled default — the next bootstrap recreates
 * it).
 *
 * <p>Called from the access controller right after
 * {@link HomeBootstrapService#ensureVance} so every authenticated
 * tenant sees the defaults without an explicit migration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerToolBootstrapService {

    private final BundledServerToolRegistry registry;
    private final ServerToolService serverToolService;

    public void ensureSystemTools(String tenantId) {
        int created = 0;
        for (BundledServerTool bundled : registry.all()) {
            if (serverToolService.findDocument(
                    tenantId,
                    HomeBootstrapService.VANCE_PROJECT_NAME,
                    bundled.name()).isPresent()) {
                continue;
            }
            ServerToolDocument doc = ServerToolDocument.builder()
                    .name(bundled.name())
                    .type(bundled.type())
                    .description(bundled.description())
                    .parameters(new java.util.LinkedHashMap<>(bundled.parameters()))
                    .labels(new ArrayList<>(bundled.labels()))
                    .enabled(bundled.enabled())
                    .primary(bundled.primary())
                    .build();
            serverToolService.create(tenantId, HomeBootstrapService.VANCE_PROJECT_NAME, doc);
            created++;
        }
        if (created > 0) {
            log.info("ServerToolBootstrap: provisioned {} system tools into tenantId='{}' project='{}'",
                    created, tenantId, HomeBootstrapService.VANCE_PROJECT_NAME);
        }
    }
}
