package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.OrgFeature;
import com.receipttracker.model.OrgMembership;
import com.receipttracker.model.OrgMembership.MemberStatus;
import com.receipttracker.model.OrgMembership.OrgRole;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgFeatureRepository;
import com.receipttracker.repository.OrgMembershipRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeatureEntitlementServiceTest {

    @Mock private OrgFeatureRepository featureRepo;
    @Mock private OrgMembershipRepository memberRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks private FeatureEntitlementService service;

    private User user;
    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "gatingEnabled", true);
        user = user(1L, "user@test.com");
        orgA = org(10L, "org-a");
        orgB = org(11L, "org-b");
        mockCurrentUser(user);
        when(memberRepo.findByUserAndStatus(user, MemberStatus.ACTIVE)).thenReturn(List.of());
        when(featureRepo.findByOrg(orgA)).thenReturn(List.of());
        when(featureRepo.findByOrg(orgB)).thenReturn(List.of());
    }

    @Nested
    class GetMyFeatures {

        @Test
        void gatingDisabledReturnsAllFeatures() {
            ReflectionTestUtils.setField(service, "gatingEnabled", false);
            assertThat(service.getMyFeatures()).containsExactlyInAnyOrder(AppFeature.values());
        }

        @Test
        void noMembershipsReturnsEmpty() {
            assertThat(service.getMyFeatures()).isEmpty();
        }

        @Test
        void unionAcrossMultipleOrgs() {
            when(memberRepo.findByUserAndStatus(user, MemberStatus.ACTIVE))
                    .thenReturn(List.of(membership(orgA), membership(orgB)));
            when(featureRepo.findByOrg(orgA)).thenReturn(List.of(grant(orgA, AppFeature.GARAGE, null)));
            when(featureRepo.findByOrg(orgB)).thenReturn(List.of(grant(orgB, AppFeature.DOCUMENT_VAULT, null)));

            assertThat(service.getMyFeatures())
                    .containsExactlyInAnyOrder(AppFeature.GARAGE, AppFeature.DOCUMENT_VAULT);
        }

        @Test
        void expiredGrantExcluded() {
            when(memberRepo.findByUserAndStatus(user, MemberStatus.ACTIVE))
                    .thenReturn(List.of(membership(orgA)));
            when(featureRepo.findByOrg(orgA)).thenReturn(List.of(
                    grant(orgA, AppFeature.GARAGE, LocalDateTime.now().minusDays(1)),
                    grant(orgA, AppFeature.JOB_TRACKER, LocalDateTime.now().plusDays(30))));

            assertThat(service.getMyFeatures()).containsExactly(AppFeature.JOB_TRACKER);
        }
    }

    @Nested
    class RequireFeature {

        @Test
        void throwsWhenMissing() {
            assertThatThrownBy(() -> service.requireFeature(AppFeature.GARAGE))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("FEATURE_LOCKED")
                    .hasMessageContaining("GARAGE");
        }

        @Test
        void passesWhenGranted() {
            when(memberRepo.findByUserAndStatus(user, MemberStatus.ACTIVE))
                    .thenReturn(List.of(membership(orgA)));
            when(featureRepo.findByOrg(orgA)).thenReturn(List.of(grant(orgA, AppFeature.GARAGE, null)));

            service.requireFeature(AppFeature.GARAGE); // no exception
        }

        @Test
        void passesWhenGatingDisabled() {
            ReflectionTestUtils.setField(service, "gatingEnabled", false);
            service.requireFeature(AppFeature.SHOP_POS); // no exception, no user lookup
        }
    }

    @Nested
    class RequireOrgFeature {

        @Test
        void throwsWhenOrgLacksFeature() {
            when(featureRepo.findByOrgAndFeature(orgA, AppFeature.SHOP_POS)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireOrgFeature(orgA, AppFeature.SHOP_POS))
                    .hasMessageContaining("FEATURE_LOCKED");
        }

        @Test
        void throwsWhenGrantExpired() {
            when(featureRepo.findByOrgAndFeature(orgA, AppFeature.SHOP_POS))
                    .thenReturn(Optional.of(grant(orgA, AppFeature.SHOP_POS, LocalDateTime.now().minusHours(1))));
            assertThatThrownBy(() -> service.requireOrgFeature(orgA, AppFeature.SHOP_POS))
                    .hasMessageContaining("FEATURE_LOCKED");
        }

        @Test
        void passesWhenGranted() {
            when(featureRepo.findByOrgAndFeature(orgA, AppFeature.SHOP_POS))
                    .thenReturn(Optional.of(grant(orgA, AppFeature.SHOP_POS, null)));
            service.requireOrgFeature(orgA, AppFeature.SHOP_POS); // no exception
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private Organization org(Long id, String slug) {
        Organization o = new Organization();
        o.setId(id);
        o.setSlug(slug);
        o.setName(slug);
        return o;
    }

    private OrgMembership membership(Organization o) {
        OrgMembership m = new OrgMembership();
        m.setOrg(o);
        m.setUser(user);
        m.setInviteEmail(user.getEmail());
        m.setRole(OrgRole.STAFF);
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }

    private OrgFeature grant(Organization o, AppFeature f, LocalDateTime expiresAt) {
        OrgFeature g = new OrgFeature();
        g.setOrg(o);
        g.setFeature(f);
        g.setGrantedAt(LocalDateTime.now().minusDays(2));
        g.setExpiresAt(expiresAt);
        return g;
    }
}
