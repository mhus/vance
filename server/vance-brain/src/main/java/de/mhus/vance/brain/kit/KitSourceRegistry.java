package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.KitSourcesDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Answers, for a given kit url, where that kit is allowed to come from
 * and under which rules.
 *
 * <p>Reads {@code _vance/config/kit-sources.yaml} from the tenant-wide
 * {@code _tenant} project. The document is optional and additive: with
 * no configuration, every url resolves to a guessed git or folder source
 * with no signature requirement — which is exactly how kits behaved
 * before sources existed.
 *
 * <p>Matched by <b>longest url prefix</b>. That lets a tenant configure
 * a whole host loosely and one repository on it strictly, and it keeps
 * the kit reference itself a plain {@code (url, path)} pair: adding a
 * source never changes any installed kit's identity.
 *
 * <p>Spec: {@code planning/kit-shop.md} §5.1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitSourceRegistry {

    public static final String SOURCES_PATH = "_vance/config/kit-sources.yaml";

    /** Where a tenant's kit library lives unless the tenant says otherwise. */
    public static final String DEFAULT_LIBRARY_ID = "vancetope-library";
    public static final String DEFAULT_LIBRARY_URL = "https://library.vancetope.com";

    private final DocumentService documentService;

    /**
     * The source covering {@code url}.
     *
     * <p>Never null: a url that no configured source claims still gets a
     * source, guessed from its shape. Refusing unknown urls would mean a
     * tenant has to enumerate every colleague's repository before
     * installing from it — a lockdown nobody asked for, and one that
     * would break every existing installation the moment the file
     * appears.
     */
    public KitSourceDto resolve(String tenantId, String url) {
        String needle = normalize(url);
        KitSourceDto best = null;
        int bestLength = -1;
        for (KitSourceDto candidate : configuredSources(tenantId)) {
            String prefix = normalize(candidate.getUrl());
            if (prefix.isEmpty() || !needle.startsWith(prefix)) continue;
            if (prefix.length() > bestLength) {
                best = candidate;
                bestLength = prefix.length();
            }
        }
        if (best != null) return best;

        KitSourceType guessed = KitSourceType.guessFrom(url);
        return KitSourceDto.builder()
                .id("(unconfigured)")
                .type(guessed)
                .url(url)
                .signature(KitSignaturePolicy.defaultFor(guessed))
                .build();
    }

    /**
     * Everything this tenant may draw from: the configured sources plus
     * the default library, unless the tenant replaced it by configuring
     * the same url itself.
     */
    public List<KitSourceDto> configuredSources(String tenantId) {
        List<KitSourceDto> sources = new ArrayList<>(readDocument(tenantId));
        boolean libraryOverridden = sources.stream()
                .anyMatch(s -> normalize(s.getUrl()).equals(normalize(DEFAULT_LIBRARY_URL)));
        if (!libraryOverridden) {
            sources.add(defaultLibrary());
        }
        return sources;
    }

    /**
     * The built-in library entry. Present from the start so a tenant sees
     * what the default is and can override it by url — the entry being
     * configured is not the same as the library being reachable, which
     * needs a loader that does not exist yet.
     */
    public static KitSourceDto defaultLibrary() {
        return KitSourceDto.builder()
                .id(DEFAULT_LIBRARY_ID)
                .type(KitSourceType.LIBRARY)
                .url(DEFAULT_LIBRARY_URL)
                .signature(KitSignaturePolicy.REQUIRED)
                .build();
    }

    private List<KitSourceDto> readDocument(String tenantId) {
        Optional<DocumentDocument> doc = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, SOURCES_PATH);
        if (doc.isEmpty()) return List.of();
        String content = readText(doc.get());
        if (content == null || content.isBlank()) return List.of();
        try {
            KitSourcesDto parsed = KitYamlMapper.parseSources(content);
            return parsed.getSources();
        } catch (KitException e) {
            // A broken sources file must not silently widen what is allowed:
            // falling back to "no sources" keeps the pre-configuration
            // behaviour, which is the narrower of the two readings.
            log.warn("KitSourceRegistry: {} in tenant '{}' is malformed: {} — "
                    + "ignoring it, kits load as if unconfigured",
                    SOURCES_PATH, tenantId, e.getMessage());
            return List.of();
        }
    }

    private @Nullable String readText(DocumentDocument doc) {
        String content = documentService.readContent(doc);
        if (content != null) return content;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to read " + SOURCES_PATH, e);
        }
    }

    /**
     * Compare urls without the noise that does not change where they
     * point: case of the scheme and host, and a trailing slash.
     */
    private static String normalize(@Nullable String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
