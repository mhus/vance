package de.mhus.vance.foot;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.audit.ConversationAuditService;
import de.mhus.vance.foot.cli.FootRunner;
import de.mhus.vance.foot.cli.VanceFootCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the full Foot Spring context and asserts every singleton wires up.
 *
 * <p>This is a pure startup smoke test: Spring eagerly instantiates all
 * non-lazy singletons during context refresh, so a broken bean definition
 * (missing {@code @Autowired} on a multi-constructor bean, unsatisfiable
 * dependency, ambiguous injection) fails the test here — the same failure
 * a user would otherwise only see when launching {@code vance-foot.jar}.
 *
 * <p>{@link FootRunner} is replaced by a Mockito mock so the context boots
 * <em>without</em> launching the Picocli command — no WebSocket connect, no
 * REPL, no terminal takeover. We validate the object graph, not the CLI run.
 *
 * <p>{@code webEnvironment = NONE} mirrors production
 * ({@code WebApplicationType.NONE} in {@link VanceFootApplication}) and keeps
 * the test from pulling in a mock servlet environment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VanceFootContextSmokeTest {

    /** Replaces the CLI runner so context boot does not execute the command. */
    @MockitoBean
    FootRunner footRunner;

    @Autowired
    ApplicationContext context;

    @Test
    void springContext_boots_andInstantiatesAllSingletons() {
        assertThat(context).isNotNull();
        // Spot-check the two beans whose wiring is easiest to break: the
        // Picocli root (deep constructor graph) and the audit service whose
        // dual-constructor ambiguity previously crashed startup.
        assertThat(context.getBean(VanceFootCommand.class)).isNotNull();
        assertThat(context.getBean(ConversationAuditService.class)).isNotNull();
    }
}
