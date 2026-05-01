package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferComplete;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Foot → Brain: completion of a Brain-initiated download. */
@Component
class TransferCompleteWsHandler implements WsHandler {

    private final BrainTransferService transfers;
    private final ObjectMapper objectMapper;

    TransferCompleteWsHandler(BrainTransferService transfers, ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return MessageType.TRANSFER_COMPLETE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope) {
        TransferComplete complete = objectMapper.convertValue(
                envelope.getData(), TransferComplete.class);
        transfers.onTransferComplete(complete);
    }
}
