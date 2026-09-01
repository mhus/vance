package de.mhus.vance.brain.webgrab;

import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Import the page somebody is looking at as a document.
 *
 * <p><b>Why the content arrives instead of the URL.</b> A grab is worth having
 * precisely for pages the server cannot fetch: behind a login, behind a
 * paywall, on an intranet, or assembled by JavaScript. For everything else
 * {@code web_fetch} already exists and an agent already has it. So the caller
 * sends what its browser rendered, and this service never makes an outbound
 * request — which also means no SSRF surface to defend.
 *
 * <p>HTML becomes Markdown; a PDF or an image is stored as it arrived. That
 * split is the whole type logic: converting a PDF would lose the thing that
 * makes it a PDF, and leaving HTML as HTML produces a document nobody wants to
 * open in a year.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebGrabService {

    /** Where grabs land when the caller names no folder. */
    public static final String DEFAULT_FOLDER = "web";

    /**
     * How many name collisions to walk before giving up. A page grabbed 50
     * times is not a naming problem.
     */
    private static final int MAX_COLLISION_ATTEMPTS = 50;

    private final DocumentService documentService;

    /** What was created. */
    public record Grabbed(String path, String title, String mimeType, boolean converted) {}

    /**
     * Store one grabbed page.
     *
     * @param sourceUrl where it came from — kept in the document's front matter
     *                  and used to resolve relative links.
     * @param mimeType  what the caller says it is. {@code text/html} takes the
     *                  conversion path; everything else is stored verbatim.
     * @param content   the rendered DOM, or the raw bytes.
     */
    public Grabbed grab(String tenantId, String projectId, @Nullable String folder,
                        String sourceUrl, @Nullable String mimeType, byte[] content,
                        @Nullable String title, @Nullable String userId, WriteActor actor) {

        String targetFolder = normaliseFolder(folder);
        boolean html = isHtml(mimeType);

        String documentTitle;
        String extension;
        byte[] bytes;
        if (html) {
            HtmlToMarkdown.Result converted = HtmlToMarkdown.convert(
                    new String(content, StandardCharsets.UTF_8), sourceUrl);
            documentTitle = pickTitle(title, converted.title(), sourceUrl);
            extension = "md";
            bytes = withFrontMatter(documentTitle, sourceUrl, converted.markdown())
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            documentTitle = pickTitle(title, null, sourceUrl);
            extension = GrabNaming.extensionFor(mimeType);
            bytes = content;
        }

        String stem = GrabNaming.slug(documentTitle, sourceUrl);
        DocumentDocument created = createAtFreePath(
                tenantId, projectId, targetFolder, stem, extension,
                documentTitle, html ? "text/markdown" : mimeType, bytes, userId, actor);

        log.info("WebGrabService.grab tenant='{}' project='{}' path='{}' source='{}' converted={}",
                tenantId, projectId, created.getPath(), sourceUrl, html);
        return new Grabbed(created.getPath(), documentTitle,
                created.getMimeType(), html);
    }

    /**
     * Write under the first free name.
     *
     * <p><b>Suffix, never overwrite.</b> Grabbing the same article twice is a
     * normal thing to do — you saw it again and forgot. Overwriting would take
     * whatever the reader had added to the earlier copy with it, silently. A
     * second file is mildly untidy and destroys nothing, and untidy is the
     * failure a person can see and fix.
     *
     * <p>The loop races: two grabs of the same page at the same moment can both
     * find {@code post.md} free. The loser gets
     * {@code DocumentAlreadyExistsException} from the create and simply tries
     * the next name — which is why the existence check is not the guard, the
     * create is.
     */
    private DocumentDocument createAtFreePath(
            String tenantId, String projectId, String folder, String stem, String extension,
            String title, @Nullable String mimeType, byte[] bytes,
            @Nullable String userId, WriteActor actor) {

        for (int attempt = 1; attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            String name = attempt == 1 ? stem : stem + "-" + attempt;
            String path = folder.isEmpty()
                    ? name + "." + extension
                    : folder + "/" + name + "." + extension;
            if (documentService.findByPath(tenantId, projectId, path).isPresent()) {
                continue;
            }
            try {
                return documentService.create(tenantId, projectId, path, title, List.of(),
                        mimeType, new ByteArrayInputStream(bytes), userId, actor);
            } catch (DocumentService.DocumentAlreadyExistsException e) {
                // Lost the race — the next name is free or the next iteration
                // says so.
                log.debug("WebGrabService: '{}' taken between check and create", path);
            }
        }
        throw new IllegalStateException(
                "Could not find a free name for '" + stem + "' in '" + folder + "'");
    }

    /**
     * The front matter of a grabbed page.
     *
     * <p>Uses the fenced {@code key: value} form the markdown header strategy
     * already reads, so the source travels with the document rather than being
     * a sentence in the body that the next edit removes. {@code source} is the
     * one field worth machine-readable: it answers "where is this from" and
     * "have I got this already" without a text search.
     *
     * <p>The title is quoted and stripped of anything that would break the
     * flat parser — it is the page's own text, and a colon or a newline in it
     * would end the header early and spill the rest into the body.
     */
    static String withFrontMatter(String title, String sourceUrl, String markdown) {
        String safeTitle = UntrustedContent.collapseWhitespace(title).replace("\"", "'");
        return "---\n"
                + "title: \"" + safeTitle + "\"\n"
                + "source: " + sourceUrl.replaceAll("\\s", "") + "\n"
                + "grabbedAt: " + Instant.now() + "\n"
                + "---\n\n"
                + markdown
                + "\n";
    }

    /**
     * What to call it: what the caller typed, else what the page calls itself,
     * else the URL. The caller wins because the only reason a grab carries a
     * title is that somebody edited it in the popup.
     */
    private static String pickTitle(@Nullable String given, @Nullable String fromPage,
                                    String sourceUrl) {
        for (String candidate : new String[] {given, fromPage, sourceUrl}) {
            if (candidate != null && !candidate.isBlank()) {
                return UntrustedContent.collapseWhitespace(candidate.trim());
            }
        }
        return GrabNaming.FALLBACK;
    }

    private static boolean isHtml(@Nullable String mimeType) {
        if (mimeType == null) return false;
        String mime = mimeType.toLowerCase(java.util.Locale.ROOT);
        return mime.startsWith("text/html") || mime.startsWith("application/xhtml");
    }

    /**
     * A folder, cleaned to a relative path.
     *
     * <p>Same reasoning as {@link GrabNaming}: this arrives from a client
     * configuration file that a person pasted into. Leading slashes and
     * {@code ..} segments come out, so the worst a bad value can do is put the
     * document somewhere unexpected inside its own project.
     */
    static String normaliseFolder(@Nullable String folder) {
        if (folder == null || folder.isBlank()) return DEFAULT_FOLDER;
        StringBuilder out = new StringBuilder();
        for (String segment : folder.split("/")) {
            String clean = GrabNaming.slugify(segment);
            if (clean.isEmpty()) continue;
            if (!out.isEmpty()) out.append('/');
            out.append(clean);
        }
        return out.isEmpty() ? DEFAULT_FOLDER : out.toString();
    }
}
