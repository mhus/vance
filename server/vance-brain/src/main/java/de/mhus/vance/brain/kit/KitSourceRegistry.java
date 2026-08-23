package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.KitSourcesDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.kit.KitException;
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
 * <p>An <em>unreadable</em> document is a different matter from an absent
 * one: it is refused, not ignored. See {@link #readDocument}.
 *
 * <p>Matched by <b>longest url prefix</b>, and only at a path-segment
 * boundary. That lets a tenant configure a whole host loosely and one
 * repository on it strictly, and it keeps the kit reference itself a plain
 * {@code (url, path)} pair: adding a source never changes any installed
 * kit's identity.
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
            if (prefix.isEmpty() || !coversUrl(prefix, needle)) continue;
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
     * what the default is and can override it by url.
     *
     * <p>No {@code storeUrl}: the default library serves its store front
     * on the same host, under {@code /store/…}.
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
            // Fail closed. "No sources" is not the narrower reading, it is the
            // widest one: with the list empty no prefix claims the url any
            // more, resolve() guesses GIT, and KitSignaturePolicy.defaultFor
            // hands back OFF — so a typo in one line would turn
            // `signature: required` into no signature at all for every source
            // in the file, visible as a single WARN. That is exactly what
            // KitYamlMapper.parseSources is strict about, and swallowing the
            // exception here undid its strictness one frame later.
            log.warn("KitSourceRegistry: {} in tenant '{}' is malformed: {} — "
                    + "refusing every kit source resolution until it is fixed",
                    SOURCES_PATH, tenantId, e.getMessage());
            throw new KitException(SOURCES_PATH + " in tenant '" + tenantId
                    + "' is malformed and cannot be trusted to say which sources are"
                    + " allowed and which need a signature — fix it before installing"
                    + " or updating kits (" + e.getMessage() + ")", e);
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
     * Whether a configured source url covers a kit url.
     *
     * <p>Prefix match, but only at a segment boundary. A bare
     * {@code startsWith} let {@code https://github.com/acme-sandbox-evil/x.git}
     * be claimed by the source configured for {@code https://github.com/acme}.
     * Usually that only makes a foreign url inherit a <em>stricter</em> policy,
     * which is harmless — but it inverts as soon as a longer, looser entry
     * exists ({@code …/acme} required, {@code …/acme-sandbox} off): whoever
     * controls {@code acme-sandbox-evil} then wins the {@code off} entry.
     */
    private static boolean coversUrl(String prefix, String needle) {
        if (!needle.startsWith(prefix)) return false;
        return needle.length() == prefix.length() || needle.charAt(prefix.length()) == '/';
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
