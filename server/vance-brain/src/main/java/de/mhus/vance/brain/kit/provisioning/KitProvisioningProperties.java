package de.mhus.vance.brain.kit.provisioning;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.kits.provisioning.*} — the periodic check.
 *
 * <p>Only the check is configurable here. The other two triggers are
 * events (a project coming up, its provisioning document changing) and
 * have nothing to tune.
 *
 * <p><b>{@code check-interval} and {@code check-initial-delay} are not
 * fields.</b> {@code @Scheduled} cannot read a bean, so
 * {@link KitProvisioningCheckTick} takes them as property placeholders with
 * their defaults inline — {@code PT4H} and {@code PT5M}. Mirroring them here
 * would be two sources of truth for one number, and the copy that nobody reads
 * is the one a later change would edit.
 */
@ConfigurationProperties(prefix = "vance.kits.provisioning")
@Data
public class KitProvisioningProperties {

    /**
     * Whether the periodic check runs at all. Off means provisioning still
     * happens on project start and on document change — only the „the
     * source moved on" notice stops.
     */
    private boolean checkEnabled = true;
}
