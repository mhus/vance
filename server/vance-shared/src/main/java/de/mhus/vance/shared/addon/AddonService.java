package de.mhus.vance.shared.addon;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code addons} collection. Every read/write of an
 * {@link AddonDocument} from anywhere else in the codebase must go
 * through this service — direct repository access is package-private.
 *
 * <p>v1 surface is intentionally small: list the active rows for
 * {@code GET /face/addons}, list all rows for future admin views,
 * look up by name, and an idempotent {@link #ensure} entry point that
 * the bootstrap can use to seed bundled addons. Install / disable /
 * remove flows are not in v1 — the seed populates rows on first boot
 * and admins can flip {@code enabled} directly in Mongo until a
 * proper admin API exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddonService {

    private final AddonRepository repository;

    /**
     * All enabled addons, sorted by name. Backs {@code GET /face/addons}.
     */
    public List<AddonDocument> listEnabled() {
        return repository.findByEnabledTrueOrderByNameAsc();
    }

    /**
     * Every row including disabled ones — for the eventual admin UI.
     */
    public List<AddonDocument> listAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public Optional<AddonDocument> findByName(String name) {
        return repository.findByName(name);
    }

    /**
     * Idempotent seed: insert if absent, leave intact otherwise.
     * Used by the bootstrap to register bundled addons without
     * stomping on an admin's later {@code enabled=false} edit.
     */
    public AddonDocument ensure(String name, String path) {
        return repository.findByName(name).orElseGet(() -> {
            AddonDocument doc = AddonDocument.builder()
                    .name(name)
                    .path(path)
                    .enabled(true)
                    .build();
            AddonDocument saved = repository.save(doc);
            log.info("AddonService seeded addon '{}' path='{}'", name, path);
            return saved;
        });
    }

    /**
     * Create a new addon row. Fails if {@code name} already exists —
     * use {@link #ensure(String, String)} for idempotent seeding.
     */
    public AddonDocument create(String name, String path) {
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("Addon '" + name + "' already exists");
        }
        AddonDocument doc = AddonDocument.builder()
                .name(name)
                .path(path)
                .enabled(true)
                .build();
        AddonDocument saved = repository.save(doc);
        log.info("AddonService created addon '{}' path='{}'", name, path);
        return saved;
    }

    /**
     * Change the source path of an existing addon. The name is
     * immutable — there is no rename operation, since the name doubles
     * as the on-disk directory under {@code /shared/addons/<name>/}.
     */
    public AddonDocument updatePath(String name, String path) {
        AddonDocument doc = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Addon '" + name + "' not found"));
        if (java.util.Objects.equals(doc.getPath(), path)) {
            return doc;
        }
        doc.setPath(path);
        AddonDocument saved = repository.save(doc);
        log.info("AddonService updated addon '{}' path → '{}'", name, path);
        return saved;
    }

    /**
     * Flip {@code enabled}. Returns the post-update row; throws if the
     * addon doesn't exist.
     */
    public AddonDocument setEnabled(String name, boolean enabled) {
        AddonDocument doc = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Addon '" + name + "' not found"));
        if (doc.isEnabled() == enabled) {
            return doc;
        }
        doc.setEnabled(enabled);
        AddonDocument saved = repository.save(doc);
        log.info("AddonService set addon '{}' enabled={}", name, enabled);
        return saved;
    }

    /**
     * Hard-delete an addon row. The cached {@code /shared/addons/<name>/}
     * directory on disk is left alone — a separate cleanup step (cronjob
     * or admin command) reclaims it.
     */
    public void delete(String name) {
        AddonDocument doc = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Addon '" + name + "' not found"));
        repository.delete(doc);
        log.info("AddonService deleted addon '{}'", name);
    }
}
