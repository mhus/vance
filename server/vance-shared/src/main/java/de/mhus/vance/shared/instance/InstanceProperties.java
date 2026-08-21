package de.mhus.vance.shared.instance;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.instance.*} — how this installation names itself to the
 * outside world.
 *
 * <p>Exists because nothing else in the tree could do the job. {@code
 * vance.cluster.nodeName} is per pod and otherwise drawn at random from
 * a dictionary, so a remote party would see a different name per pod;
 * {@code vance.cluster.id} is an internal partitioning key that defaults
 * to {@code "default"}, so every unconfigured installation would claim
 * the same identity — worse than none, because it looks meaningful.
 *
 * <p><b>No default on purpose.</b> Unset means the field is left out of
 * outbound requests rather than filled with a stand-in. A remote host
 * reads a missing value correctly as „unknown"; it would log
 * {@code "default"} as a customer name.
 *
 * <p><b>Not an authorisation input.</b> The label is self-declared and
 * unverified — whoever receives it must authorise on the credential, not
 * on this string. See {@code planning/kit-ode-provisioning.md} §3.2.
 */
@ConfigurationProperties(prefix = "vance.instance")
public class InstanceProperties {

    /**
     * Human-readable name of this installation, sent to remote parties
     * that provision into it so their logs can tell installations apart.
     * Null or blank means „do not say".
     */
    private @Nullable String name;

    public @Nullable String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }
}
