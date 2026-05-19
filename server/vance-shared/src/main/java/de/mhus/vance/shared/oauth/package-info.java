/**
 * OAuth flow persistence — {@link de.mhus.vance.shared.oauth.OAuthStateDocument}
 * stores in-flight Authorization-Code-Grant state between the init
 * redirect and the callback. Tenant- and provider-config storage lives
 * in regular YAML documents under {@code oauth/<providerId>.yaml} —
 * see {@code vance-brain/.../oauth/} for the Loader/Registry/Provider
 * beans that consume them.
 */
@NullMarked
package de.mhus.vance.shared.oauth;

import org.jspecify.annotations.NullMarked;
