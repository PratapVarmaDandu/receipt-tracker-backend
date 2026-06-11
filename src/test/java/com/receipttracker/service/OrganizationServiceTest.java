package com.receipttracker.service;

import com.receipttracker.service.EncryptionService;
import com.receipttracker.dto.CreateOrganizationRequest;
import com.receipttracker.dto.InviteMemberRequest;
import com.receipttracker.dto.OrgMemberDTO;
import com.receipttracker.dto.OrganizationDTO;
import com.receipttracker.model.OrgMembership;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.OrgMembership.OrgRole;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgMembershipRepository;
import com.receipttracker.repository.OrgOrderRepository;
import com.receipttracker.repository.OrganizationRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceTest {

    @Mock private OrganizationRepository orgRepo;
    @Mock private OrgMembershipRepository memberRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmailService emailService;
    @Mock private EncryptionService encryptionService;
    @Mock private OrgOrderRepository orgOrderRepo;

    @InjectMocks
    private OrganizationService service;

    private User owner;
    private User member;
    private Organization org;
    private OrgMembership ownerMembership;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");
        when(orgOrderRepo.findByOrgOrderByPlacedAtDesc(any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        owner = user(1L, "owner@example.com", "Alice Owner");
        member = user(2L, "member@example.com", "Bob Member");

        org = new Organization();
        org.setId(10L);
        org.setName("Spice Town");
        org.setSlug("spice-town");
        org.setPlan(Organization.OrgPlan.FREE);
        org.setStatus(Organization.OrgStatus.ACTIVE);
        org.setOwner(owner);
        org.setCreatedAt(LocalDateTime.now());

        ownerMembership = membership(1L, org, owner, "owner@example.com", OrgRole.OWNER, MemberStatus.ACTIVE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // create()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        void success_savesOrgAndOwnerMembership() {
            mockCurrentUser(owner);
            when(orgRepo.existsBySlug("spice-town")).thenReturn(false);
            when(orgRepo.save(any())).thenReturn(org);
            when(memberRepo.save(any())).thenReturn(ownerMembership);
            when(memberRepo.findByOrgOrderByInvitedAtDesc(org)).thenReturn(List.of(ownerMembership));

            CreateOrganizationRequest req = new CreateOrganizationRequest();
            req.setName("Spice Town");
            req.setSlug("spice-town");

            OrganizationDTO dto = service.create(req);

            assertThat(dto.getSlug()).isEqualTo("spice-town");
            assertThat(dto.getMyRole()).isEqualTo("OWNER");

            ArgumentCaptor<OrgMembership> memberCaptor = ArgumentCaptor.forClass(OrgMembership.class);
            verify(memberRepo).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRole()).isEqualTo(OrgRole.OWNER);
            assertThat(memberCaptor.getValue().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        }

        @Test
        void autoGeneratesSlugFromName_whenSlugNotProvided() {
            mockCurrentUser(owner);
            when(orgRepo.existsBySlug("spice-town")).thenReturn(false);
            when(orgRepo.save(any())).thenReturn(org);
            when(memberRepo.save(any())).thenReturn(ownerMembership);
            when(memberRepo.findByOrgOrderByInvitedAtDesc(org)).thenReturn(List.of(ownerMembership));

            CreateOrganizationRequest req = new CreateOrganizationRequest();
            req.setName("Spice Town");
            // slug not set — service should derive it

            service.create(req);

            ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
            verify(orgRepo).save(captor.capture());
            assertThat(captor.getValue().getSlug()).isEqualTo("spice-town");
        }

        @Test
        void slugifiesSpecialCharacters() {
            mockCurrentUser(owner);
            when(orgRepo.existsBySlug("my-business")).thenReturn(false);
            when(orgRepo.save(any())).thenAnswer(inv -> {
                Organization o = inv.getArgument(0);
                o.setId(99L);
                o.setOwner(owner);
                o.setCreatedAt(LocalDateTime.now());
                return o;
            });
            when(memberRepo.save(any())).thenReturn(ownerMembership);
            when(memberRepo.findByOrgOrderByInvitedAtDesc(any())).thenReturn(List.of(ownerMembership));

            CreateOrganizationRequest req = new CreateOrganizationRequest();
            req.setName("My Business!!!");

            service.create(req);

            ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
            verify(orgRepo).save(captor.capture());
            assertThat(captor.getValue().getSlug()).isEqualTo("my-business");
        }

        @Test
        void duplicateSlug_throws() {
            mockCurrentUser(owner);
            when(orgRepo.existsBySlug("spice-town")).thenReturn(true);

            CreateOrganizationRequest req = new CreateOrganizationRequest();
            req.setName("Spice Town");
            req.setSlug("spice-town");

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already exists");

            verify(orgRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listMine()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ListMine {

        @Test
        void returnsOrgsForCurrentUser() {
            mockCurrentUser(owner);
            when(memberRepo.findByUserAndStatusNot(owner, MemberStatus.REVOKED))
                    .thenReturn(List.of(ownerMembership));
            when(memberRepo.findByOrgOrderByInvitedAtDesc(org)).thenReturn(List.of(ownerMembership));

            List<OrganizationDTO> result = service.listMine();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSlug()).isEqualTo("spice-town");
            assertThat(result.get(0).getMyRole()).isEqualTo("OWNER");
        }

        @Test
        void returnsEmptyWhenNoOrgs() {
            mockCurrentUser(owner);
            when(memberRepo.findByUserAndStatusNot(owner, MemberStatus.REVOKED))
                    .thenReturn(Collections.emptyList());

            assertThat(service.listMine()).isEmpty();
        }

        @Test
        void excludesRevokedMemberships() {
            mockCurrentUser(owner);
            when(memberRepo.findByUserAndStatusNot(owner, MemberStatus.REVOKED))
                    .thenReturn(Collections.emptyList()); // revoked excluded by repo query

            assertThat(service.listMine()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBySlug()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class GetBySlug {

        @Test
        void success_returnsOrgWithRole() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.findByOrgOrderByInvitedAtDesc(org)).thenReturn(List.of(ownerMembership));

            OrganizationDTO dto = service.getBySlug("spice-town");

            assertThat(dto.getName()).isEqualTo("Spice Town");
            assertThat(dto.getMyRole()).isEqualTo("OWNER");
            assertThat(dto.getMemberCount()).isEqualTo(1);
        }

        @Test
        void unknownSlug_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("does-not-exist")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBySlug("does-not-exist"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void notAMember_throws() {
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBySlug("spice-town"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not a member");
        }

        @Test
        void revokedMember_treatedAsNonMember() {
            OrgMembership revoked = membership(5L, org, member, "member@example.com", OrgRole.STAFF, MemberStatus.REVOKED);
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.getBySlug("spice-town"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not a member");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // invite()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Invite {

        @Test
        void ownerCanInviteStaff() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, "bob@example.com", MemberStatus.REVOKED)).thenReturn(false);
            OrgMembership saved = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.PENDING);
            when(memberRepo.save(any())).thenReturn(saved);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("bob@example.com");
            req.setRole("STAFF");

            OrgMemberDTO dto = service.invite("spice-town", req);

            assertThat(dto.getInviteEmail()).isEqualTo("bob@example.com");
            assertThat(dto.getRole()).isEqualTo("STAFF");
            assertThat(dto.getStatus()).isEqualTo("PENDING");
            verify(emailService).sendOrgInvite(eq("bob@example.com"), any(), any(), eq("STAFF"), any());
        }

        @Test
        void adminCanInviteViewer() {
            OrgMembership adminMembership = membership(2L, org, member, "member@example.com", OrgRole.ADMIN, MemberStatus.ACTIVE);
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.of(adminMembership));
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, "carol@example.com", MemberStatus.REVOKED)).thenReturn(false);
            OrgMembership saved = membership(10L, org, null, "carol@example.com", OrgRole.VIEWER, MemberStatus.PENDING);
            when(memberRepo.save(any())).thenReturn(saved);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("carol@example.com");
            req.setRole("VIEWER");

            OrgMemberDTO dto = service.invite("spice-town", req);

            assertThat(dto.getRole()).isEqualTo("VIEWER");
            verify(emailService).sendOrgInvite(eq("carol@example.com"), any(), any(), eq("VIEWER"), any());
        }

        @Test
        void staffCannotInvite_throws() {
            OrgMembership staffMembership = membership(3L, org, member, "member@example.com", OrgRole.STAFF, MemberStatus.ACTIVE);
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.of(staffMembership));

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("carol@example.com");
            req.setRole("VIEWER");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Insufficient permissions");
            verify(emailService, never()).sendOrgInvite(any(), any(), any(), any(), any());
        }

        @Test
        void viewerCannotInvite_throws() {
            OrgMembership viewerMembership = membership(4L, org, member, "member@example.com", OrgRole.VIEWER, MemberStatus.ACTIVE);
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.of(viewerMembership));

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("carol@example.com");
            req.setRole("STAFF");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Insufficient permissions");
        }

        @Test
        void cannotInviteAsOwnerRole_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("someone@example.com");
            req.setRole("OWNER");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Cannot invite as OWNER");
        }

        @Test
        void cannotInviteOrgOwnerEmail_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("owner@example.com"); // same as org owner
            req.setRole("STAFF");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Cannot invite the organization owner");
        }

        @Test
        void duplicateActiveInvite_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, "bob@example.com", MemberStatus.REVOKED)).thenReturn(true);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("bob@example.com");
            req.setRole("STAFF");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("active invite already exists");
        }

        @Test
        void revokedMemberReInvite_reusesExistingRow() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            // no active invite (the existing row is REVOKED)
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, "bob@example.com", MemberStatus.REVOKED)).thenReturn(false);
            // a REVOKED row exists → should be reused, not inserted
            OrgMembership revoked = membership(7L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.REVOKED);
            when(memberRepo.findByOrgAndInviteEmail(org, "bob@example.com")).thenReturn(Optional.of(revoked));
            when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("bob@example.com");
            req.setRole("VIEWER");

            OrgMemberDTO result = service.invite("spice-town", req);

            ArgumentCaptor<OrgMembership> captor = ArgumentCaptor.forClass(OrgMembership.class);
            verify(memberRepo).save(captor.capture());
            OrgMembership saved = captor.getValue();
            // same entity id (reused row)
            assertThat(saved.getId()).isEqualTo(7L);
            assertThat(saved.getStatus()).isEqualTo(MemberStatus.PENDING);
            assertThat(saved.getRole()).isEqualTo(OrgRole.VIEWER);
            assertThat(saved.getUser()).isNull();
            assertThat(saved.getInviteToken()).isNotBlank();
        }

        @Test
        void invalidRole_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(any(), any(), any())).thenReturn(false);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("bob@example.com");
            req.setRole("SUPERUSER");

            assertThatThrownBy(() -> service.invite("spice-town", req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Invalid role");
        }

        @Test
        void inviteEmailNormalisedToLowercase() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.existsByOrgAndInviteEmailAndStatusNot(org, "bob@example.com", MemberStatus.REVOKED)).thenReturn(false);
            OrgMembership saved = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.PENDING);
            when(memberRepo.save(any())).thenReturn(saved);

            InviteMemberRequest req = new InviteMemberRequest();
            req.setEmail("BOB@Example.com");
            req.setRole("STAFF");

            service.invite("spice-town", req);

            ArgumentCaptor<OrgMembership> captor = ArgumentCaptor.forClass(OrgMembership.class);
            verify(memberRepo).save(captor.capture());
            assertThat(captor.getValue().getInviteEmail()).isEqualTo("bob@example.com");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // acceptInvite()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class AcceptInvite {

        @Test
        void success_setsActiveAndLinksUser() {
            OrgMembership pending = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.PENDING);
            pending.setInviteToken("test-token-123");
            mockCurrentUser(member); // member email = member@example.com... wait, need bob

            User bob = user(3L, "bob@example.com", "Bob");
            mockCurrentUser(bob);
            when(memberRepo.findByInviteToken("test-token-123")).thenReturn(Optional.of(pending));
            when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OrgMemberDTO dto = service.acceptInvite("test-token-123");

            assertThat(dto.getStatus()).isEqualTo("ACTIVE");
            ArgumentCaptor<OrgMembership> captor = ArgumentCaptor.forClass(OrgMembership.class);
            verify(memberRepo).save(captor.capture());
            assertThat(captor.getValue().getUser()).isEqualTo(bob);
            assertThat(captor.getValue().getJoinedAt()).isNotNull();
        }

        @Test
        void alreadyActive_idempotent_returnsWithoutSave() {
            User bob = user(3L, "bob@example.com", "Bob");
            OrgMembership active = membership(9L, org, bob, "bob@example.com", OrgRole.STAFF, MemberStatus.ACTIVE);
            active.setInviteToken("test-token-123");
            mockCurrentUser(bob);
            when(memberRepo.findByInviteToken("test-token-123")).thenReturn(Optional.of(active));

            OrgMemberDTO dto = service.acceptInvite("test-token-123");

            assertThat(dto.getStatus()).isEqualTo("ACTIVE");
            verify(memberRepo, never()).save(any());
        }

        @Test
        void wrongEmail_throws() {
            OrgMembership pending = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.PENDING);
            pending.setInviteToken("test-token-123");
            User carol = user(4L, "carol@example.com", "Carol");
            mockCurrentUser(carol);
            when(memberRepo.findByInviteToken("test-token-123")).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.acceptInvite("test-token-123"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not for your account");
            verify(memberRepo, never()).save(any());
        }

        @Test
        void revokedToken_throws() {
            OrgMembership revoked = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.REVOKED);
            revoked.setInviteToken("test-token-123");
            User bob = user(3L, "bob@example.com", "Bob");
            mockCurrentUser(bob);
            when(memberRepo.findByInviteToken("test-token-123")).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.acceptInvite("test-token-123"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("revoked");
        }

        @Test
        void unknownToken_throws() {
            mockCurrentUser(owner);
            when(memberRepo.findByInviteToken("bad-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptInvite("bad-token"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Invite not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // revokeMember()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class RevokeMember {

        @Test
        void ownerCanRevokeStaff() {
            OrgMembership staffMembership = membership(5L, org, member, "member@example.com", OrgRole.STAFF, MemberStatus.ACTIVE);
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.findById(5L)).thenReturn(Optional.of(staffMembership));
            when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.revokeMember("spice-town", 5L);

            ArgumentCaptor<OrgMembership> captor = ArgumentCaptor.forClass(OrgMembership.class);
            verify(memberRepo).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(MemberStatus.REVOKED);
        }

        @Test
        void cannotRevokeOwner_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.findById(1L)).thenReturn(Optional.of(ownerMembership));

            assertThatThrownBy(() -> service.revokeMember("spice-town", 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Cannot revoke the owner");
            verify(memberRepo, never()).save(any());
        }

        @Test
        void memberNotFound_throws() {
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeMember("spice-town", 999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Member not found");
        }

        @Test
        void memberBelongsToDifferentOrg_throws() {
            Organization otherOrg = new Organization();
            otherOrg.setId(99L);
            OrgMembership wrongOrgMembership = membership(5L, otherOrg, member, "member@example.com", OrgRole.STAFF, MemberStatus.ACTIVE);
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(ownerMembership));
            when(memberRepo.findById(5L)).thenReturn(Optional.of(wrongOrgMembership));

            assertThatThrownBy(() -> service.revokeMember("spice-town", 5L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Access denied");
        }

        @Test
        void staffCannotRevoke_throws() {
            OrgMembership staffMembership = membership(3L, org, owner, "owner@example.com", OrgRole.STAFF, MemberStatus.ACTIVE);
            mockCurrentUser(owner);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, owner)).thenReturn(Optional.of(staffMembership));

            assertThatThrownBy(() -> service.revokeMember("spice-town", 5L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Insufficient permissions");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getInviteByToken()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class GetInviteByToken {

        @Test
        void success_returnsMemberDTO() {
            OrgMembership pending = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.PENDING);
            pending.setInviteToken("tok-abc");
            when(memberRepo.findByInviteToken("tok-abc")).thenReturn(Optional.of(pending));

            OrgMemberDTO dto = service.getInviteByToken("tok-abc");

            assertThat(dto.getInviteEmail()).isEqualTo("bob@example.com");
            assertThat(dto.getStatus()).isEqualTo("PENDING");
        }

        @Test
        void tokenNotFound_throws() {
            when(memberRepo.findByInviteToken("bad-tok")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInviteByToken("bad-tok"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Invite not found");
        }

        @Test
        void revokedToken_throws() {
            OrgMembership revoked = membership(9L, org, null, "bob@example.com", OrgRole.STAFF, MemberStatus.REVOKED);
            revoked.setInviteToken("tok-abc");
            when(memberRepo.findByInviteToken("tok-abc")).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.getInviteByToken("tok-abc"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("revoked");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listMembers()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ListMembers {

        @Test
        void viewerCanListMembers() {
            OrgMembership viewerMembership = membership(4L, org, member, "member@example.com", OrgRole.VIEWER, MemberStatus.ACTIVE);
            mockCurrentUser(member);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, member)).thenReturn(Optional.of(viewerMembership));
            when(memberRepo.findByOrgOrderByInvitedAtDesc(org)).thenReturn(List.of(ownerMembership, viewerMembership));

            List<OrgMemberDTO> result = service.listMembers("spice-town");

            assertThat(result).hasSize(2);
        }

        @Test
        void nonMember_cannotListMembers_throws() {
            User outsider = user(99L, "outsider@example.com", "Outsider");
            mockCurrentUser(outsider);
            when(orgRepo.findBySlug("spice-town")).thenReturn(Optional.of(org));
            when(memberRepo.findByOrgAndUser(org, outsider)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listMembers("spice-town"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not a member");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void mockCurrentUser(User u) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn(u.getEmail());
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
        when(userRepo.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
    }

    private User user(Long id, String email, String name) {
        User u = new User();
        u.setId(id);
        u.setGoogleId("google-" + id);
        u.setEmail(email);
        u.setName(name);
        return u;
    }

    private OrgMembership membership(Long id, Organization o, User u, String email,
                                     OrgRole role, MemberStatus status) {
        OrgMembership m = new OrgMembership();
        m.setId(id);
        m.setOrg(o);
        m.setUser(u);
        m.setInviteEmail(email);
        m.setRole(role);
        m.setStatus(status);
        m.setInvitedAt(LocalDateTime.now());
        if (status == MemberStatus.ACTIVE) m.setJoinedAt(LocalDateTime.now());
        return m;
    }
}
