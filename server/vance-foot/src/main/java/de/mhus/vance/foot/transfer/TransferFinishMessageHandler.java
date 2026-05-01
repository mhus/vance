package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferFinish;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: closes an inbound download lifecycle. */
@Component
class TransferFinishMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    TransferFinishMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.TRANSFER_FINISH;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TransferFinish finish = json.convertValue(envelope.getData(), TransferFinish.class);
        transfers.onTransferFinish(finish);
    }
}
