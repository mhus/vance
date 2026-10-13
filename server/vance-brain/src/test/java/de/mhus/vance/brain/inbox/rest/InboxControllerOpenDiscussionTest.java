package de.mhus.vance.brain.inbox.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.InboxDiscussionOpenRequest;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * <b>A discussion does not need an object.</b> The endpoint grew out of the
 * document-anchored case and made {@code documentId} mandatory — so a free
 * conversation, the kind the Trillian heads live in, could not be opened at
 * all. Absent document means the thread stands on its own title and body.
 */
class InboxControllerOpenDiscussionTest {

    private static final String TENANT = "acme";
    private static final String ME = "wile.coyote";
    private static final String TARGET = "road.runner";
    private static final String DOC_ID = "d1";

    private MaximegalonService service;
    private RequestAuthority authority;
    private DocumentService documentService;
    private InboxController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(MaximegalonService.class);
        authority = mock(RequestAuthority.class);
        documentService = mock(DocumentService.class);
        controller = new InboxController(
                service,
                new InboxEffectRegistry(List.of()),
                mock(TeamService.class),
                mock(ProjectService.class),
                authority,
                new InboxAuthz(mock(TeamService.class)),
                documentService,
                mock(UserService.class));

        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(ME);

        // create() echoes its argument so the mapper has a document to map.
        when(service.create(any())).thenAnswer(inv -> {
            MaximegalonDocument doc = inv.getArgument(0);
            doc.setId("t9");
            return doc;
        });
    }

    private static InboxDiscussionOpenRequest request(String documentId) {
        return InboxDiscussionOpenRequest.builder()
                .documentId(documentId)
                .assignedToUserId(TARGET)
                .title("Hello from the outside")
                .body("A free conversation, no document involved.")
                .build();
    }

    @Test
    void withoutDocument_createsAFreeTextDiscussion() {
        controller.openDiscussion(TENANT, request(null), request);

        ArgumentCaptor<MaximegalonDocument> captor = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(service).create(captor.capture());
        MaximegalonDocument created = captor.getValue();
        assertThat(created.getType()).isEqualTo(MaximegalonType.OUTPUT_TEXT);
        assertThat(created.getDocumentRef()).isNull();
        assertThat(created.getAssignedToUserId()).isEqualTo(TARGET);
        assertThat(created.getOriginatorUserId()).isEqualTo(ME);
        assertThat(created.getTags()).containsExactly("discussion");
        assertThat(created.isRequiresAction()).isFalse();
        assertThat(created.getStatus()).isEqualTo(MaximegalonStatus.PENDING);

        // No document to anchor: none is read, none is looked up.
        verify(documentService, never()).findById(any());
        verify(authority, never())
                .enforce(any(HttpServletRequest.class), any(Resource.Document.class), any(Action.class));

        // The delivery gate is still the assignee's inbox.
        verify(authority).enforce(request, new Resource.InboxItem(TENANT, "", TARGET), Action.WRITE);
    }

    @Test
    void withDocument_anchorsTheThreadToIt() {
        when(documentService.findById(DOC_ID))
                .thenReturn(Optional.of(DocumentDocument.builder()
                        .id(DOC_ID)
                        .tenantId(TENANT)
                        .projectId("p1")
                        .path("notes/a.md")
                        .title("A")
                        .mimeType("text/markdown")
                        .build()));

        controller.openDiscussion(TENANT, request(DOC_ID), request);

        ArgumentCaptor<MaximegalonDocument> captor = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(service).create(captor.capture());
        MaximegalonDocument created = captor.getValue();
        assertThat(created.getType()).isEqualTo(MaximegalonType.OUTPUT_DOCUMENT);
        assertThat(created.getDocumentRef()).isNotNull();
        assertThat(created.getDocumentRef().getDocumentId()).isEqualTo(DOC_ID);

        // Reading the anchored document is part of opening the thread about it.
        verify(authority).enforce(request, new Resource.Document(TENANT, "p1", "notes/a.md"), Action.READ);
    }

    @Test
    void withForeignTenantDocument_isNotFound() {
        when(documentService.findById(DOC_ID))
                .thenReturn(Optional.of(DocumentDocument.builder()
                        .id(DOC_ID)
                        .tenantId("other")
                        .projectId("p1")
                        .path("notes/a.md")
                        .build()));

        assertThatThrownBy(() -> controller.openDiscussion(TENANT, request(DOC_ID), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Document not found");

        verify(service, never()).create(any());
    }
}
