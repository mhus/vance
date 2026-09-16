package de.mhus.vance.brain.chattheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@link ChatThemeController#themeCss} — the chat theme CSS endpoint.
 * These tests pin the contract: project READ enforcement, the
 * resolve → sanitize → scope pipeline, the pre-scoped pass-through,
 * and the {@code Content-Type} / {@code Cache-Control} / {@code ETag}
 * response headers. The resolver's own fallback semantics live in
 * {@link ChatThemeResolverTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChatThemeControllerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Mock
    private ChatThemeResolver chatThemeResolver;

    @Mock
    private RequestAuthority authority;

    @Mock
    private HttpServletRequest httpRequest;

    private ChatThemeController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatThemeController(chatThemeResolver, authority);
    }

    @Test
    void themeCss_scopesAndServes() {
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme")).thenReturn("h1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Every selector is fenced to the transcript root, doubled so
        // the specificity matches the chat's Vue scoped styles.
        assertThat(resp.getBody()).isEqualTo(".chat-theme.chat-theme h1 { color: red; }");
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("text/css;charset=utf-8"));
        // `private`, not `public`: the answer depends on the project
        // layer, a shared cache would hand one project's theme to the
        // next caller.
        assertThat(resp.getHeaders().getCacheControl()).contains("max-age=60").contains("private");
        assertThat(resp.getHeaders().getETag()).isNotBlank();
    }

    @Test
    void themeCss_passesNameToResolver_unmodified() {
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme")).thenReturn("/* x */");

        controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        // The controller never interprets the name — invalid-name and
        // miss handling (fallback to default) is the resolver's job.
        verify(chatThemeResolver).resolveStylesheet(TENANT, PROJECT, "acme");
    }

    @Test
    void themeCss_enforcesProjectRead() {
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme")).thenReturn("h1 { color: red; }");

        controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        // Same visibility as the recipe listing: a project READ check,
        // deliberately no per-document gate (the stylesheet is config,
        // not content).
        verify(authority).enforce(httpRequest, new Resource.Project(TENANT, PROJECT), Action.READ);
    }

    @Test
    void themeCss_externalUrlInCss_filteredOut() {
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme"))
                .thenReturn(".note { background: url('https://evil/x.png') red; }\nh1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        assertThat(resp.getBody())
                .doesNotContain("evil")
                .contains("url()")
                .contains(".chat-theme.chat-theme h1 { color: red; }");
    }

    @Test
    void themeCss_atImport_filteredOut() {
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme"))
                .thenReturn("@import 'https://evil/x.css';\nh1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        assertThat(resp.getBody()).doesNotContain("@import").doesNotContain("evil");
    }

    @Test
    void themeCss_preScopedSelector_passedThrough() {
        // The dark-mode escape hatch: the mode lives on an ancestor
        // (<html>), a descendant prefix could not address it — the
        // author writes the scope class themselves and the prefixer
        // leaves the selector alone.
        String preScoped = ".chat-theme[data-mode=dark] .msg-user { color: blue; }";
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "acme")).thenReturn(preScoped);

        ResponseEntity<String> resp = controller.themeCss(TENANT, PROJECT, "acme", httpRequest);

        assertThat(resp.getBody()).contains(preScoped);
    }

    @Test
    void themeCss_invalidName_stillServesDefault_notAnError() {
        // The resolver degrades traversal-shaped input to the default
        // theme — the endpoint has no 4xx path for a styling problem,
        // so the client's <style> injection never sees an error state.
        when(chatThemeResolver.resolveStylesheet(TENANT, PROJECT, "../evil")).thenReturn("h1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, PROJECT, "../evil", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(".chat-theme.chat-theme h1 { color: red; }");
    }
}
