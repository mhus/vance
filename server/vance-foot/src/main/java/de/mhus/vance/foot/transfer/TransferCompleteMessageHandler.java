package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferComplete;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: completion notification for a Foot-initiated upload. */
@Component
class TransferCompleteMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    TransferCompleteMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.TRANSFER_COMPLETE;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TransferComplete complete = json.convertValue(envelope.getData(), TransferComplete.class);
        transfers.onTransferComplete(complete);
    }
}
