package de.mhus.vance.shared.process;

import java.io.IOException;

/**
 * Starts an OS process for a brain-side starter (npm, pip, ffmpeg, whisper, yt-dlp,
 * latexmk, Rserve, …). The single place that decides what a child may see: the
 * brain process holds the Mongo password, the encryption password, provider keys
 * and the internal token, and a child must inherit none of them
 * ({@code planning/multi-tenant-isolation.md} P6).
 *
 * <p>Declared in {@code vance-shared} so the workspace handlers can use it; the
 * brain provides the implementation (sealed environment from the exec policy,
 * scoped {@code HOME}). Callers keep their own stream and timeout handling on the
 * returned {@link Process}.
 */
@FunctionalInterface
public interface ProcessLauncher {

    /**
     * Starts {@code spec}.
     *
     * @throws IOException when the process cannot be started
     */
    Process start(ProcessSpec spec) throws IOException;
}
