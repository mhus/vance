package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.ClientFileUploadRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Brain → Foot: trigger Foot to upload a local file. */
@Component
class ClientFileUploadRequestMessageHandler implements MessageHandler {

    private final FootTransferService transfers;
    private final ObjectMapper json = JsonMapper.builder().build();

    ClientFileUploadRequestMessageHandler(FootTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String messageType() {
        return MessageType.CLIENT_FILE_UPLOAD_REQUEST;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ClientFileUploadRequest req = json.convertValue(
                envelope.getData(), ClientFileUploadRequest.class);
        transfers.onUploadRequest(req);
    }
}
