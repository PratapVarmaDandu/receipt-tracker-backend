package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.BeneficiaryDTO;
import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.repository.BeneficiaryRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BeneficiaryService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryService.class);

    @Autowired private BeneficiaryRepository beneficiaryRepo;
    @Autowired private UserRepository userRepo;

    // ── User resolution ──────────────────────────────────────────────────────

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BeneficiaryDTO getCurrent() {
        log.info(">>> getCurrent()");
        User user = currentUser();
        Beneficiary b = beneficiaryRepo.findByUser(user)
                .orElseThrow(() -> new RuntimeException("No beneficiary profile for current user"));
        return toDTO(b);
    }

    @Transactional
    public BeneficiaryDTO getOrCreateForCurrentUser() {
        log.info(">>> getOrCreateForCurrentUser()");
        User user = currentUser();
        Beneficiary b = beneficiaryRepo.findByUser(user).orElseGet(() -> {
            log.info("TRANSACTION: creating Beneficiary for user={}", user.getId());
            Beneficiary newB = new Beneficiary();
            newB.setUser(user);
            Beneficiary saved = beneficiaryRepo.save(newB);
            log.info("TRANSACTION: Beneficiary created id={}", saved.getId());
            return saved;
        });
        return toDTO(b);
    }

    @Transactional(readOnly = true)
    public boolean currentUserIsBeneficiary() {
        User user = currentUser();
        return beneficiaryRepo.existsByUser(user);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    BeneficiaryDTO toDTO(Beneficiary b) {
        return new BeneficiaryDTO(
                b.getId(),
                b.getUser().getId(),
                b.getUser().getName(),
                b.getUser().getEmail(),
                b.getCreatedAt(),
                false  // hasProfile populated in Slice 3 when CanonicalProfile exists
        );
    }
}
