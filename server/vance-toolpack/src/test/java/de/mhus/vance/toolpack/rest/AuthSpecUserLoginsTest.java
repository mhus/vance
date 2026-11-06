package de.mhus.vance.toolpack.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthSpecUserLoginsTest {

    @Test
    void a_bearer_token_from_the_users_oauth_login_is_a_required_user_login() {
        AuthSpec auth = AuthSpec.bearer("{{secret:user:oauth.google.access_token}}");

        assertThat(auth.requiredUserLogins()).containsExactly("google");
    }

    @Test
    void a_static_secret_or_no_auth_needs_no_user_login() {
        assertThat(AuthSpec.bearer("{{secret:my.api.token}}").requiredUserLogins())
                .isEmpty();
        assertThat(AuthSpec.NONE.requiredUserLogins()).isEmpty();
    }

    @Test
    void a_project_or_tenant_secret_is_not_a_user_login() {
        assertThat(AuthSpec.bearer("{{secret:project:oauth.slack.access_token}}")
                        .requiredUserLogins())
                .isEmpty();
    }
}
