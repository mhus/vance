package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxListResponse {
    @Builder.Default
    private List<MaximegalonDto> items = new ArrayList<>();
    private int count;

    /**
     * {@code true} when the server stopped at its ceiling and there are older
     * threads it did not look at.
     *
     * <p>Only the by-document listing sets it today; the filtered inbox list
     * returns everything it matched. Said rather than implied because a
     * silently cut list reads as "that is all of them" — the same rule the tool
     * surface follows with {@code truncated}/{@code omittedMessages}.
     */
    private boolean truncated;
}
