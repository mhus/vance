package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.KindHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KindRegistry}. Verifies that the built-in
 * kinds declared in {@link BuiltInKindHandlers} are all collected,
 * including the {@code formula} kind added for KaTeX/mhchem rendering.
 *
 * <p>Uses plain {@link KindHandler} lambdas (as {@link BuiltInKindHandlers}
 * does) instead of spinning up a Spring context — the registry's
 * {@link KindRegistry#collect()} postConstruct logic is framework-free.
 */
class KindRegistryTest {

    private static KindHandler name(String n) {
        return () -> n;
    }

    /** The same handlers {@link BuiltInKindHandlers} registers as @Beans. */
    private static final List<KindHandler> BUILT_INS = List.of(
            name("text"),
            name("slides"),
            name("schema"),
            name("application"),
            name("compose"),
            name("formula"));

    @Test
    void formulaKind_isRegistered() {
        KindRegistry registry = new KindRegistry(BUILT_INS);
        registry.collect();

        assertThat(registry.isKnown("formula")).isTrue();
        assertThat(registry.isKnown("FORMULA")).isTrue();
        assertThat(registry.names()).contains("formula");
    }

    @Test
    void formulaKind_handlerResolves() {
        KindRegistry registry = new KindRegistry(BUILT_INS);
        registry.collect();

        KindHandler handler = registry.handlerFor("formula");
        assertThat(handler).isNotNull();
        assertThat(handler.getName()).isEqualTo("formula");
    }

    @Test
    void allBuiltInKinds_areRegistered() {
        KindRegistry registry = new KindRegistry(BUILT_INS);
        registry.collect();

        assertThat(registry.names())
                .containsExactlyInAnyOrder(
                        "text", "slides", "schema", "application", "compose", "formula");
    }

    @Test
    void unknownKind_isNotKnown() {
        KindRegistry registry = new KindRegistry(BUILT_INS);
        registry.collect();

        assertThat(registry.isKnown("frobnicate")).isFalse();
        assertThat(registry.handlerFor("frobnicate")).isNull();
    }

    @Test
    void blankName_throws() {
        KindRegistry registry = new KindRegistry(List.of(name("  ")));
        org.assertj.core.api.Assertions.assertThatThrownBy(registry::collect)
                .isInstanceOf(IllegalStateException.class);
    }
}
