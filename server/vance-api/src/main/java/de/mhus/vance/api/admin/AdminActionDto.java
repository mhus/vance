package de.mhus.vance.api.admin;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire descriptor of one admin action — an operator command the
 * Settings page's "Aktionen" tab lists and runs (catalog refresh,
 * model discovery, health checks, …). Actions are provided by the
 * backend's {@code AdminAction} SPI; the client renders whatever the
 * list endpoint returns, so a new action needs no client release.
 *
 * <p>{@link #title} and {@link #description} are localized the same way
 * wizard texts are: {@code Map<lang, text>}, with the client-side
 * universal fallback to {@code en}. A backend that stores only one
 * language simply fills that key.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("admin")
public class AdminActionDto {

    /** Stable action id — the REST run path names it ({@code admin/actions/{id}}). */
    private String id;

    /** Localized title, {@code Map<lang, text>}. */
    private Map<String, String> title;

    /** Localized description — what the action does, when to run it. */
    private Map<String, String> description;

    /**
     * Scope availability: {@code TENANT_ONLY} (runnable in the tenant
     * scope) or {@code TENANT_AND_PROJECT} (also runnable against a
     * project). The client uses it to filter the list per scope row.
     */
    private String scope;
}
