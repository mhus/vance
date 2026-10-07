package de.mhus.vance.brain.guard.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit coverage of the {@link GuardHandlerRegistry}'s name contract. */
class GuardHandlerRegistryTest {

    private static GuardHandler named(String name) {
        return new GuardHandler() {
            @Override
            public String name() {
                return name;
            }
        };
    }

    @Test
    void find_returnsTheRegisteredHandler() {
        GuardHandler fence = named("fence-check");
        GuardHandlerRegistry registry = new GuardHandlerRegistry(List.of(fence));

        assertThat(registry.find("fence-check")).isSameAs(fence);
        assertThat(registry.find("other")).isNull();
        assertThat(registry.names()).containsExactly("fence-check");
    }

    @Test
    void duplicateName_failsTheBoot() {
        assertThatThrownBy(() -> new GuardHandlerRegistry(List.of(named("dup"), named("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void emptyRegistry_isEmpty() {
        GuardHandlerRegistry registry = new GuardHandlerRegistry(List.of());
        assertThat(registry.names()).isEmpty();
        assertThat(registry.find("anything")).isNull();
    }
}
