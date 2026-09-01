package de.mhus.vance.brain.webgrab;

import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

        // Kept separate from the document title on purpose. The title falls back
        // to the source URL because a URL is a usable label; the *file name*
        // must not, because GrabNaming has a better answer for a URL than
        // slugifying the whole of it — passing the fallback title in here made
        // that answer unreachable and produced
        // `https-example-com-blog-post.md` where `post.md` was meant.
        String pageTitle;
        String extension;
        byte[] bytes;
        if (html) {
            HtmlToMarkdown.Result converted = HtmlToMarkdown.convert(
                    decodeText(content, mimeType), sourceUrl);
            pageTitle = firstUsable(title, converted.title());
            extension = "md";
            bytes = withFrontMatter(titleOrUrl(pageTitle, sourceUrl), sourceUrl,
                    converted.markdown())
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            pageTitle = firstUsable(title, null);
            extension = GrabNaming.extensionFor(mimeType);
            bytes = content;
        }
        String documentTitle = titleOrUrl(pageTitle, sourceUrl);

        String stem = GrabNaming.slug(pageTitle, sourceUrl);
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
     * <p><b>Values are unquoted</b>, matching {@code FrontMatter.render} — the
     * canonical writer for this format. The parser splits on the first colon
     * and never unquotes, so a quoted title comes back out <em>with</em> its
     * quotes as part of the value. What the title does need is
     * {@code collapseWhitespace}: a newline in it would end the header early
     * and spill the rest of the page into the body.
     */
    static String withFrontMatter(String title, String sourceUrl, String markdown) {
        return "---\n"
                + "title: " + UntrustedContent.collapseWhitespace(title) + "\n"
                + "source: " + sourceUrl.replaceAll("\\s", "") + "\n"
                + "grabbedAt: " + Instant.now() + "\n"
                + "---\n\n"
                + markdown
                + "\n";
    }

    /**
     * The name the page offers for itself: what the caller typed, else what the
     * page calls itself. {@code null} when it offers none.
     *
     * <p>The caller wins because the only reason a grab carries a title is that
     * somebody edited it in the popup. Deliberately <b>no URL fallback</b> —
     * see {@link #titleOrUrl} for why the two answers differ.
     */
    private static @Nullable String firstUsable(@Nullable String given,
                                                @Nullable String fromPage) {
        for (String candidate : new String[] {given, fromPage}) {
            if (candidate != null && !candidate.isBlank()) {
                return UntrustedContent.collapseWhitespace(candidate.trim());
            }
        }
        return null;
    }

    /** A display title, falling back to the source URL and then to a constant. */
    private static String titleOrUrl(@Nullable String pageTitle, String sourceUrl) {
        if (pageTitle != null) return pageTitle;
        String url = UntrustedContent.collapseWhitespace(sourceUrl).trim();
        return url.isEmpty() ? GrabNaming.FALLBACK : url;
    }

    /**
     * Decode grabbed markup using the charset the caller declared.
     *
     * <p>UTF-8 is the right default and what every browser extension sends — it
     * hands over a DOM snapshot, which is a string. But the endpoint documents
     * a shell client as a caller too, and one posting an ISO-8859-1 page with
     * the matching {@code charset=} would otherwise have every umlaut stored as
     * U+FFFD: silent, and not recoverable from the stored document.
     *
     * <p>An unusable charset name falls back rather than failing: a page we can
     * read approximately beats a grab that refuses.
     */
    private static String decodeText(byte[] content, @Nullable String mimeType) {
        return new String(content, charsetOf(mimeType));
    }

    private static Charset charsetOf(@Nullable String mimeType) {
        if (mimeType == null) return StandardCharsets.UTF_8;
        for (String part : mimeType.split(";")) {
            String token = part.trim();
            if (!StringUtils.startsWithIgnoreCase(token, "charset=")) continue;
            String name = token.substring("charset=".length()).trim()
                    .replaceAll("^[\"']|[\"']$", "");
            try {
                return Charset.forName(name);
            } catch (IllegalArgumentException e) {
                log.debug("Grab declared an unusable charset '{}' — reading as UTF-8", name);
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
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
