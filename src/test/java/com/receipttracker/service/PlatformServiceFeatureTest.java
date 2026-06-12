package com.receipttracker.service;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.OrgFeature;
import com.receipttracker.model.Organization;
import com.receipttracker.model.User;
import com.receipttracker.repository.OrgFeatureRepository;
import com.receipttracker.repository.OrgMembershipRepository;
import com.receipttracker.repository.OrgOrderRepository;
import com.receipttracker.repository.OrganizationRepository;
import com.receipttracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformServiceFeatureTest {

    @Mock private OrganizationRepository orgRepo;
    @Mock private OrgMembershipRepository memberRepo;
    @Mock private OrgOrderRepository orgOrderRepo;
    @Mock private OrgFeatureRepository featureRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks private PlatformService service;

    private User admin;
    private Organization org;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(1L);
        admin.setGoogleId("admin-google-id");
        admin.setEmail("admin@test.com");
        admin.setPlatformAdmin(true);

        org = new Organization();
        org.setId(10L);
        org.setSlug("acme");
        org.setName("Acme");

        mockCurrentUser(admin);
        when(orgRepo.findBySlug("acme")).thenReturn(Optional.of(org));
        when(featureRepo.findByOrg(org)).thenReturn(List.of());
    }

    @Test
    void grantCreatesNewRow() {
        when(featureRepo.findByOrgAndFeature(org, AppFeature.GARAGE)).thenReturn(Optional.empty());

        service.grantFeature("acme", "GARAGE", null);

        ArgumentCaptor<OrgFeature> captor = ArgumentCaptor.forClass(OrgFeature.class);
        verify(featureRepo).save(captor.capture());
        assertThat(captor.getValue().getFeature()).isEqualTo(AppFeature.GARAGE);
        assertThat(captor.getValue().getOrg()).isEqualTo(org);
        assertThat(captor.getValue().getExpiresAt()).isNull();
    }

    @Test
    void grantIsIdempotentAndUpdatesExpiry() {
        OrgFeature existing = new OrgFeature();
        existing.setId(5L);
        existing.setOrg(org);
        existing.setFeature(AppFeature.GARAGE);
        when(featureRepo.findByOrgAndFeature(org, AppFeature.GARAGE)).thenReturn(Optional.of(existing));

        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        service.grantFeature("acme", "garage", expiry);

        ArgumentCaptor<OrgFeature> captor = ArgumentCaptor.forClass(OrgFeature.class);
        verify(featureRepo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(expiry);
    }

    @Test
    void revokeDeletesRow() {
        service.revokeFeature("acme", "DOCUMENT_VAULT");
        verify(featureRepo).deleteByOrgAndFeature(org, AppFeature.DOCUMENT_VAULT);
    }

    @Test
    void unknownFeatureRejected() {
        assertThatThrownBy(() -> service.grantFeature("acme", "NOT_A_FEATURE", null))
                .hasMessageContaining("Unknown feature");
        verify(featureRepo, never()).save(any());
    }

    @Test
    void nonAdminRejected() {
        admin.setPlatformAdmin(false);
        assertThatThrownBy(() -> service.grantFeature("acme", "GARAGE", null))
                .hasMessageContaining("Platform admin access required");
        verify(featureRepo, never()).save(any());
    }

    @Test
    void listFiltersExpiredGrants() {
        OrgFeature live = new OrgFeature();
        live.setOrg(org);
        live.setFeature(AppFeature.GARAGE);
        OrgFeature expired = new OrgFeature();
        expired.setOrg(org);
        expired.setFeature(AppFeature.SHOP_POS);
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(featureRepo.findByOrg(org)).thenReturn(List.of(live, expired));

        assertThat(service.listOrgFeatures("acme")).containsExactly("GARAGE");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** PlatformService resolves the caller by googleId ("sub" attribute). */
    private void mockCurrentUser(User u) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("sub")).thenReturn(u.getGoogleId());
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
        when(userRepo.findByGoogleId(u.getGoogleId())).thenReturn(Optional.of(u));
    }
}
