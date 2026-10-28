package de.mhus.vance.toolpack;

/**
 * Names of host capabilities a {@link Tool} may require
 * ({@link Tool#requiresCapabilities()}). A host that lacks a capability leaves the
 * tool out of every inventory instead of listing it and failing on call. Most capabilities
 * belong to the pod; {@link #RUNNER} and — where the pod has no ffmpeg of its own —
 * {@link #AUDIO_TOOLS} can instead come from the project's runner.
 */
public final class ToolCapabilities {

    /**
     * Access to the server-side workspace: arbitrary files on the pod, programs
     * started there. Absent when the operator switches workspace access off
     * ({@code vance.workspace.mode=off}).
     */
    public static final String WORKSPACE = "workspace";

    /**
     * Local audio editing (ffmpeg/ffprobe): {@code audio_info}, {@code audio_trim},
     * {@code audio_mix}, {@code audio_concat}, {@code audio_convert}. Present when the
     * programs run on this host; the cloud image ships without them.
     */
    public static final String AUDIO_TOOLS = "audio-tools";

    /**
     * A runner serves the project ({@code planning/runner.md}): the {@code runner_*} tools.
     * Never a property of the pod — it is true per project, so a host cannot have it and the
     * tools show up only for projects that do.
     */
    public static final String RUNNER = "runner";

    private ToolCapabilities() {}
}
