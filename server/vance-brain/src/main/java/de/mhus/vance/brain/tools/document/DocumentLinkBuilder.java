package de.mhus.vance.brain.tools.document;

import de.mhus.vance.shared.document.DocumentDocument;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for building {@code vance:} URIs and the
 * canonical Markdown link form.
 *
 * <p>Shared by {@link DocumentLinkTool} (explicit LLM tool call) and
 * by document-producing tools ({@code doc_create} et al.) that
 * include a {@code markdownLink} field in their result so the LLM
 * can embed the link without a second tool round-trip.
 *
 * <p>Spec: specification/inline-and-embedded-content.md §3.1 + §10.1.
 */
@Component
public class DocumentLinkBuilder {

    /**
     * Build the {@code markdownLink} string for a document.
     *
     * @param doc                 the persisted document
     * @param currentProjectName  the caller's active project — drives the
     *                            same-project vs. cross-project URI form;
     *                            {@code null} treats the doc's project as
     *                            cross-project (defensive)
     * @param textOverride        optional explicit link text; falls back to
     *                            the document title and finally the path leaf
     * @param modeOverride        optional explicit mode (preview/reference)
     * @param imageStyleOverride  optional explicit image-vs-link syntax
     */
    public Result build(
            DocumentDocument doc,
            @Nullable String currentProjectName,
            @Nullable String textOverride,
            @Nullable String modeOverride,
            @Nullable Boolean imageStyleOverride) {

        String docProjectName = doc.getProjectId();
        String kind = doc.getKind() != null ? doc.getKind().toLowerCase() : "document";
        String docPath = doc.getPath();
        String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                ? doc.getTitle()
                : leafSegment(docPath);
        String linkText = textOverride != null ? textOverride : title;

        boolean isImageKind = "image".equals(kind) || "svg".equals(kind);
        boolean imageStyle = imageStyleOverride != null ? imageStyleOverride : isImageKind;

        String mode = modeOverride != null ? modeOverride : defaultModeForKind(kind);

        boolean crossProject = currentProjectName == null
                || !currentProjectName.equals(docProjectName);

        String uri = buildVanceUri(crossProject ? docProjectName : null, docPath, kind, mode);
        String markdownLink = (imageStyle ? "!" : "")
                + "[" + escapeLinkText(linkText) + "](" + uri + ")";

        return new Result(markdownLink, docPath, kind,
                crossProject ? docProjectName : null, title, mode, imageStyle);
    }

    /** Convenience for tools that just create a doc and want the canonical link. */
    public String linkFor(DocumentDocument doc, @Nullable String currentProjectName) {
        return build(doc, currentProjectName, null, null, null).markdownLink();
    }

    public record Result(
            String markdownLink,
            String path,
            String kind,
            @Nullable String project,
            String title,
            String mode,
            boolean imageStyle) {}

    public static String defaultModeForKind(String kind) {
        return switch (kind) {
            case "image", "svg", "pdf", "video" -> "preview";
            default -> "reference";
        };
    }

    public static String buildVanceUri(
            @Nullable String crossProjectName, String path, String kind, String mode) {
        StringBuilder sb = new StringBuilder("vance:");
        if (crossProjectName != null) {
            sb.append("//").append(urlEncodeSegment(crossProjectName)).append('/');
        } else {
            sb.append('/');
        }
        sb.append(urlEncodePath(path));
        sb.append("?kind=").append(urlEncodeSegment(kind));
        if (!defaultModeForKind(kind).equals(mode)) {
            sb.append("&mode=").append(urlEncodeSegment(mode));
        }
        return sb.toString();
    }

    private static String urlEncodeSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlEncodePath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(urlEncodeSegment(parts[i]));
        }
        return sb.toString();
    }

    private static String leafSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String escapeLinkText(String text) {
        return text.replace("]", "\\]").replace("[", "\\[");
    }
}
