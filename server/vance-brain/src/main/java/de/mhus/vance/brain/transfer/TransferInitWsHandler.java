package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Foot → Brain: Foot starts pushing a file (upload). */
@Component
class TransferInitWsHandler implements WsHandler {

    private final BrainTransferService transfers;
    private final ObjectMapper objectMapper;

    TransferInitWsHandler(BrainTransferService transfers, ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return MessageType.TRANSFER_INIT;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope) {
        TransferInit init = objectMapper.convertValue(envelope.getData(), TransferInit.class);
        transfers.onTransferInit(wsSession, init);
    }
}
