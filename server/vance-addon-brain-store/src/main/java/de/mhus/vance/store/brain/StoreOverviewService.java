package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitLibraryEntryDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitLibraryService;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.shared.kit.KitException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The store as one screen: what is offered, what is owned, what is
 * installed here, and what could be updated.
 *
 * <p>Those are four questions with three different answerers — the store
 * knows what is offered, the delivery service knows what an account owns,
 * and only this installation knows what it has installed. <b>This class is
 * the join</b>, and that it has to be is not a shortcoming: a store that
 * knew what sat on which installation would be a telemetry database.
 *
 * <p>They come back as one list with a state per entry rather than four
 * lists, because the four overlap — a kit that is owned and installed and
 * updatable would otherwise appear three times, and the screen would have
 * to work out that they are the same thing.
 *
 * <p>Spec: {@code planning/kit-store.md} §3 S6, §7 Phase S3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreOverviewService {

    /** Where one kit stands, from this installation's point of view. */
    public enum EntryState {
        /** In the catalogue, not owned by this account. */
        OFFERED,
        /** Owned, not installed in this project. */
        OWNED,
        /** Owned and installed at the version the library currently offers. */
        INSTALLED,
        /** Owned and installed, but the library offers a newer version. */
        UPDATABLE
    }

    /** One row of the screen. */
    public record Entry(
            String sourceId,
            String sourceUrl,
            String path,
            String vendor,
            String kitId,
            String displayName,
            @Nullable String description,
            @Nullable String license,
            @Nullable String homepage,
            @Nullable String availableVersion,
            @Nullable String installedVersion,
            @Nullable Instant licenseExpiresAt,
            boolean downloadable,
            double averageStars,
            long ratingCount,
            long priceCents,
            @Nullable String currency,
            @Nullable Integer licenseTermDays,
            EntryState state) {}

    /** One store, as far as this user is concerned. */
    public record SourceView(
            String sourceId,
            String url,
            @Nullable String accountId,
            boolean reachable,
            @Nullable String problem,
            List<Entry> entries) {}

    private final KitSourceRegistry sources;
    private final KitLibraryService library;
    private final KitRecordStore recordStore;
    private final StoreClient client;
    private final StoreConnectionService connections;

    /**
     * Everything, per configured library.
     *
     * <p>One unreachable store contributes an explanation and no entries;
     * it must not blank out the others, and it must not look like an empty
     * catalogue either — "nothing for sale" and "could not ask" are
     * different answers and the screen has to be able to say which.
     */
    public List<SourceView> overview(String tenantId, String projectId, String userId) {
        List<SourceView> out = new ArrayList<>();
        for (KitSourceDto source : sources.configuredSources(tenantId)) {
            if (source.getType() != KitSourceType.LIBRARY) continue;
            out.add(viewOf(tenantId, projectId, userId, source));
        }
        return out;
    }

    private SourceView viewOf(
            String tenantId, String projectId, String userId, KitSourceDto source) {

        StoreConnectionService.Connection connection =
                connections.connectionOf(tenantId, userId, source);

        List<StoreClient.CatalogueEntry> offered;
        try {
            offered = client.catalogue(source);
        } catch (KitException e) {
            log.info("StoreOverviewService: store '{}' is not listable: {}",
                    source.getId(), e.getMessage());
            return new SourceView(source.getId(), source.getUrl(),
                    connection.accountId(), false, e.getMessage(), List.of());
        }

        // Owned kits come from the delivery service, which answers per link
        // token. Without a connection there is simply nothing owned to show —
        // not an error, just a store nobody has signed in to yet.
        List<KitLibraryEntryDto> owned = connection.isConnected()
                ? safeLibrary(tenantId, projectId, userId, source)
                : List.of();

        Map<String, String> installedVersions = installedVersions(tenantId, projectId, source);

        Map<String, StoreClient.Score> scores = new LinkedHashMap<>();
        Map<String, StoreClient.CatalogueEntry> offeredByPath = new LinkedHashMap<>();
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (StoreClient.CatalogueEntry entry : offered) {
            String path = path(entry.vendorName(), entry.kitId());
            if (entry.score() != null) scores.put(path, entry.score());
            offeredByPath.put(path, entry);
            byPath.put(path, new Entry(
                    source.getId(), source.getUrl(), path,
                    entry.vendorName(), entry.kitId(), entry.displayName(),
                    entry.description(), entry.license(), entry.homepage(),
                    entry.version(), null, null, true,
                    entry.score() == null ? 0d : entry.score().average(),
                    entry.score() == null ? 0L : entry.score().count(),
                    entry.priceCents(), entry.currency(), entry.licenseTermDays(),
                    EntryState.OFFERED));
        }
        // Owned entries win over catalogue ones: they carry the licence
        // expiry and the version this account may actually have, which can
        // be older than what the catalogue advertises.
        for (KitLibraryEntryDto entry : owned) {
            String installed = installedVersions.get(entry.getPath());
            byPath.put(entry.getPath(), new Entry(
                    source.getId(), source.getUrl(), entry.getPath(),
                    entry.getVendor() == null ? "" : entry.getVendor(),
                    entry.getKitId(), entry.getDisplayName(),
                    entry.getDescription(), entry.getLicense(), null,
                    entry.getVersion(), installed, entry.getLicenseExpiresAt(),
                    entry.isDownloadable(),
                    // The score comes from the catalogue, which the library
                    // listing knows nothing about — an owned kit would
                    // otherwise lose its stars the moment it is bought.
                    scores.containsKey(entry.getPath())
                            ? scores.get(entry.getPath()).average() : 0d,
                    scores.containsKey(entry.getPath())
                            ? scores.get(entry.getPath()).count() : 0L,
                    // Price too: an owned kit still shows what it costs,
                    // which is what somebody comparing a second seat wants.
                    offeredByPath.containsKey(entry.getPath())
                            ? offeredByPath.get(entry.getPath()).priceCents() : 0L,
                    offeredByPath.containsKey(entry.getPath())
                            ? offeredByPath.get(entry.getPath()).currency() : null,
                    offeredByPath.containsKey(entry.getPath())
                            ? offeredByPath.get(entry.getPath()).licenseTermDays() : null,
                    stateOf(installed, entry.getVersion())));
        }

        List<Entry> entries = new ArrayList<>(byPath.values());
        entries.sort(Comparator.comparing(Entry::vendor).thenComparing(Entry::kitId));
        return new SourceView(source.getId(), source.getUrl(),
                connection.accountId(), true, null, entries);
    }

    /**
     * Installed versions of this source's kits, keyed by library path.
     *
     * <p>Read off the install record's {@code commit}, which for a library
     * install reads {@code library:<version>} — the field records what was
     * installed, and for a library that is a version rather than a hash.
     */
    private Map<String, String> installedVersions(
            String tenantId, String projectId, KitSourceDto source) {

        Map<String, String> out = new LinkedHashMap<>();
        for (KitInstalledRecordDto record : recordStore.list(tenantId, projectId)) {
            if (record.getOrigin() == null) continue;
            if (!sameSource(record.getOrigin().getUrl(), source.getUrl())) continue;
            String path = record.getOrigin().getPath();
            if (path == null) continue;
            out.put(path, versionOf(record.getOrigin().getCommit()));
        }
        return out;
    }

    /**
     * {@code library:1.2.0} → {@code 1.2.0}.
     *
     * <p>Anything else is passed through: a record written by a git install
     * carries a commit hash, and showing it verbatim is more honest than
     * pretending it is a version.
     */
    private static @Nullable String versionOf(@Nullable String commit) {
        if (commit == null) return null;
        return commit.startsWith("library:") ? commit.substring("library:".length()) : commit;
    }

    private static EntryState stateOf(
            @Nullable String installedVersion, @Nullable String availableVersion) {
        if (installedVersion == null) return EntryState.OWNED;
        if (availableVersion == null) return EntryState.INSTALLED;
        // String equality, not version ordering. What matters is "is this
        // the one the library would hand over now" — comparing semver here
        // would invent an ordering the library has not agreed to, and a
        // downgrade is as much a reason to offer an update as an upgrade.
        return installedVersion.equals(availableVersion)
                ? EntryState.INSTALLED
                : EntryState.UPDATABLE;
    }

    private List<KitLibraryEntryDto> safeLibrary(
            String tenantId, String projectId, String userId, KitSourceDto source) {
        try {
            return library.list(tenantId, projectId, userId).stream()
                    .filter(entry -> source.getId().equals(entry.getSourceId()))
                    .toList();
        } catch (KitException e) {
            // The catalogue answered, the library did not — an expired or
            // revoked link, most likely. Showing the catalogue without the
            // purchases beats showing nothing.
            log.info("StoreOverviewService: library of '{}' is not listable: {}",
                    source.getId(), e.getMessage());
            return List.of();
        }
    }

    private static String path(@Nullable String vendor, String kitId) {
        return vendor == null || vendor.isBlank() ? kitId : vendor + "/" + kitId;
    }

    private static boolean sameSource(@Nullable String recordUrl, String sourceUrl) {
        if (recordUrl == null) return false;
        return normalize(recordUrl).equals(normalize(sourceUrl));
    }

    private static String normalize(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
