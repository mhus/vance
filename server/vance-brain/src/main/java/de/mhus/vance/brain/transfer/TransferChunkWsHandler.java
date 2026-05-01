package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Foot → Brain: one chunk of an inbound upload. */
@Component
class TransferChunkWsHandler implements WsHandler {

    private final BrainTransferService transfers;
    private final ObjectMapper objectMapper;

    TransferChunkWsHandler(BrainTransferService transfers, ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return MessageType.TRANSFER_CHUNK;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope) {
        TransferChunk chunk = objectMapper.convertValue(envelope.getData(), TransferChunk.class);
        transfers.onTransferChunk(chunk);
    }
}
