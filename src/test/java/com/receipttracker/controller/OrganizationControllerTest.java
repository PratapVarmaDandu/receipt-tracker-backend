package com.receipttracker.controller;

import com.receipttracker.dto.*;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationControllerTest {

    @Mock  private OrganizationService orgService;
    @InjectMocks private OrganizationController controller;

    private OrganizationDTO sampleOrg;
    private OrgMemberDTO sampleMember;

    @BeforeEach
    void setUp() {
        sampleOrg = new OrganizationDTO();
        sampleOrg.setId(1L);
        sampleOrg.setName("Spice Town");
        sampleOrg.setSlug("spice-town");
        sampleOrg.setMyRole("OWNER");

        sampleMember = new OrgMemberDTO();
        sampleMember.setId(2L);
        sampleMember.setInviteEmail("bob@example.com");
        sampleMember.setRole("STAFF");
        sampleMember.setStatus("PENDING");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/organizations
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        void success_returns200WithDto() {
            when(orgService.create(any())).thenReturn(sampleOrg);

            CreateOrganizationRequest req = new CreateOrganizationRequest();
            req.setName("Spice Town");
            req.setSlug("spice-town");

            ResponseEntity<?> response = controller.create(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(sampleOrg);
        }

        @Test
        void serviceThrows_returns400WithErrorMessage() {
            when(orgService.create(any())).thenThrow(new RuntimeException("An organization with that URL already exists."));

            ResponseEntity<?> response = controller.create(new CreateOrganizationRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error"))
                    .isEqualTo("An organization with that URL already exists.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/organizations
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ListMine {

        @Test
        void returns200WithList() {
            when(orgService.listMine()).thenReturn(List.of(sampleOrg));

            ResponseEntity<List<OrganizationDTO>> response = controller.listMine();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).getSlug()).isEqualTo("spice-town");
        }

        @Test
        void returnsEmptyList_whenNoOrgs() {
            when(orgService.listMine()).thenReturn(List.of());

            ResponseEntity<List<OrganizationDTO>> response = controller.listMine();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/organizations/{slug}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Get {

        @Test
        void success_returns200() {
            when(orgService.getBySlug("spice-town")).thenReturn(sampleOrg);

            ResponseEntity<?> response = controller.get("spice-town");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(sampleOrg);
        }

        @Test
        void notFound_returns400() {
            when(orgService.getBySlug("unknown")).thenThrow(new RuntimeException("Organization not found: unknown"));

            ResponseEntity<?> response = controller.get("unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("not found");
        }

        @Test
        void notMember_returns400() {
            when(orgService.getBySlug("spice-town")).thenThrow(new RuntimeException("You are not a member of this organization"));

            ResponseEntity<?> response = controller.get("spice-town");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("not a member");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/organizations/{slug}/members
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ListMembers {

        @Test
        void success_returns200WithMembers() {
            when(orgService.listMembers("spice-town")).thenReturn(List.of(sampleMember));

            ResponseEntity<?> response = controller.listMembers("spice-town");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) response.getBody()).hasSize(1);
        }

        @Test
        void insufficientPermissions_returns400() {
            when(orgService.listMembers("spice-town")).thenThrow(new RuntimeException("Insufficient permissions"));

            ResponseEntity<?> response = controller.listMembers("spice-town");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/organizations/{slug}/members
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Invite {

        @Test
        void success_returns200WithMemberDto() {
            when(orgService.invite(eq("spice-town"), any())).thenReturn(sampleMember);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("bob@example.com");
            req.setRole("STAFF");

            ResponseEntity<?> response = controller.invite("spice-town", req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(sampleMember);
        }

        @Test
        void duplicateInvite_returns400() {
            when(orgService.invite(eq("spice-town"), any()))
                    .thenThrow(new RuntimeException("An active invite already exists for bob@example.com"));

            ResponseEntity<?> response = controller.invite("spice-town", new InviteMemberRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("active invite");
        }

        @Test
        void insufficientRole_returns400() {
            when(orgService.invite(eq("spice-town"), any()))
                    .thenThrow(new RuntimeException("Insufficient permissions — requires ADMIN"));

            ResponseEntity<?> response = controller.invite("spice-town", new InviteMemberRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("Insufficient permissions");
        }

        @Test
        void cannotInviteOwnerRole_returns400() {
            when(orgService.invite(eq("spice-town"), any()))
                    .thenThrow(new RuntimeException("Cannot invite as OWNER"));

            ResponseEntity<?> response = controller.invite("spice-town", new InviteMemberRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /api/organizations/{slug}/members/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Revoke {

        @Test
        void success_returns200WithMessage() {
            doNothing().when(orgService).revokeMember("spice-town", 5L);

            ResponseEntity<?> response = controller.revoke("spice-town", 5L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((Map<?, ?>) response.getBody()).get("message")).isEqualTo("Member revoked");
        }

        @Test
        void cannotRevokeOwner_returns400() {
            doThrow(new RuntimeException("Cannot revoke the owner"))
                    .when(orgService).revokeMember("spice-town", 1L);

            ResponseEntity<?> response = controller.revoke("spice-town", 1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).asString().contains("Cannot revoke");
        }

        @Test
        void memberNotFound_returns400() {
            doThrow(new RuntimeException("Member not found"))
                    .when(orgService).revokeMember("spice-town", 999L);

            ResponseEntity<?> response = controller.revoke("spice-town", 999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
