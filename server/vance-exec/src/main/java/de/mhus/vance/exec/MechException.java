package de.mhus.vance.exec;

/**
 * A mechanic — ffmpeg, whisper, yt-dlp — could not do what it was asked. The {@link Reason}
 * says whether the caller got the request wrong or the program failed; the callers (the brain's
 * tools, the runner's operations) turn it into their own error shapes.
 */
public class MechException extends RuntimeException {

    /** What kind of failure it is; names match the audio tools' public vocabulary. */
    public enum Reason {
        /** A parameter cannot be used (a fade longer than the clip, one clip to concatenate). */
        PARAMETER_INVALID,
        /** The requested output format is not one the tool offers. */
        FORMAT_UNSUPPORTED,
        /** The program ran and failed, timed out, or could not be started. */
        PROCESSING_ERROR,
        /** The program or its data is not on this host. */
        UNAVAILABLE
    }

    private final Reason reason;

    public MechException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MechException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
