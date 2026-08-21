package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.shared.kit.KitException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Dispatch of a provisioning entry to its mechanism. */
class KitProvisioningHandlersTest {

    private record Fixed(String id, List<DesiredKit> result) implements KitProvisioningHandler {
        @Override
        public List<DesiredKit> discover(KitProvisioningContext context) {
            return result;
        }
    }

    private static KitProvisioningEntry entry(String type) {
        return new KitProvisioningEntry(type, "https://host", null,
                KitProvisioningAuthority.UPDATE);
    }

    @Test
    void discover_routesByType() {
        DesiredKit kit = new DesiredKit("https://host", "acme-crm", "r1",
                KitProvisioningAuthority.UPDATE);
        KitProvisioningHandlers handlers = new KitProvisioningHandlers(List.of(
                new Fixed("ode", List.of(kit)),
                new Fixed("git-list", List.of())));

        assertThat(handlers.discover("acme", "sales", entry("ode"))).containsExactly(kit);
        assertThat(handlers.discover("acme", "sales", entry("git-list"))).isEmpty();
    }

    @Test
    void discover_unknownType_namesWhatIsAvailable() {
        KitProvisioningHandlers handlers =
                new KitProvisioningHandlers(List.of(new Fixed("ode", List.of())));

        // A hand-written document with a typo is the likely cause, and a silent
        // skip would look like the source having nothing to offer.
        assertThatThrownBy(() -> handlers.discover("acme", "sales", entry("odee")))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("available: [ode]");
    }

    @Test
    void construction_duplicateId_breaksTheBoot() {
        assertThatThrownBy(() -> new KitProvisioningHandlers(List.of(
                new Fixed("ode", List.of()), new Fixed("ode", List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both claim id 'ode'");
    }

    @Test
    void ids_listsWhatThisBuildHas() {
        assertThat(new KitProvisioningHandlers(List.of(
                new Fixed("ode", List.of()), new Fixed("git-list", List.of()))).ids())
                .containsExactly("ode", "git-list");
    }
}
