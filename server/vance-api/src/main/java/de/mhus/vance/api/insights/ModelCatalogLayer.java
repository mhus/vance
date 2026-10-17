package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * One layer of the model catalog cascade, outermost first. Within a scope
 * the discovery-written {@code _AUTO} layer sits below the operator-written
 * one, so a hand-set field beats an observed one. See
 * {@code specification/public/llm-resource-management.md} (model catalog).
 */
@GenerateTypeScript("insights")
public enum ModelCatalogLayer {
    /** Shipped with the brain ({@code vance-defaults/_vance/model/}). */
    BUNDLED,
    /** {@code _vance/model-auto/} in the system tenant's {@code _tenant} project. */
    SYSTEM_AUTO,
    /** {@code _vance/model/} in the system tenant's {@code _tenant} project. */
    SYSTEM,
    /** {@code _vance/model-auto/} in the tenant's {@code _tenant} project. */
    TENANT_AUTO,
    /** {@code _vance/model/} in the tenant's {@code _tenant} project. */
    TENANT,
    /** {@code _vance/model-auto/} in the project itself. */
    PROJECT_AUTO,
    /** {@code _vance/model/} in the project itself. */
    PROJECT
}
