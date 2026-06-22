package com.receipttracker.immigration.controller;

import com.receipttracker.config.ApiErrors;
import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.service.FilingPackageService;
import com.receipttracker.immigration.service.ImmPdfGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class FilingPackageController {

    @Autowired private FilingPackageService packageService;
    @Autowired private ImmPdfGenerationService pdfGenerationService;

    // ── Case-scoped endpoints ─────────────────────────────────────────────────

    @PostMapping("/api/immigration/cases/{caseId}/packages")
    public ResponseEntity<?> createPackage(
            @PathVariable Long caseId,
            @RequestBody CreatePackageRequest req) {
        try {
            return ResponseEntity.ok(packageService.create(caseId, req));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages")
    public ResponseEntity<?> listPackages(@PathVariable Long caseId) {
        try {
            return ResponseEntity.ok(packageService.listForCase(caseId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}")
    public ResponseEntity<?> getPackage(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        try {
            return ResponseEntity.ok(packageService.getPackage(caseId, packageId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/send-questionnaires")
    public ResponseEntity<?> sendQuestionnaires(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        try {
            return ResponseEntity.ok(packageService.sendQuestionnaires(caseId, packageId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/review-summary")
    public ResponseEntity<?> reviewSummary(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        try {
            return ResponseEntity.ok(packageService.getReviewSummary(caseId, packageId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/approve-answers")
    public ResponseEntity<?> approveAnswers(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        try {
            return ResponseEntity.ok(packageService.approveAnswers(caseId, packageId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @PutMapping("/api/immigration/cases/{caseId}/packages/{packageId}/answers/{answerKey}")
    public ResponseEntity<?> overrideAnswer(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable String answerKey,
            @RequestBody AnswerOverrideRequest req) {
        try {
            return ResponseEntity.ok(packageService.overrideAnswer(caseId, packageId, answerKey, req));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    // ── PDF generation endpoints ──────────────────────────────────────────────

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/generate-pdf")
    public ResponseEntity<?> generatePdf(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @RequestBody(required = false) GeneratePdfRequest req) {
        try {
            boolean override = req != null && req.overridePendingReview();
            return ResponseEntity.ok(pdfGenerationService.generatePacket(caseId, packageId, override));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets")
    public ResponseEntity<?> listPackets(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        try {
            return ResponseEntity.ok(pdfGenerationService.listPackets(caseId, packageId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets/{packetId}/download")
    public ResponseEntity<Resource> downloadPacket(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable Long packetId) {
        return pdfGenerationService.downloadPacket(caseId, packageId, packetId);
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets/{packetId}/approve")
    public ResponseEntity<?> approvePacket(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable Long packetId) {
        try {
            return ResponseEntity.ok(pdfGenerationService.approvePacket(caseId, packageId, packetId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    // ── Public questionnaire endpoints ────────────────────────────────────────

    @GetMapping("/api/immigration/packages/questionnaires/{token}")
    public ResponseEntity<?> getQuestionnaire(@PathVariable String token) {
        try {
            return ResponseEntity.ok(packageService.getPublicQuestionnaire(token));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }

    @PostMapping("/api/immigration/packages/questionnaires/{token}/submit")
    public ResponseEntity<?> submitQuestionnaire(
            @PathVariable String token,
            @RequestBody SubmitQuestionnaireRequest req) {
        try {
            packageService.submitQuestionnaire(token, req);
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", ApiErrors.safeMessage(e)));
        }
    }
}
