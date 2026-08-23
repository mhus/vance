package de.mhus.vance.api.megadodo;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One page of the feed, newest first.
 *
 * <p>Paging is <b>keyset</b>, not offset: the feed grows while it is being
 * read, and an offset would shift the boundary between two pages — the
 * same lesson the Centauri feed merge is built on. {@link #nextCursor}
 * encodes {@code (timestamp, id)} of the last row; {@code null} means the
 * end.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("megadodo")
public class MegadodoPageDto {

    @Builder.Default
    private List<MegadodoEventDto> items = new ArrayList<>();

    /** Opaque — pass back verbatim to get the next page. */
    private @Nullable String nextCursor;
}
