package de.mhus.vance.shared.document.jaglan;

import org.jspecify.annotations.Nullable;

/**
 * The mount could not be reached, or there is no Jaglan implementation in
 * this process at all.
 *
 * <p>The second case is the important one and it is not an edge case: the
 * port is optional ({@code ObjectProvider}), and every process that loads
 * {@code vance-shared} without the Jaglan addon — anus, for one — has none.
 * A path under {@code _ext/} must then fail with this, so the caller says
 * "no mount here" instead of throwing a {@code NullPointerException} from
 * somewhere inside the document layer.
 *
 * <p>Transient by nature, so distinct from {@link JaglanAccessException}: the
 * caller may retry, and a listing should keep showing the mount folder with
 * {@code access = UNKNOWN} rather than making it disappear. A configured
 * mount that is briefly down is still configured.
 */
public class JaglanUnavailableException extends RuntimeException {

    private final @Nullable String mount;

    public JaglanUnavailableException(@Nullable String mount, String message) {
        super(message);
        this.mount = mount;
    }

    public JaglanUnavailableException(@Nullable String mount, String message, Throwable cause) {
        super(message, cause);
        this.mount = mount;
    }

    /** The mount involved, or {@code null} when no port exists at all. */
    public @Nullable String getMount() {
        return mount;
    }
}
