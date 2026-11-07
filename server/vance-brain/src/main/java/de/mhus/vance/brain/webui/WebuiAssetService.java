package de.mhus.vance.brain.webui;

import de.mhus.vance.shared.document.BinaryLookupResult;
import de.mhus.vance.shared.document.DocumentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * Finds a web UI asset: the address is checked ({@link WebuiAssetPath}), the file is looked up through the document
 * cascade as bytes (project → {@code _tenant} → classpath), and an SVG goes through the {@link SvgSanitizer} on the
 * way out. Anything that is not served — a bad address, nothing there, too large, an SVG the sanitizer refuses — is
 * the same answer, empty: nothing here tells the caller which of those it was.
 */
@Service
@RequiredArgsConstructor
public class WebuiAssetService {

    /** The largest asset served, in bytes. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private final DocumentService documentService;

    /** A file ready to serve. */
    public record Asset(byte[] content, String contentType, String etag, boolean svg) {}

    /**
     * @param requested the path after {@code /webui/}
     */
    public Optional<Asset> find(String tenant, String project, String requested) {
        Optional<WebuiAssetPath> path = WebuiAssetPath.parse(requested);
        if (path.isEmpty()) return Optional.empty();
        Optional<BinaryLookupResult> hit =
                documentService.lookupCascadeBytes(tenant, project, path.get().cascadePath(), MAX_BYTES);
        if (hit.isEmpty()) return Optional.empty();

        WebuiAssetPath.Type type = path.get().type();
        byte[] bytes = hit.get().content();
        if (type == WebuiAssetPath.Type.SVG) {
            Optional<byte[]> clean = SvgSanitizer.sanitize(bytes);
            if (clean.isEmpty()) return Optional.empty();
            bytes = clean.get();
        }
        return Optional.of(new Asset(
                bytes,
                type.contentType(),
                '"' + DigestUtils.md5DigestAsHex(bytes) + '"',
                type == WebuiAssetPath.Type.SVG));
    }
}
