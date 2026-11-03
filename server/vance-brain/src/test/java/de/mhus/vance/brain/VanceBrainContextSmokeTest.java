package de.mhus.vance.brain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Boots the full Brain Spring context against an <b>embedded</b> MongoDB and
 * asserts every singleton wires up — no external database, no Docker.
 *
 * <p>Spring eagerly instantiates all non-lazy singletons during context
 * refresh, so a broken bean definition (missing {@code @Autowired} on a
 * multi-constructor bean, unsatisfiable dependency, ambiguous injection) fails
 * the test here — the same failure a user would otherwise only hit when
 * launching the Brain server. Because a real (empty) Mongo is present, the
 * startup paths that touch it — system-tenant bootstrap, the backfill
 * migrations, the {@code ModelCatalog} load, the cluster/reclaimer
 * {@code ApplicationReadyEvent} hooks — all run for real instead of being
 * mocked out, so this exercises far more of the boot than a mock-everything
 * approach would.
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><b>Mongo</b>: an in-process {@code mongod} via flapdoodle
 *       ({@code de.flapdoodle.embed.mongo.spring4x}). The version is pinned
 *       through {@code de.flapdoodle.mongodb.embedded.version}; the binary is
 *       downloaded once and cached under {@code ~/.embedmongo}. The app's own
 *       {@code spring.mongodb.uri} is blanked so the embedded wiring wins.</li>
 *   <li><b>Redis</b>: disabled (also excluded in the main class; every consumer
 *       self-gates).</li>
 *   <li><b>LLM</b>: no provider is constructed at startup (lazy per turn).</li>
 *   <li><b>Permission provider</b>: the bare {@code vance-brain} module ships
 *       none, but {@code PermissionService} requires exactly one, so an
 *       allow-all resolver is supplied on the test classpath (see below).</li>
 * </ul>
 *
 * <p>{@code webEnvironment = RANDOM_PORT} boots a real embedded server so the
 * WebSocket {@code ServerContainer} wires up (a MOCK servlet env has none).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "de.flapdoodle.mongodb.embedded.version=7.0.12",
            "spring.mongodb.uri=",
            "spring.mongodb.database=vance-smoke",
            "spring.data.mongodb.auto-index-creation=false",
            "vance.bootstrap.acme=false",
            "vance.encryption.password=smoke-test",
        })
class VanceBrainContextSmokeTest {

    @Autowired
    ApplicationContext context;

    @Test
    void springContext_boots_andInstantiatesAllSingletons() {
        assertThat(context).isNotNull();
    }
}
