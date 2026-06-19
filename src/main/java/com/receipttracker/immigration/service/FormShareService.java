package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.CreateFormShareRequest;
import com.receipttracker.immigration.dto.FormShareDTO;
import com.receipttracker.immigration.dto.FormShareViewDTO;
import com.receipttracker.immigration.model.FormShare;
import com.receipttracker.immigration.model.GrantScope;
import com.receipttracker.immigration.repository.FormInstanceRepository;
import com.receipttracker.immigration.repository.FormShareRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class FormShareService {

    private static final Logger log = LoggerFactory.getLogger(FormShareService.class);

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Autowired private FormShareRepository formShareRepo;
    @Autowired private FormInstanceRepository formInstanceRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private FormInstanceService formInstanceService;
    @Autowired private UserRepository userRepo;
    @Autowired private EmailService emailService;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional
    public FormShareDTO createShare(Long caseId, Long formId, CreateFormShareRequest req) {
        log.info(">>> createShare() caseId={} formId={}", caseId, formId);
        User caller = currentUser();
        permissionService.requireAccess(caller, caseId, GrantScope.WRITE_FORMS);

        var form = formInstanceRepo.findById(formId)
                .orElseThrow(() -> new RuntimeException("Form not found: " + formId));
        if (!form.getImmigrationCase().getId().equals(caseId)) {
            throw new RuntimeException("Form does not belong to case " + caseId);
        }

        int expiryDays = (req.expiryDays() > 0 && req.expiryDays() <= 30) ? req.expiryDays() : 7;
        String recipientType = req.recipientType() != null ? req.recipientType().toUpperCase() : "BENEFICIARY";
        if (!recipientType.equals("EMPLOYER") && !recipientType.equals("BENEFICIARY")) {
            throw new RuntimeException("recipientType must be EMPLOYER or BENEFICIARY");
        }

        FormShare share = new FormShare();
        share.setFormInstanceId(formId);
        share.setRecipientEmail(req.recipientEmail().toLowerCase().trim());
        share.setSharedByUserId(caller.getId());
        share.setRecipientType(recipientType);
        share.setExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        FormShare saved = formShareRepo.save(share);

        String shareUrl = frontendUrl + "/immigration/forms/shared/" + saved.getToken();
        try {
            emailService.sendFormShareEmail(
                    saved.getRecipientEmail(),
                    caller.getName() != null ? caller.getName() : caller.getEmail(),
                    form.getFormType().displayName,
                    shareUrl,
                    expiryDays
            );
        } catch (Exception e) {
            log.warn("FormShare email failed to={}: {}", saved.getRecipientEmail(), e.getMessage());
        }

        log.info("<<< createShare() shareId={}", saved.getId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public FormShareViewDTO getByToken(String token) {
        log.info(">>> getByToken()");
        FormShare share = formShareRepo.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired share link"));
        if (share.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This share link has expired");
        }
        var form = formInstanceRepo.findById(share.getFormInstanceId())
                .orElseThrow(() -> new RuntimeException("Form not found"));
        return new FormShareViewDTO(share.getId(), share.getRecipientType(), share.getExpiresAt(),
                formInstanceService.toDTO(form));
    }

    private FormShareDTO toDTO(FormShare s) {
        return new FormShareDTO(s.getId(), s.getFormInstanceId(), s.getToken(),
                s.getRecipientEmail(), s.getRecipientType(), s.getExpiresAt(), s.getCreatedAt());
    }
}
