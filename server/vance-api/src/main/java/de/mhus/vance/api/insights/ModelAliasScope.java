package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The settings scope an {@code ai.alias.*} value comes from, as the
 * project sees it: its own setting, or the tenant's one it inherits.
 */
@GenerateTypeScript("insights")
public enum ModelAliasScope {
    /** Set in the {@code _tenant} project — inherited by every project. */
    TENANT,
    /** Set in the project itself, overriding the tenant value. */
    PROJECT
}
