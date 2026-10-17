package de.mhus.vance.brain.ai;

import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model picker's list: chat models selectable in a project (see
 * {@link ChatModelService#listSelectable}). Selecting one goes through the
 * {@code //model} engine command, which validates again — this list is
 * convenience, not enforcement.
 */
@RestController
@RequiredArgsConstructor
public class ChatModelController {

    private final ChatModelService chatModelService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/projects/{project}/chat-models")
    public ChatModelListDto list(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        return chatModelService.listSelectable(tenant, project);
    }
}
