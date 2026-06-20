package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.dto.*;
import com.receipttracker.immigration.service.FilingPackageService;
import com.receipttracker.immigration.service.ImmPdfGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class FilingPackageController {

    @Autowired private FilingPackageService packageService;
    @Autowired private ImmPdfGenerationService pdfGenerationService;

    // ── Case-scoped endpoints ─────────────────────────────────────────────────

    @PostMapping("/api/immigration/cases/{caseId}/packages")
    public ResponseEntity<FilingPackageDTO> createPackage(
            @PathVariable Long caseId,
            @RequestBody CreatePackageRequest req) {
        return ResponseEntity.ok(packageService.create(caseId, req));
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages")
    public ResponseEntity<List<FilingPackageDTO>> listPackages(@PathVariable Long caseId) {
        return ResponseEntity.ok(packageService.listForCase(caseId));
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}")
    public ResponseEntity<FilingPackageDTO> getPackage(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        return ResponseEntity.ok(packageService.getPackage(caseId, packageId));
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/send-questionnaires")
    public ResponseEntity<FilingPackageDTO> sendQuestionnaires(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        return ResponseEntity.ok(packageService.sendQuestionnaires(caseId, packageId));
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/review-summary")
    public ResponseEntity<ReviewSummaryDTO> reviewSummary(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        return ResponseEntity.ok(packageService.getReviewSummary(caseId, packageId));
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/approve-answers")
    public ResponseEntity<FilingPackageDTO> approveAnswers(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        return ResponseEntity.ok(packageService.approveAnswers(caseId, packageId));
    }

    @PutMapping("/api/immigration/cases/{caseId}/packages/{packageId}/answers/{answerKey}")
    public ResponseEntity<FilingPackageAnswerDTO> overrideAnswer(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable String answerKey,
            @RequestBody AnswerOverrideRequest req) {
        return ResponseEntity.ok(packageService.overrideAnswer(caseId, packageId, answerKey, req));
    }

    // ── PDF generation endpoints ──────────────────────────────────────────────

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/generate-pdf")
    public ResponseEntity<GeneratedPdfPacketDTO> generatePdf(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @RequestBody(required = false) GeneratePdfRequest req) {
        boolean override = req != null && req.overridePendingReview();
        return ResponseEntity.ok(pdfGenerationService.generatePacket(caseId, packageId, override));
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets")
    public ResponseEntity<List<GeneratedPdfPacketDTO>> listPackets(
            @PathVariable Long caseId,
            @PathVariable Long packageId) {
        return ResponseEntity.ok(pdfGenerationService.listPackets(caseId, packageId));
    }

    @GetMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets/{packetId}/download")
    public ResponseEntity<Resource> downloadPacket(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable Long packetId) {
        return pdfGenerationService.downloadPacket(caseId, packageId, packetId);
    }

    @PostMapping("/api/immigration/cases/{caseId}/packages/{packageId}/packets/{packetId}/approve")
    public ResponseEntity<GeneratedPdfPacketDTO> approvePacket(
            @PathVariable Long caseId,
            @PathVariable Long packageId,
            @PathVariable Long packetId) {
        return ResponseEntity.ok(pdfGenerationService.approvePacket(caseId, packageId, packetId));
    }

    // ── Public questionnaire endpoints ────────────────────────────────────────

    @GetMapping("/api/immigration/packages/questionnaires/{token}")
    public ResponseEntity<QuestionnairePublicDTO> getQuestionnaire(@PathVariable String token) {
        return ResponseEntity.ok(packageService.getPublicQuestionnaire(token));
    }

    @PostMapping("/api/immigration/packages/questionnaires/{token}/submit")
    public ResponseEntity<Void> submitQuestionnaire(
            @PathVariable String token,
            @RequestBody SubmitQuestionnaireRequest req) {
        packageService.submitQuestionnaire(token, req);
        return ResponseEntity.ok().build();
    }
}
