package de.mhus.vance.brain.tools.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Surface-level checks of the sender pack: its one tool, who it belongs to, and what it is labelled as. */
class SmtpSenderToolFactoryTest {

    private final SmtpSenderToolFactory factory = new SmtpSenderToolFactory(mock(SettingsSecretResolver.class));

    @Test
    void a_pack_has_one_send_tool_that_is_a_service_of_the_pack() {
        Collection<Tool> tools = factory.create(pack("noreply"));

        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("noreply__send_message");
            assertThat(t.service()).isEqualTo("noreply");
            assertThat(t.labels()).contains("smtp_sender:noreply", "write", "side-effect");
        });
    }

    private static ServerToolDocument pack(String name) {
        return ServerToolDocument.builder()
                .name(name)
                .type(SmtpSenderToolFactory.TYPE_ID)
                .parameters(new LinkedHashMap<>(Map.of("host", "smtp.example.com")))
                .build();
    }
}
