package de.mhus.vance.shared.document;

import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Write-end of an image-generation pipeline. Providers (Fenchurch image
 * adapters in {@code vance-brain}) push bytes through the {@link OutputStream}
 * surface and accumulate descriptive metadata through the typed setters. On
 * {@link #close()} the concrete implementation commits the bytes plus the
 * collected metadata to its backing store — for the document-backed default
 * implementation that means a {@code DocumentDocument} save via
 * {@link DocumentService}.
 *
 * <p>The abstraction lives in {@code vance-shared} so providers in
 * {@code vance-brain} stay unaware of how/where images are persisted: they
 * call {@link #setMimeType}, {@link #setMetadata}, {@code write(...)} and
 * {@link #close()} — the destination decides the rest.
 */
public abstract class ImageDestinationStream extends OutputStream {

    /**
     * Mark the bytes about to be written as this mime type. Required before
     * {@link #close()}; the destination uses it to set the document mime,
     * pick a file extension on path-default fall-through, and feed
     * downstream consumers (preview, tag, RAG-eligibility check).
     */
    public abstract void setMimeType(String mimeType);

    /**
     * Human-readable title attached to the resulting document
     * ({@code DocumentDocument.title}). {@code null} leaves the title
     * empty (UI falls back to the file name in that case).
     */
    public abstract void setTitle(@Nullable String title);

    /**
     * Attach a key/value pair to the resulting document's
     * {@code headers} map. Used by providers to record reproducibility
     * info (model id, revised prompt, seed, generation duration, …).
     * Multiple calls with the same key overwrite previous values.
     */
    public abstract void setMetadata(String key, String value);

    /**
     * Set the alt-text under the conventional {@code altText} headers
     * key. Equivalent to {@code setMetadata("altText", altText)} but
     * spelled out so providers don't have to memorise the key.
     * {@code null} clears any previously set alt-text.
     */
    public abstract void setAltText(@Nullable String altText);

    /**
     * Commit the accumulated bytes and metadata to the destination.
     * After {@code close()} the stream is no longer writable.
     */
    @Override
    public abstract void close();

    /**
     * Overridden purely to drop the {@link java.io.IOException} that
     * {@link java.io.OutputStream} declares on its bulk write — image
     * providers buffer in memory and never throw checked IO, so the
     * concrete subclasses provide a no-throws override and callers
     * don't need a {@code try/catch} they have nothing meaningful to
     * do with.
     */
    @Override
    public abstract void write(byte[] b, int off, int len);
}
