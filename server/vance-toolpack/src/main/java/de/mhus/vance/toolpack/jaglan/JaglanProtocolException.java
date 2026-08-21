package de.mhus.vance.toolpack.jaglan;

import org.jspecify.annotations.Nullable;

/**
 * A mount protocol could not serve a request — refused, unreachable, or a
 * malformed answer from the source.
 *
 * <p>Protocol-level and therefore in the toolpack, which cannot see the
 * document layer. The brain-side port implementation translates it into
 * {@code JaglanAccessException} (a refusal, stop asking) or
 * {@code JaglanUnavailableException} (transient, retry) — that distinction
 * belongs to the caller's world, not the protocol's, because only the
 * document layer knows what it wants to tell REST and the tool surface.
 */
public class JaglanProtocolException extends RuntimeException {

    private final @Nullable String mount;

    /** {@code true} when the source refused rather than failed. */
    private final boolean refused;

    public JaglanProtocolException(@Nullable String mount, String message) {
        this(mount, message, true, null);
    }

    public JaglanProtocolException(
            @Nullable String mount, String message, boolean refused, @Nullable Throwable cause) {
        super(message, cause);
        this.mount = mount;
        this.refused = refused;
    }

    /** Transport or availability failure — retryable, not a refusal. */
    public static JaglanProtocolException unavailable(
            @Nullable String mount, String message, @Nullable Throwable cause) {
        return new JaglanProtocolException(mount, message, false, cause);
    }

    public @Nullable String getMount() {
        return mount;
    }

    public boolean isRefused() {
        return refused;
    }
}
