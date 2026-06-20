package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
import com.receipttracker.immigration.repository.ImmOrgRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
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

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a CSV of all cases for a given immigration org (attorney or employer).
 * Requires the caller to be an active ATTORNEY or OWNER member of the org.
 */
@Service
public class CaseExportService {

    private static final Logger log = LoggerFactory.getLogger(CaseExportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgRepository immOrgRepo;
    @Autowired private ImmOrgMemberRepository memberRepo;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public String exportOrgCases(Long orgId) {
        log.info(">>> exportOrgCases() orgId={}", orgId);
        User caller = currentUser();
        requireAttorneyOrOwner(caller, orgId);

        ImmOrg org = immOrgRepo.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Org not found: " + orgId));

        List<ImmigrationCase> cases = org.getOrgType() == ImmOrgType.LAW_FIRM
                ? caseRepo.findByLawFirmImmOrgIdOrderByCreatedAtDesc(orgId)
                : caseRepo.findByEmployerImmOrgIdOrderByCreatedAtDesc(orgId);

        StringBuilder sb = new StringBuilder();
        sb.append("Case Number,Beneficiary Name,Beneficiary Email,Case Type,Status,");
        sb.append("Employer,Law Firm,Attorney,Priority Date,USCIS Receipt,Opened\n");

        for (ImmigrationCase c : cases) {
            String benefName  = benefName(c);
            String benefEmail = benefEmail(c);
            String employer   = orgName(c.getEmployerImmOrgId());
            String lawFirm    = orgName(c.getLawFirmImmOrgId());
            String attorney   = memberEmail(c.getAssignedAttorneyMemberId());
            String priDate    = c.getPriorityDate() != null ? c.getPriorityDate().format(DATE_FMT) : "";
            String receipt    = c.getReceiptNumber() != null ? c.getReceiptNumber() : "";
            String opened     = c.getCreatedAt().toLocalDate().format(DATE_FMT);

            sb.append(csv(c.getCaseNumber())).append(',')
              .append(csv(benefName)).append(',')
              .append(csv(benefEmail)).append(',')
              .append(csv(c.getCaseType().name())).append(',')
              .append(csv(c.getStatus().name())).append(',')
              .append(csv(employer)).append(',')
              .append(csv(lawFirm)).append(',')
              .append(csv(attorney)).append(',')
              .append(csv(priDate)).append(',')
              .append(csv(receipt)).append(',')
              .append(csv(opened)).append('\n');
        }

        log.info("<<< exportOrgCases() orgId={} rows={}", orgId, cases.size());
        return sb.toString();
    }

    private void requireAttorneyOrOwner(User user, Long orgId) {
        boolean ok = memberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().anyMatch(m -> m.getImmOrgId().equals(orgId)
                        && (m.getRole() == ImmOrgMemberRole.ATTORNEY
                                || m.getRole() == ImmOrgMemberRole.OWNER));
        if (!ok) throw new RuntimeException("Access denied: ATTORNEY or OWNER role required for org " + orgId);
    }

    private String benefName(ImmigrationCase c) {
        if (c.getBeneficiary() == null || c.getBeneficiary().getUser() == null) return "";
        return c.getBeneficiary().getUser().getName();
    }

    private String benefEmail(ImmigrationCase c) {
        if (c.getBeneficiary() == null || c.getBeneficiary().getUser() == null) return "";
        return c.getBeneficiary().getUser().getEmail();
    }

    private String orgName(Long orgId) {
        if (orgId == null) return "";
        return immOrgRepo.findById(orgId).map(ImmOrg::getName).orElse("");
    }

    private String memberEmail(Long memberId) {
        if (memberId == null) return "";
        return memberRepo.findById(memberId).map(ImmOrgMember::getEmail).orElse("");
    }

    private String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
