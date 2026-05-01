package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferFinish;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Foot → Brain: closes an inbound upload lifecycle. */
@Component
class TransferFinishWsHandler implements WsHandler {

    private final BrainTransferService transfers;
    private final ObjectMapper objectMapper;

    TransferFinishWsHandler(BrainTransferService transfers, ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return MessageType.TRANSFER_FINISH;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope) {
        TransferFinish finish = objectMapper.convertValue(envelope.getData(), TransferFinish.class);
        transfers.onTransferFinish(finish);
    }
}
