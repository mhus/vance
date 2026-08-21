package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How much a provisioning source may do to a project without being asked.
 *
 * <p>Graded rather than a boolean because the two things it governs are
 * not the same size: „a kit I have has a new revision" changes the
 * content of what is installed, „this project should also have X"
 * changes the project's tool surface. One flag for both would mean
 * granting the second in order to get the first.
 *
 * <p>Uninstalling is in no level. A desired-state list that removes
 * documents when a line is deleted turns a typo into data loss, and
 * uninstall is a verb somebody types on purpose.
 */
@GenerateTypeScript("kit")
public enum KitProvisioningAuthority {

    /**
     * Nothing unattended. Divergence is reported and waits for an answer.
     * The default, and the level at which the report itself is the
     * consent step — which is why provisioned kits need no separate
     * trust gate.
     */
    NOTIFY,

    /** May update kits that are already installed here. */
    UPDATE,

    /**
     * May additionally install kits that appear in the desired list but
     * are not here yet. The level at which the source is effectively an
     * administrator of this project — appropriate for a company's own
     * host, and the reason it has to be written down explicitly.
     */
    MANAGE;

    /** What applies when an entry does not say. */
    public static KitProvisioningAuthority defaultLevel() {
        return NOTIFY;
    }

    /** True when this level permits refreshing a kit that is already installed. */
    public boolean mayUpdateInstalled() {
        return this != NOTIFY;
    }

    /** True when this level permits pulling in a kit that is not installed yet. */
    public boolean mayInstallNew() {
        return this == MANAGE;
    }
}
