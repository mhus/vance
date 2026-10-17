package de.mhus.vance.brain.suggestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuggestionControllerTest {

    private static final String TENANT = "acme";

    private final List<SuggestionRequest> seen = new ArrayList<>();
    private final SecurityContext subject = SecurityContext.user("anna", TENANT, List.of());
    private RequestAuthority authority;
    private HttpServletRequest request;
    private SuggestionController controller;

    @BeforeEach
    void setUp() {
        SuggestionSource source = new SuggestionSource() {
            @Override
            public String name() {
                return "people";
            }

            @Override
            public List<FormSuggestionDto> suggest(SuggestionRequest r) {
                seen.add(r);
                return IntStream.range(0, 40)
                        .mapToObj(i -> new FormSuggestionDto("p" + i, null, List.of("p" + i), null, null))
                        .toList();
            }
        };
        authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        when(authority.contextOf(request)).thenReturn(subject);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("anna");
        controller = new SuggestionController(new SuggestionService(List.of(source)), authority);
    }

    @Test
    void emptyOrBlankQueryAnswersNothingWithoutAskingAnyone() {
        assertThat(controller.suggest(TENANT, "people", null, null, null, request))
                .isEmpty();
        assertThat(controller.suggest(TENANT, "people", "  ", "website", null, request))
                .isEmpty();
        assertThat(seen).isEmpty();
        verifyNoInteractions(authority);
    }

    @Test
    void requestCarriesCallerTrimmedQueryAndProject() {
        controller.suggest(TENANT, "people", "  ann ", "website", null, request);

        verify(authority).enforce(request, new Resource.Project(TENANT, "website"), Action.READ);
        SuggestionRequest r = seen.get(0);
        assertThat(r.tenantId()).isEqualTo(TENANT);
        assertThat(r.subject()).isSameAs(subject);
        assertThat(r.userLogin()).isEqualTo("anna");
        assertThat(r.projectId()).isEqualTo("website");
        assertThat(r.query()).isEqualTo("ann");
        assertThat(r.limit()).isEqualTo(8);
    }

    @Test
    void limitIsClampedToOneThroughTwentyFive() {
        assertThat(controller.suggest(TENANT, "people", "p", null, 0, request)).hasSize(1);
        assertThat(controller.suggest(TENANT, "people", "p", null, -5, request)).hasSize(1);
        assertThat(controller.suggest(TENANT, "people", "p", null, 100, request))
                .hasSize(25);
        assertThat(controller.suggest(TENANT, "people", "p", null, 12, request)).hasSize(12);
        assertThat(seen).extracting(SuggestionRequest::limit).containsExactly(1, 1, 25, 12);
    }

    @Test
    void blankProjectIdMeansNoProjectAndNoCheck() {
        controller.suggest(TENANT, "people", "p", " ", null, request);

        verify(authority, never()).enforce(eq(request), any(Resource.class), any(Action.class));
        assertThat(seen.get(0).projectId()).isNull();
    }

    @Test
    void unreadableProjectIsRefusedBeforeTheSourceIsAsked() {
        doThrow(new PermissionDeniedException(subject, new Resource.Project(TENANT, "secret"), Action.READ))
                .when(authority)
                .enforce(request, new Resource.Project(TENANT, "secret"), Action.READ);

        assertThatThrownBy(() -> controller.suggest(TENANT, "people", "p", "secret", null, request))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void unknownSourceAnswersEmpty() {
        assertThat(controller.suggest(TENANT, "not-installed", "p", null, null, request))
                .isEmpty();
        assertThat(seen).isEmpty();
    }
}
