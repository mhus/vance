package de.mhus.vance.shared.user;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether an account signs in with a code sent by e-mail instead of its
 * password — decided per tenant.
 *
 * <p>Two things have to be true. The installation offers the way in
 * ({@code vance.auth.email-token.enabled}, default off): the endpoints exist and
 * the Anus mail sender runs. And the tenant has chosen it: the setting
 * {@value #SETTING_KEY} on its {@code _tenant} project, default off. A tenant that
 * has not chosen keeps its password login, so switching the installation on
 * locks nobody out; the operator turns it on per tenant once every account has
 * an address.
 *
 * <p>{@code specification/public/email-login.md} §2.
 */
@Component
public class EmailTokenMode {

    /** Tenant setting (on {@code _tenant}, BOOLEAN): this tenant signs in with the e-mail code. */
    public static final String SETTING_KEY = "auth.email-token";

    private final SettingService settingService;
    private final boolean instanceEnabled;

    public EmailTokenMode(
            SettingService settingService, @Value("${vance.auth.email-token.enabled:false}") boolean instanceEnabled) {
        this.settingService = settingService;
        this.instanceEnabled = instanceEnabled;
    }

    /** The installation offers the e-mail code at all (endpoints, mail sender). */
    public boolean instanceEnabled() {
        return instanceEnabled;
    }

    /** Accounts of this tenant sign in with the e-mail code; their password no longer does. */
    public boolean enabledFor(String tenantId) {
        if (!instanceEnabled) {
            return false;
        }
        return settingService.getBooleanValue(
                tenantId, SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME, SETTING_KEY, false);
    }
}
