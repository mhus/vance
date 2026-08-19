package de.mhus.vance.brain.centauri;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a {@link CentauriCursor} into an opaque string and back.
 *
 * <p>Base64url over compact JSON. The client must not interpret it: which
 * streams a feed has and how each source pages are implementation details,
 * and a client that parses the cursor would break on the first source that
 * changes its paging.
 *
 * <p>A cursor that does not decode is rejected rather than replaced by a
 * fresh one. Silently restarting at the top of the stream would look like
 * an endless scroll that occasionally loops — the worst kind of bug,
 * because it reads as a content problem rather than a paging one.
 */
@Service
public class CentauriCursorCodec {

    /** Bumped when the wire shape changes, so an old cursor is rejected, not misread. */
    static final int FORMAT_VERSION = 1;

    private final ObjectMapper mapper;

    public CentauriCursorCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(CentauriCursor cursor) {
        Wire wire = new Wire(
                FORMAT_VERSION,
                cursor.perStream(),
                cursor.watermark() == null ? null : cursor.watermark().toString(),
                cursor.exhausted());
        try {
            byte[] json = mapper.writeValueAsBytes(wire);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JacksonException e) {
            throw new CentauriException("could not encode feed cursor", e);
        }
    }

    public CentauriCursor decode(@Nullable String encoded) {
        if (StringUtils.isBlank(encoded)) {
            return CentauriCursor.fresh();
        }
        Wire wire;
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            wire = mapper.readValue(new String(json, StandardCharsets.UTF_8), Wire.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new CentauriException("malformed feed cursor", e);
        }
        if (wire == null) {
            throw new CentauriException("malformed feed cursor: empty payload");
        }
        if (wire.version() != FORMAT_VERSION) {
            throw new CentauriException(
                    "unsupported feed cursor version " + wire.version()
                            + " (expected " + FORMAT_VERSION + ")");
        }
        Instant watermark = null;
        if (StringUtils.isNotBlank(wire.watermark())) {
            try {
                watermark = Instant.parse(wire.watermark());
            } catch (RuntimeException e) {
                throw new CentauriException("malformed feed cursor watermark", e);
            }
        }
        return new CentauriCursor(
                wire.perStream() == null ? Map.of() : wire.perStream(),
                watermark,
                wire.exhausted() == null ? Set.of() : wire.exhausted());
    }

    /** Short field names keep the cursor short enough for a query-free POST body. */
    record Wire(
            @JsonProperty("v") int version,
            @JsonProperty("s") @Nullable Map<String, String> perStream,
            @JsonProperty("w") @Nullable String watermark,
            @JsonProperty("e") @Nullable Set<String> exhausted) { }
}
