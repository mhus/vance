package de.mhus.vance.runner.protocol;

import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frame inside the envelope: a 4-byte big-endian header length, the header as
 * UTF-8 JSON, then the body to the end of the stream. A length prefix instead of a
 * delimiter keeps the header unambiguous whatever the body holds, and lets the body
 * stream without being measured first.
 */
final class Frames {

    /** A header is a few hundred bytes; anything near this is hostile or broken. */
    static final int MAX_HEADER_BYTES = 1024 * 1024;

    static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private Frames() {}

    static void writeHeader(OutputStream out, Object header) throws IOException {
        byte[] json;
        try {
            json = MAPPER.writeValueAsBytes(header);
        } catch (JacksonException e) {
            throw new IOException("Cannot serialise the header: " + e.getMessage(), e);
        }
        if (json.length > MAX_HEADER_BYTES) {
            throw new IOException("Header too large: " + json.length + " bytes");
        }
        out.write(ByteBuffer.allocate(4).putInt(json.length).array());
        out.write(json);
    }

    static <T> T readHeader(InputStream in, Class<T> type) {
        byte[] json;
        try {
            int length = new DataInputStream(in).readInt();
            if (length <= 0 || length > MAX_HEADER_BYTES) {
                throw new RunnerProtocolException(Kind.MALFORMED, "Header length out of range: " + length);
            }
            json = in.readNBytes(length);
            if (json.length != length) {
                throw new RunnerProtocolException(Kind.MALFORMED, "Stream ends inside the header");
            }
        } catch (EOFException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Stream ends before the header", e);
        } catch (IOException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Cannot read the header: " + e.getMessage(), e);
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Header is not valid: " + e.getOriginalMessage(), e);
        }
    }
}
