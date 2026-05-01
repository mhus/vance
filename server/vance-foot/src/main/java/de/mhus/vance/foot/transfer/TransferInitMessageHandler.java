package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: opens an inbound download. */
@Component
class TransferInitMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    TransferInitMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.TRANSFER_INIT;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TransferInit init = json.convertValue(envelope.getData(), TransferInit.class);
        transfers.onTransferInit(init);
    }
}
