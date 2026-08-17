package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitLibraryEntryDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lists what a tenant may install from their configured libraries.
 *
 * <p>Read-only and on demand — nothing is cached and nothing is fetched
 * in the background. A library is someone else's service; asking it only
 * when a person is looking at the screen keeps an install of Vancetope
 * from depending on its availability.
 *
 * <p>Spec: {@code planning/kit-shop.md} §7 Phase D.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitLibraryService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final KitSourceRegistry sources;
    private final KitRecordStore recordStore;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Everything the tenant's libraries offer, marked up with what is
     * already installed in {@code projectId}.
     *
     * <p>A library that cannot be reached contributes nothing and a
     * warning — one unreachable service must not blank out the others.
     */
    public List<KitLibraryEntryDto> list(
            String tenantId, String projectId, @Nullable String token) {
        Set<String> installed = installedKeys(tenantId, projectId);
        List<KitLibraryEntryDto> out = new ArrayList<>();
        for (KitSourceDto source : sources.configuredSources(tenantId)) {
            if (source.getType() != KitSourceType.LIBRARY) continue;
            try {
                out.addAll(fetch(source, token, installed));
            } catch (KitException e) {
                log.warn("KitLibraryService: library '{}' is not listable: {}",
                        source.getId(), e.getMessage());
            }
        }
        return out;
    }

    private List<KitLibraryEntryDto> fetch(
            KitSourceDto source, @Nullable String token, Set<String> installed) {
        if (token == null || token.isBlank()) {
            throw new KitException("no token for library '" + source.getId() + "'");
        }
        URI uri = URI.create(trimTrailingSlash(source.getUrl()) + "/library/kits");
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KitException("not reachable: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new KitException("returned HTTP " + response.statusCode());
        }

        List<RemoteEntry> remote;
        try {
            remote = json.readValue(response.body(),
                    json.getTypeFactory().constructCollectionType(List.class, RemoteEntry.class));
        } catch (RuntimeException e) {
            throw new KitException("returned something that is not a kit list", e);
        }

        List<KitLibraryEntryDto> out = new ArrayList<>(remote.size());
        for (RemoteEntry entry : remote) {
            String path = libraryPath(entry.vendor(), entry.kitId());
            out.add(KitLibraryEntryDto.builder()
                    .sourceUrl(source.getUrl())
                    .sourceId(source.getId())
                    .path(path)
                    .kitId(entry.kitId())
                    .vendor(entry.vendor())
                    .displayName(entry.displayName() == null ? entry.kitId() : entry.displayName())
                    .description(entry.description())
                    .license(entry.license())
                    .version(entry.version())
                    .licenseExpiresAt(entry.licenseExpiresAt())
                    .downloadable(entry.downloadable())
                    .installed(installed.contains(
                            KitRecordId.hash(source.getUrl(), path)))
                    .build());
        }
        return out;
    }

    /**
     * Which kits this project already has, keyed by the coordinate hash.
     *
     * <p>Compared by hash rather than by record id: the id also contains
     * the kit's declared name, which comes from its {@code kit.yaml} and
     * may differ from the display name the library shows. The hash is
     * derived from {@code (url, path)} alone and is therefore the same on
     * both sides — which is what "the same kit" means here.
     */
    private Set<String> installedKeys(String tenantId, String projectId) {
        Set<String> keys = new HashSet<>();
        for (KitInstalledRecordDto record : recordStore.list(tenantId, projectId)) {
            if (record.getOrigin() == null) continue;
            keys.add(KitRecordId.hash(
                    record.getOrigin().getUrl(), record.getOrigin().getPath()));
        }
        return keys;
    }

    /** The delivery service's wire shape, mirrored only as far as needed. */
    private record RemoteEntry(
            String kitId,
            @Nullable String vendor,
            @Nullable String displayName,
            @Nullable String description,
            @Nullable String license,
            @Nullable String version,
            @Nullable Instant licenseExpiresAt,
            boolean downloadable) {}

    /**
     * How a kit is addressed inside a library: {@code vendor/kitId}.
     *
     * <p>Two vendors may use the same kit id, so the id alone does not
     * identify anything — which is also why the delivery endpoint takes
     * both segments.
     */
    static String libraryPath(@Nullable String vendor, String kitId) {
        return vendor == null || vendor.isBlank() ? kitId : vendor + "/" + kitId;
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
