package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.ProcessCountsNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ProcessCountsState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds {@code process-counts} into {@link ProcessCountsState}, which
 * {@link de.mhus.vance.foot.ui.StatusBar} renders in the hints row.
 *
 * <p>Deliberately silent — nothing is written to the scrollback. A worker
 * starting or finishing is ambient information; printing a line per
 * transition would interleave with the conversation for no gain. The
 * status-bar segment is the whole point.
 *
 * <p>Requirement: planning/process-visibility.md §4.A
 */
@Component
@RequiredArgsConstructor
public class ProcessCountsHandler implements MessageHandler {

    private final ProcessCountsState state;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    public String messageType() {
        return MessageType.PROCESS_COUNTS;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ProcessCountsNotification msg = json.convertValue(
                envelope.getData(), ProcessCountsNotification.class);
        if (msg != null) {
            state.apply(msg);
        }
    }
}
