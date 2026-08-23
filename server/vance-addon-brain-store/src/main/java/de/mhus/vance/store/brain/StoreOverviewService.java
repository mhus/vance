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
        /**
         * Installed here, at the version the library currently offers.
         *
         * <p>Says nothing about ownership on purpose: an installation
         * knows what it has installed without asking anybody, and that
         * answer must not disappear when nobody is signed in.
         */
        INSTALLED,
        /** Installed here, but the library offers a different version. */
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
            /** What the vendor says it is for, and what it contains. */
            List<String> topics,
            List<String> contains,
            /**
             * The vendor's proven domain — the one line that answers "is
             * this really from them", so it travels with the entry rather
             * than being fetched per card.
             */
            @Nullable String vendorDomain,
            EntryState state) {}

    /** One store, as far as this user is concerned. */
    public record SourceView(
            String sourceId,
            /** What a person calls this store — its configured title, else its id. */
            String title,
            String url,
            @Nullable String accountId,
            boolean reachable,
            @Nullable String problem,
            /**
             * Whether the entitlements below are the account's actual ones.
             *
             * <p>False means the delivery service did not answer, so an entry
             * that is not marked {@link EntryState#OWNED} may well be owned —
             * and a screen that offers "Buy" on it is offering a second
             * purchase of something already paid for. Nothing upstream stops
             * that: the order goes through the store account rather than the
             * link, and {@code OrderService.create} has no "already owns it"
             * guard.
             *
             * <p>True without a connection is not a contradiction: there is no
             * account whose entitlements could be unknown, and buying needs an
             * account, so the question does not arise.
             */
            boolean ownershipKnown,
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
                connections.connectionOf(tenantId, userId, projectId, source);

        List<StoreClient.CatalogueEntry> offered;
        try {
            offered = client.catalogue(source);
        } catch (KitException e) {
            log.info("StoreOverviewService: store '{}' is not listable: {}",
                    source.getId(), e.getMessage());
            return new SourceView(source.getId(), titleOf(source), source.getUrl(),
                    connection.accountId(), false, e.getMessage(), false, List.of());
        }

        // Owned kits come from the delivery service, which answers per link
        // token. Without a connection there is simply nothing owned to show —
        // not an error, just a store nobody has signed in to yet.
        Entitlements entitlements = connection.isConnected()
                ? safeLibrary(tenantId, projectId, userId, source)
                : Entitlements.noAccount();
        List<KitLibraryEntryDto> owned = entitlements.entries();

        Map<String, String> installedVersions = installedVersions(tenantId, projectId, source);

        Map<String, StoreClient.Score> scores = new LinkedHashMap<>();
        Map<String, StoreClient.CatalogueEntry> offeredByPath = new LinkedHashMap<>();
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (StoreClient.CatalogueEntry entry : offered) {
            String path = path(entry.vendorName(), entry.kitId());
            if (entry.score() != null) scores.put(path, entry.score());
            offeredByPath.put(path, entry);
            // Whether it is installed here is this installation's own
            // knowledge, and it does not become unknowable because nobody
            // has signed in: without a link the owned list is empty, and
            // reading the record only there meant an installed kit was
            // offered as if it were new.
            String installed = installedVersions.get(path);
            byPath.put(path, new Entry(
                    source.getId(), source.getUrl(), path,
                    // Normalised like the owned branch below. `vendor` is
                    // declared non-null, but this one comes from the store's
                    // JSON and Jackson leaves an absent field null whatever
                    // @NullMarked says — and the sort at the end of this
                    // method reads it, so one such entry used to lose the
                    // whole overview rather than just its own row.
                    orBlank(entry.vendorName()), entry.kitId(), entry.displayName(),
                    entry.description(), entry.license(), entry.homepage(),
                    entry.version(), installed, null, true,
                    entry.score() == null ? 0d : entry.score().average(),
                    entry.score() == null ? 0L : entry.score().count(),
                    entry.priceCents(), entry.currency(), entry.licenseTermDays(),
                    orEmpty(entry.topics()), orEmpty(entry.contains()),
                    entry.vendorDomain(),
                    // Not stateOf() alone: with nothing installed this row
                    // is OFFERED, not OWNED — ownership is what the link
                    // answers, and there is no link here. When the link
                    // exists but did not answer, this stays OFFERED too and
                    // SourceView.ownershipKnown carries the doubt: a fourth
                    // state per entry would say the same thing once per row
                    // about a fact that holds for the whole source.
                    installed == null
                            ? EntryState.OFFERED
                            : stateOf(installed, entry.version())));
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
                    // Tags come from the catalogue as well: the library
                    // listing describes an entitlement, not a product.
                    offeredByPath.containsKey(entry.getPath())
                            ? orEmpty(offeredByPath.get(entry.getPath()).topics()) : List.of(),
                    offeredByPath.containsKey(entry.getPath())
                            ? orEmpty(offeredByPath.get(entry.getPath()).contains()) : List.of(),
                    offeredByPath.containsKey(entry.getPath())
                            ? offeredByPath.get(entry.getPath()).vendorDomain() : null,
                    stateOf(installed, entry.getVersion())));
        }

        List<Entry> entries = new ArrayList<>(byPath.values());
        entries.sort(Comparator.comparing(Entry::vendor).thenComparing(Entry::kitId));
        // `problem` stays reserved for "the store could not be asked at all" —
        // an unlistable library is a partial answer, and the screen tells that
        // apart by `ownershipKnown` rather than by a banner that would read
        // like the whole shop is down.
        return new SourceView(source.getId(), titleOf(source), source.getUrl(),
                connection.accountId(), true, null, entitlements.known(), entries);
    }

    private static List<String> orEmpty(@Nullable List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String orBlank(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** Its configured title, else its id — the id is a handle, not a name. */
    private static String titleOf(KitSourceDto source) {
        return source.getTitle() == null || source.getTitle().isBlank()
                ? source.getId()
                : source.getTitle();
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

    /**
     * What the delivery service said this account owns — and whether it said
     * anything at all.
     *
     * <p>The flag is the whole point of the type. An empty list on its own
     * reads as "owns nothing", which for an unreachable delivery service is a
     * claim nobody made, and the screen acts on it: an owned-but-not-installed
     * kit falls back to {@code OFFERED} with a live Buy button. The purchase
     * then goes through the store account rather than the link, so it
     * succeeds, grants a second entitlement and issues a second invoice.
     */
    private record Entitlements(List<KitLibraryEntryDto> entries, boolean known) {

        static Entitlements of(List<KitLibraryEntryDto> entries) {
            return new Entitlements(entries, true);
        }

        /** Nobody is signed in: there is no account, so nothing is unknown. */
        static Entitlements noAccount() {
            return new Entitlements(List.of(), true);
        }

        static Entitlements unknown() {
            return new Entitlements(List.of(), false);
        }
    }

    private Entitlements safeLibrary(
            String tenantId, String projectId, String userId, KitSourceDto source) {
        try {
            return Entitlements.of(library.list(tenantId, projectId, userId).stream()
                    .filter(entry -> source.getId().equals(entry.getSourceId()))
                    .toList());
        } catch (KitException e) {
            // The catalogue answered, the library did not — an expired or
            // revoked link, most likely. Showing the catalogue without the
            // purchases beats showing nothing, but it must not be shown as if
            // the purchases were known to be absent.
            log.info("StoreOverviewService: library of '{}' is not listable: {}",
                    source.getId(), e.getMessage());
            return Entitlements.unknown();
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
