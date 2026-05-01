package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferInitResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Foot → Brain: response to a Brain-initiated TransferInit (download). */
@Component
class TransferInitResponseWsHandler implements WsHandler {

    private final BrainTransferService transfers;
    private final ObjectMapper objectMapper;

    TransferInitResponseWsHandler(BrainTransferService transfers, ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return MessageType.TRANSFER_INIT_RESPONSE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope) {
        TransferInitResponse rsp = objectMapper.convertValue(
                envelope.getData(), TransferInitResponse.class);
        transfers.onTransferInitResponse(rsp);
    }
}
