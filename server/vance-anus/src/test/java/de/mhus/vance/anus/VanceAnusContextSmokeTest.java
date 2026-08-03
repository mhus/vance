package de.mhus.vance.anus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Boots the full Anus (admin shell) Spring context against an <b>embedded</b>
 * MongoDB and asserts every singleton wires up — no external database, no
 * Docker.
 *
 * <p>Same rationale as the Brain smoke test: eager singleton instantiation
 * during context refresh catches broken bean wiring before it ever reaches a
 * running process. A real (empty) Mongo is present, so the startup paths that
 * touch it (system-tenant bootstrap, the simple-auth grant migration) run for
 * real instead of being mocked.
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><b>Mongo</b>: an in-process {@code mongod} via flapdoodle
 *       ({@code de.flapdoodle.embed.mongo.spring4x}), pinned through
 *       {@code de.flapdoodle.mongodb.embedded.version}; the app's own
 *       {@code spring.mongodb.uri} is blanked so the embedded wiring wins.</li>
 *   <li><b>Shell</b>: {@code spring.shell.interactive.enabled=false} stops
 *       Spring Shell's interactive runner from taking over stdin and blocking
 *       the test. ({@code OneShotCommandRunner} is a no-op here: neither
 *       {@code --sudo} nor {@code --setup} mode is active, so it falls through
 *       without {@code System.exit}.)</li>
 *   <li><b>Redis</b>: disabled. <b>Permission provider</b>: the real
 *       {@code MongoPermissionResolver} is already on the Anus classpath (via
 *       the simple-auth addon), so no test resolver is added.</li>
 *   <li>{@code vance.encryption.password} is required with no default, so a
 *       test value is supplied.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "de.flapdoodle.mongodb.embedded.version=7.0.12",
                "spring.mongodb.uri=",
                "spring.mongodb.database=vance-smoke",
                "spring.data.mongodb.auto-index-creation=false",
                "spring.shell.interactive.enabled=false",
                "vance.encryption.password=smoke-test",
                "vance.redis.enabled=false",
        })
class VanceAnusContextSmokeTest {

    @Autowired
    ApplicationContext context;

    @Test
    void springContext_boots_andInstantiatesAllSingletons() {
        assertThat(context).isNotNull();
    }
}
