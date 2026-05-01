package de.mhus.vance.brain.permission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies {@link PermissionExceptionAdvice} maps
 * {@link PermissionDeniedException} to a 403 with the documented body
 * shape, regardless of which controller threw.
 */
class PermissionExceptionAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new PermissionExceptionAdvice())
                .build();
    }

    @Test
    void permissionDenied_isMappedTo403_withBodyShape() throws Exception {
        mockMvc.perform(get("/_test/throw"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("permission_denied"))
                .andExpect(jsonPath("$.action").value("WRITE"))
                .andExpect(jsonPath("$.resourceType").value("Project"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/_test/throw")
        public String throwIt() {
            throw new PermissionDeniedException(
                    SecurityContext.user("alice", "acme", java.util.List.of()),
                    new Resource.Project("acme", "proj"),
                    Action.WRITE);
        }
    }
}
