package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /ping} — sends a ping and reports round-trip latency. Independent
 * of the scheduled keep-alive loop in {@link ConnectionService}; useful for
 * ad-hoc latency probes without waiting for the next interval.
 */
@Component
public class PingCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public PingCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public String description() {
        return "Send a ping and print the round-trip time.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        long sent = System.currentTimeMillis();
        PongData pong = connection.request(
                MessageType.PING,
                PingData.builder().clientTimestamp(sent).build(),
                PongData.class,
                Duration.ofSeconds(5));
        long rtt = System.currentTimeMillis() - sent;
        long oneWay = pong.getServerTimestamp() - pong.getClientTimestamp();
        terminal.info("pong rtt=" + rtt + "ms one-way=" + oneWay + "ms");
    }
}
