package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Identifies the kind of client connecting to the Brain.
 *
 * Sent during the WebSocket handshake via the {@code X-Vance-Client-Type} header.
 * Wire form is the lowercase name.
 */
@GenerateTypeScript("ws")
public enum ClientType {

    CLI("cli"),
    /**
     * Browser-based UI (`@vance/vance-face`, chat-editor). Browsers
     * cannot send custom headers on the WebSocket upgrade, so web
     * clients authenticate via query parameters
     * ({@code ?token=}, {@code ?clientType=}, {@code ?clientVersion=}).
     * Web clients do not register {@code client-tool-*} frames —
     * workspace tools are CLI/desktop concern.
     */
    WEB("web"),
    DESKTOP("desktop"),
    MOBILE("mobile");

    private final String wireValue;

    ClientType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ClientType fromWire(@Nullable String value) {
        if (value == null) {
            throw new IllegalArgumentException("clientType value is null");
        }
        for (ClientType ct : values()) {
            if (ct.wireValue.equalsIgnoreCase(value)) {
                return ct;
            }
        }
        throw new IllegalArgumentException("Unknown clientType: " + value);
    }
}
