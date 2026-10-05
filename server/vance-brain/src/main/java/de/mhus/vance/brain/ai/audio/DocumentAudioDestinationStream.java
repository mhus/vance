package de.mhus.vance.brain.ai.audio;

import de.mhus.vance.shared.document.AudioDestinationStream;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link AudioDestinationStream} backed by
 * {@code DocumentService.createOrReplaceBinary} — same mechanics as
 * {@code DocumentImageDestinationStream}: buffer bytes in memory,
 * capture mime type + title + metadata via the typed setters, commit
 * everything in a single {@code createOrReplaceBinary} call on
 * {@link #close()}. Designed for typical audio payloads (a few MB).
 *
 * <p>One stream instance is consumed by exactly one provider call.
 * Writes after {@code close()} throw {@link IllegalStateException}.
 */
public class DocumentAudioDestinationStream extends AudioDestinationStream {

    /** Conventional tag list applied to every Hotblack-generated audio doc. */
    public static final List<String> DEFAULT_TAGS = List.of("audio", "ai-generated", "hotblack");

    private final DocumentService documentService;
    private final String tenantId;
    private final String projectId;
    private final String path;
    private final @Nullable String createdBy;
    private final List<String> tags;
    /** Authorization actor for the commit write — supplied by the caller so the
     *  DocumentService chokepoint applies WRITE/reserved-prefix against the real
     *  user instead of a SYSTEM fail-open. */
    private final de.mhus.vance.shared.permission.WriteActor actor;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private @Nullable String mimeType;
    private @Nullable String title;
    private boolean closed;

    public DocumentAudioDestinationStream(
            DocumentService documentService,
            String tenantId,
            String projectId,
            String path,
            @Nullable String createdBy,
            @Nullable List<String> tags,
            de.mhus.vance.shared.permission.WriteActor actor) {
        this.documentService = documentService;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.path = path;
        this.createdBy = createdBy;
        this.tags = tags == null ? DEFAULT_TAGS : List.copyOf(tags);
        this.actor = actor;
    }

    public DocumentAudioDestinationStream(
            DocumentService documentService,
            String tenantId,
            String projectId,
            String path,
            @Nullable String createdBy,
            de.mhus.vance.shared.permission.WriteActor actor) {
        this(documentService, tenantId, projectId, path, createdBy, null, actor);
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
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mimeType == null) {
            throw new IllegalStateException("AudioDestinationStream closed without a mime type set");
        }
        if (buffer.size() == 0) {
            throw new IllegalStateException("AudioDestinationStream closed without any bytes written");
        }
        documentService.createOrReplaceBinary(
                tenantId, projectId, path, buffer.toByteArray(), mimeType, title, tags, headers, createdBy, actor);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("AudioDestinationStream is already closed");
        }
    }
}
