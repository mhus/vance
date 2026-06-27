package de.mhus.vance.brain.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActiveAppPromptResolverTest {

    @Test
    void nullActiveApp_returnsNull() {
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of());
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);
        assertThat(r.resolve(process(), null)).isNull();
    }

    @Test
    void blankFields_returnsNull() {
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of());
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);

        ActiveAppContext blankFolder = ActiveAppContext.builder()
                .folder("").app("calendar").build();
        ActiveAppContext blankApp = ActiveAppContext.builder()
                .folder("calendars/q3").app("").build();

        assertThat(r.resolve(process(), blankFolder)).isNull();
        assertThat(r.resolve(process(), blankApp)).isNull();
    }

    @Test
    void unknownApp_returnsNull_doesNotThrow() {
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of());
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);

        ActiveAppContext unknown = ActiveAppContext.builder()
                .folder("foo/").app("never-registered").build();

        assertThat(r.resolve(process(), unknown)).isNull();
    }

    @Test
    void knownApp_invokesPromptInject_withProcessScope() {
        VanceApplication app = new TestApp("calendar", "Hello from calendar app!");
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of(app));
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);

        ActiveAppContext ctx = ActiveAppContext.builder()
                .folder("calendars/q3").app("calendar").build();

        String result = r.resolve(process(), ctx);

        assertThat(result).isEqualTo("Hello from calendar app!");
    }

    @Test
    void promptInjectThrows_returnsNull() {
        VanceApplication app = new TestApp("kanban", null) {
            @Override
            public String promptInject(PromptInjectContext c) {
                throw new RuntimeException("boom");
            }
        };
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of(app));
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);

        ActiveAppContext ctx = ActiveAppContext.builder()
                .folder("boards/sprint-7").app("kanban").build();

        assertThat(r.resolve(process(), ctx)).isNull();
    }

    @Test
    void promptInjectReturnsNull_resolverReturnsNull() {
        VanceApplication app = new TestApp("slideshow", null);  // null inject
        VanceApplicationRegistry registry = new VanceApplicationRegistry(List.of(app));
        ActiveAppPromptResolver r = new ActiveAppPromptResolver(registry);

        ActiveAppContext ctx = ActiveAppContext.builder()
                .folder("decks/launch").app("slideshow").build();

        assertThat(r.resolve(process(), ctx)).isNull();
    }

    // ── helpers ────────────────────────────────────────

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = mock(ThinkProcessDocument.class);
        when(p.getTenantId()).thenReturn("acme");
        when(p.getProjectId()).thenReturn("proj");
        when(p.getSessionId()).thenReturn("session-1");
        when(p.getId()).thenReturn("process-1");
        return p;
    }

    /** Minimal in-test VanceApplication that returns a constant prompt. */
    private static class TestApp implements VanceApplication {
        private final String name;
        private final String inject;

        TestApp(String name, String inject) {
            this.name = name;
            this.inject = inject;
        }

        @Override public String appName() { return name; }

        @Override
        public RefreshResult refresh(RefreshContext ctx) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public String promptInject(PromptInjectContext ctx) {
            return inject;
        }
    }
}
