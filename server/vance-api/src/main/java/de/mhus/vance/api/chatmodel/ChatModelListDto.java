package de.mhus.vance.api.chatmodel;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The chat models selectable in a project: catalog models of provider
 * instances that are configured there, filtered by the
 * {@code chat.allowed-models} setting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("chatmodel")
public class ChatModelListDto {

    @Builder.Default
    private List<ChatModelDto> models = new ArrayList<>();

    /** Reasoning levels {@code //thinking} accepts, lowest first. */
    @Builder.Default
    private List<String> thinkingLevels = new ArrayList<>();
}
