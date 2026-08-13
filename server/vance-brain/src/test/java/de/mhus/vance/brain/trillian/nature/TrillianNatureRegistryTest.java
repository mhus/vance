package de.mhus.vance.brain.trillian.nature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Nature id is not only a lookup key — it is spliced into the service
 * account name {@code _trillian-<nature>-<instance>} and into three
 * recipe names. An id that cannot survive that has to be caught at boot,
 * because at runtime it degrades into "the Nature silently does nothing".
 */
class TrillianNatureRegistryTest {

    @Test
    void aWordId_isAccepted() {
        // The point of the three-part account name: ids are words now,
        // not single letters that need a legend.
        TrillianNatureRegistry registry = registryOf(new FakeNature("alpha"));

        assertThat(registry.resolve("alpha").id()).isEqualTo("alpha");
    }

    @Test
    void anIdWithADash_isRefused() {
        // _trillian-fast-cheap-4711 cannot be split back into Nature and
        // instance — the reader cannot tell which dash is the separator.
        assertThatThrownBy(() -> registryOf(new FakeNature("fast-cheap")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fast-cheap");
    }

    @Test
    void anUppercaseId_isRefused() {
        assertThatThrownBy(() -> registryOf(new FakeNature("Alpha")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEmptyId_isRefused() {
        // Previously skipped with a warning, which left the Nature
        // unreachable and looking broken rather than misconfigured.
        assertThatThrownBy(() -> registryOf(new FakeNature("")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRecipeFamilyNames_areReserved() {
        // A Nature 'user' would resolve its control recipe to
        // 'trillian-user' — the prefix the user-loop family is built
        // from. It would load a recipe belonging to something else.
        assertThatThrownBy(() -> registryOf(new FakeNature("user")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trillian-user");
        assertThatThrownBy(() -> registryOf(new FakeNature("worker")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownId_stillFallsBack() {
        // Unknown is a recipe pointing at a Nature that isn't deployed —
        // a configuration mismatch, not a broken build. It must not kill
        // a running engine.
        TrillianNatureRegistry registry = registryOf(new FakeNature("alpha"));

        assertThat(registry.resolve("beta")).isNotNull();
    }

    private static TrillianNatureRegistry registryOf(TrillianNature... natures) {
        return new TrillianNatureRegistry(List.of(natures));
    }

    /** Minimal Nature: the registry only ever reads the id. */
    private record FakeNature(String id) implements TrillianNature {
        @Override
        public String title() {
            return "Fake " + id;
        }
    }
}
