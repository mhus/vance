package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferInitResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: response to a Foot-initiated TransferInit (upload). */
@Component
class TransferInitResponseMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    TransferInitResponseMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.TRANSFER_INIT_RESPONSE;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TransferInitResponse rsp = json.convertValue(envelope.getData(), TransferInitResponse.class);
        transfers.onTransferInitResponse(rsp);
    }
}
