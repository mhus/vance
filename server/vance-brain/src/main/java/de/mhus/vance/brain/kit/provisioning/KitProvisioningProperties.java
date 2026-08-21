package de.mhus.vance.brain.kit.provisioning;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.kits.provisioning.*} — the periodic check.
 *
 * <p>Only the check is configurable here. The other two triggers are
 * events (a project coming up, its provisioning document changing) and
 * have nothing to tune.
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

    /**
     * Spacing of the check.
     *
     * <p>Four hours because the tick only covers the remaining case: the
     * source published something while the project sat open. Everything
     * originating on this side already arrives through the other two
     * triggers and does not wait for it. A kit is not a feed.
     */
    private Duration checkInterval = Duration.ofHours(4);

    /**
     * How long after boot the first check runs. Not zero: a pod that just
     * started is placing projects and reading caches, and a sweep over
     * every project's provisioning is the wrong thing to add to that.
     */
    private Duration checkInitialDelay = Duration.ofMinutes(5);
}
