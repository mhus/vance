package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Fetches a kit from a tenant's library.
 *
 * <p>The second implementation of {@link KitSourceLoader}, and the
 * reason the interface exists: a library kit is not addressed by where
 * it lies but by what the caller is entitled to, so nothing about
 * cloning applies. Once the tree is on disk, everything downstream —
 * signature check, inherit resolution, policy, records — proceeds
 * exactly as for a git kit.
 *
 * <p>Spec: {@code planning/kit-shop.md} §5.5.
 */
@Service
@Slf4j
public class LibraryKitSourceLoader implements KitSourceLoader {

    /** Kits are small; a slow library should fail rather than hang an install. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean supports(KitSourceType type) {
        return type == KitSourceType.LIBRARY;
    }

    @Override
    public KitRepoLoader.LoadedKit load(
            KitInheritDto source, KitSourceDto config, @Nullable String token, Path target) {

        String kitId = source.getPath();
        if (kitId == null || kitId.isBlank()) {
            throw new KitException("a library kit needs a path: it identifies which kit of the"
                    + " library is meant (source " + config.getId() + ")");
        }
        if (token == null || token.isBlank()) {
            // Which kits exist in a library depends on who is asking, so an
            // anonymous request cannot even be answered meaningfully.
            throw new KitException("library source '" + config.getId()
                    + "' needs a token — a library serves what a tenant is entitled to,"
                    + " and without a credential there is no tenant");
        }

        URI uri = URI.create(trimTrailingSlash(config.getUrl())
                + "/library/kits/" + encodePath(kitId) + "/download");
        log.debug("LibraryKitSourceLoader: fetching {}", uri);

        HttpResponse<InputStream> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KitException("library '" + config.getId() + "' is not reachable: "
                    + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new KitException(describeFailure(response.statusCode(), kitId, config));
        }

        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            unpack(zip, target);
        } catch (IOException e) {
            throw new KitException("failed to unpack kit '" + kitId + "' from library '"
                    + config.getId() + "'", e);
        }

        Path descriptorFile = target.resolve("kit.yaml");
        if (!Files.isRegularFile(descriptorFile)) {
            throw new KitException("library '" + config.getId() + "' delivered '" + kitId
                    + "' without a kit.yaml");
        }
        KitDescriptorDto descriptor;
        try {
            descriptor = KitYamlMapper.parseDescriptor(Files.readString(descriptorFile));
        } catch (IOException e) {
            throw new KitException("failed to read the delivered kit.yaml", e);
        }

        // The commit field records what was installed. A library has no
        // commits, so the version stands in — it is what an update compares
        // against, and "library:1.2.0" reads as what it is.
        String stamp = "library:" + (descriptor.getVersion() == null
                ? "unversioned" : descriptor.getVersion());
        return new KitRepoLoader.LoadedKit(target, target, stamp, descriptor, false);
    }

    /**
     * Turn a status code into something actionable. The three that matter
     * mean quite different things, and "download failed" would hide which.
     */
    private static String describeFailure(int status, String kitId, KitSourceDto config) {
        return switch (status) {
            case 401, 403 -> "library '" + config.getId() + "' rejected the token";
            case 404 -> "kit '" + kitId + "' is not in your library at '" + config.getId() + "'";
            case 410 -> "your licence for '" + kitId + "' does not entitle any published version";
            default -> "library '" + config.getId() + "' returned HTTP " + status
                    + " for kit '" + kitId + "'";
        };
    }

    /**
     * Unpack into {@code target}, refusing entries that would land
     * outside it.
     *
     * <p>The archive comes from a remote service. A path traversal check
     * here is not a comment on that service's trustworthiness — it is
     * that an install that writes outside its target directory is
     * unrecoverable, and the check costs one comparison.
     */
    private static void unpack(ZipInputStream zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            Path destination = root.resolve(entry.getName()).normalize();
            if (!destination.startsWith(root)) {
                throw new IOException("archive entry '" + entry.getName()
                        + "' would be written outside the target directory");
            }
            Files.createDirectories(destination.getParent());
            Files.copy(zip, destination);
        }
    }

    /**
     * Percent-encode a path, keeping {@code /} as a separator.
     *
     * <p>The path comes from a kit reference, which comes from a document.
     * Interpolating it raw lets a {@code ?} or {@code #} reshape the
     * request against someone else's host — a clean 404 is the only
     * acceptable outcome for a nonsensical path.
     */
    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (String segment : path.split("/", -1)) {
            if (out.length() > 0) out.append('/');
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
