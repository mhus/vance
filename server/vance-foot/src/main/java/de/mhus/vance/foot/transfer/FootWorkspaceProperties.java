package de.mhus.vance.foot.transfer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.foot.workspace.*} — tunables for the Foot-side file
 * transfer workspace. The workspace acts as a drop-zone for inbound
 * transfers from the Brain and as the source for outbound uploads.
 *
 * <p>Distinct from the existing arbitrary-disk client-file tools
 * ({@code client_file_read} et al.) which operate on user-supplied
 * paths anywhere on the host. Transfer paths are sandboxed under
 * {@link #root} to keep the Brain's reach scoped.
 */
@Data
@ConfigurationProperties(prefix = "vance.foot.workspace")
public class FootWorkspaceProperties {

    /**
     * Filesystem root under which each project gets a sub-directory
     * named by its {@code projectId}. Default is below the user's home
     * directory; production setups can point this anywhere writable.
     */
    private String root = System.getProperty("user.home") + "/.vance/foot";

    /**
     * POSIX mode mask AND-ed against {@code attrs.mode} from the
     * Brain. Prevents the Brain from setting setuid / world-writable
     * bits on files dropped to the user's disk. Default {@code 0755}
     * preserves the executable bit (e.g. for downloaded shell
     * scripts) but trims the dangerous bits.
     */
    private int modeMask = 0755;

    /**
     * Hard upper bound on the {@code totalSize} of a single inbound
     * download from the Brain. Receiver rejects {@code transfer_init}
     * with a larger declared size.
     */
    private long maxDownloadSize = 100L * 1024 * 1024; // 100 MiB

    /**
     * Hard upper bound on the {@code totalSize} of a single outbound
     * upload to the Brain. Foot rejects locally before sending the
     * first frame.
     */
    private long maxUploadSize = 100L * 1024 * 1024; // 100 MiB
}
