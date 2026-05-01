package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: one chunk of an inbound download. */
@Component
class TransferChunkMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    TransferChunkMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.TRANSFER_CHUNK;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TransferChunk chunk = json.convertValue(envelope.getData(), TransferChunk.class);
        transfers.onTransferChunk(chunk);
    }
}
