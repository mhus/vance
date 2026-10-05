package de.mhus.vance.shared.document;

import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Write-end of an audio-generation pipeline (Hotblack TTS / music).
 * Providers (audio adapters in {@code vance-brain}) push bytes through
 * the {@link OutputStream} surface and accumulate descriptive metadata
 * through the typed setters. On {@link #close()} the concrete
 * implementation commits the bytes plus the collected metadata to its
 * backing store — for the document-backed default implementation that
 * means a {@code DocumentDocument} save via {@link DocumentService}.
 *
 * <p>Same contract as {@link ImageDestinationStream}, deliberately a
 * separate type: audio commits carry different tags/defaults and a
 * second abstract type keeps provider signatures honest about which
 * modality they write.
 */
public abstract class AudioDestinationStream extends OutputStream {

    /**
     * Mark the bytes about to be written as this mime type. Required
     * before {@link #close()}; the destination uses it to set the
     * document mime and pick a file extension on path-default
     * fall-through.
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
     * info (model id, voice, language, generation duration, reported
     * cost, …). Multiple calls with the same key overwrite previous
     * values.
     */
    public abstract void setMetadata(String key, String value);

    /**
     * Commit the accumulated bytes and metadata to the destination.
     * After {@code close()} the stream is no longer writable.
     */
    @Override
    public abstract void close();

    /**
     * Overridden purely to drop the {@link java.io.IOException} that
     * {@link java.io.OutputStream} declares on its bulk write — audio
     * providers buffer in memory and never throw checked IO.
     */
    @Override
    public abstract void write(byte[] b, int off, int len);
}
