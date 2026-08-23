package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ProjectActivationRegistry#leaseHoldingSize()} — the count the lease
 * drift detection compares against. Podless projects are activated here on
 * purpose but hold no lease, so counting them would make the comparison
 * permanently short.
 */
class ProjectActivationRegistryTest {

    @Test
    void leaseHoldingSize_excludesPodlessProjects() {
        ProjectActivationRegistry registry = new ProjectActivationRegistry();
        registry.activate("acme", "kunde-x");
        registry.activate("acme", "_user_marvin");
        registry.activate("acme", "_tenant");

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.leaseHoldingSize()).isEqualTo(1);
    }

    @Test
    void leaseHoldingSize_isZeroForAPodWithOnlyUserHubs() {
        ProjectActivationRegistry registry = new ProjectActivationRegistry();
        registry.activate("acme", "_user_marvin");

        assertThat(registry.leaseHoldingSize()).isZero();
    }
}
