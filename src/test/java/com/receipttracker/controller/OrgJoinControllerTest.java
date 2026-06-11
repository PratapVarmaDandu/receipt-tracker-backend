package com.receipttracker.controller;

import com.receipttracker.dto.OrgMemberDTO;
import com.receipttracker.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrgJoinControllerTest {

    @Mock  private OrganizationService orgService;
    @InjectMocks private OrgJoinController controller;

    private OrgMemberDTO pendingMember;

    @BeforeEach
    void setUp() {
        pendingMember = new OrgMemberDTO();
        pendingMember.setId(10L);
        pendingMember.setInviteEmail("bob@example.com");
        pendingMember.setRole("STAFF");
        pendingMember.setStatus("PENDING");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/org/join/{token}  — public, no auth needed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class GetInvite {

        @Test
        void validToken_returns200WithMemberDto() {
            when(orgService.getInviteByToken("valid-token")).thenReturn(pendingMember);

            ResponseEntity<?> response = controller.getInvite("valid-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(pendingMember);
        }

        @Test
        void unknownToken_returns400() {
            when(orgService.getInviteByToken("bad-token"))
                    .thenThrow(new RuntimeException("Invite not found"));

            ResponseEntity<?> response = controller.getInvite("bad-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("not found");
        }

        @Test
        void revokedToken_returns400() {
            when(orgService.getInviteByToken("revoked-token"))
                    .thenThrow(new RuntimeException("This invite has been revoked"));

            ResponseEntity<?> response = controller.getInvite("revoked-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("revoked");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/org/join/{token}  — auth required (principal resolved by service via SecurityContext)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class AcceptInvite {

        @Test
        void validToken_returnsActivatedMember() {
            OrgMemberDTO activated = new OrgMemberDTO();
            activated.setId(10L);
            activated.setInviteEmail("bob@example.com");
            activated.setRole("STAFF");
            activated.setStatus("ACTIVE");

            when(orgService.acceptInvite("valid-token")).thenReturn(activated);

            ResponseEntity<?> response = controller.acceptInvite("valid-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((OrgMemberDTO) response.getBody()).getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        void emailMismatch_returns400() {
            when(orgService.acceptInvite("valid-token"))
                    .thenThrow(new RuntimeException("This invite was sent to a different email address"));

            ResponseEntity<?> response = controller.acceptInvite("valid-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("different email");
        }

        @Test
        void alreadyAccepted_returnsIdempotent200() {
            OrgMemberDTO already = new OrgMemberDTO();
            already.setStatus("ACTIVE");
            when(orgService.acceptInvite("valid-token")).thenReturn(already);

            ResponseEntity<?> response = controller.acceptInvite("valid-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void revokedToken_returns400() {
            when(orgService.acceptInvite("revoked"))
                    .thenThrow(new RuntimeException("This invite has been revoked"));

            ResponseEntity<?> response = controller.acceptInvite("revoked");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void unknownToken_returns400() {
            when(orgService.acceptInvite("bad"))
                    .thenThrow(new RuntimeException("Invite not found"));

            ResponseEntity<?> response = controller.acceptInvite("bad");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
