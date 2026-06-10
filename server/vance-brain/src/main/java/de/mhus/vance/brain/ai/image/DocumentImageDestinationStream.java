package de.mhus.vance.brain.ai.image;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link ImageDestinationStream} backed by
 * {@link DocumentService#createOrReplaceBinary}.
 *
 * <p>Buffers all bytes written by the image provider in memory, captures
 * mime type + title + metadata via the typed setters, and on
 * {@link #close()} commits everything in a single
 * {@code createOrReplaceBinary} call. Designed for typical image
 * payloads (a few MB) — for very large generations a streaming
 * implementation would be needed instead.
 *
 * <p>One stream instance is consumed by exactly one provider call. The
 * caller (Fenchurch) constructs the stream with the resolved target
 * (tenant + project + path + tag set), the provider writes into it,
 * the caller closes it. Subsequent writes after {@code close()} throw
 * {@link IllegalStateException}.
 */
public class DocumentImageDestinationStream extends ImageDestinationStream {

    /** Conventional tag list applied to every Fenchurch-generated image. */
    public static final List<String> DEFAULT_TAGS =
            List.of("image", "ai-generated", "fenchurch");

    private final DocumentService documentService;
    private final String tenantId;
    private final String projectId;
    private final String path;
    private final @Nullable String createdBy;
    private final List<String> tags;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private @Nullable String mimeType;
    private @Nullable String title;
    private boolean closed;

    public DocumentImageDestinationStream(
            DocumentService documentService,
            String tenantId,
            String projectId,
            String path,
            @Nullable String createdBy,
            @Nullable List<String> tags) {
        this.documentService = documentService;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.path = path;
        this.createdBy = createdBy;
        this.tags = tags == null ? DEFAULT_TAGS : List.copyOf(tags);
    }

    public DocumentImageDestinationStream(
            DocumentService documentService,
            String tenantId,
            String projectId,
            String path,
            @Nullable String createdBy) {
        this(documentService, tenantId, projectId, path, createdBy, null);
    }

    @Override
    public void write(int b) {
        ensureOpen();
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        ensureOpen();
        buffer.write(b, off, len);
    }

    @Override
    public void setMimeType(String mimeType) {
        ensureOpen();
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        this.mimeType = mimeType;
    }

    @Override
    public void setTitle(@Nullable String title) {
        ensureOpen();
        this.title = title;
    }

    @Override
    public void setMetadata(String key, String value) {
        ensureOpen();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("metadata key must not be blank");
        }
        if (value == null) {
            headers.remove(key);
        } else {
            headers.put(key, value);
        }
    }

    @Override
    public void setAltText(@Nullable String altText) {
        ensureOpen();
        if (altText == null) {
            headers.remove("altText");
        } else {
            headers.put("altText", altText);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mimeType == null) {
            throw new IllegalStateException(
                    "ImageDestinationStream closed without a mime type set");
        }
        if (buffer.size() == 0) {
            throw new IllegalStateException(
                    "ImageDestinationStream closed without any bytes written");
        }
        documentService.createOrReplaceBinary(
                tenantId,
                projectId,
                path,
                buffer.toByteArray(),
                mimeType,
                title,
                tags,
                headers,
                createdBy);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "ImageDestinationStream is already closed");
        }
    }
}
