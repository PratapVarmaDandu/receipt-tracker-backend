package com.receipttracker.immigration.controller;

import com.receipttracker.immigration.service.CaseExportService;
import com.receipttracker.immigration.service.CaseReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/immigration")
public class CaseReportController {

    private static final Logger log = LoggerFactory.getLogger(CaseReportController.class);

    @Autowired private CaseReportService reportService;
    @Autowired private CaseExportService exportService;

    /** Case summary PDF (READ_CASE). */
    @GetMapping("/cases/{id}/report")
    public ResponseEntity<?> getCaseReport(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/report", id);
        try {
            byte[] pdf = reportService.generateCaseReport(id);
            return pdfResponse(pdf, "case-" + id + "-report.pdf");
        } catch (RuntimeException e) {
            return denied(e);
        } catch (Exception e) {
            log.error("!!! generateCaseReport: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Timeline-only PDF for the beneficiary (READ_CASE). */
    @GetMapping("/cases/{id}/timeline/export")
    public ResponseEntity<?> getTimelineExport(@PathVariable Long id) {
        log.info("GET /api/immigration/cases/{}/timeline/export", id);
        try {
            byte[] pdf = reportService.generateTimelinePdf(id);
            return pdfResponse(pdf, "case-" + id + "-timeline.pdf");
        } catch (RuntimeException e) {
            return denied(e);
        } catch (Exception e) {
            log.error("!!! generateTimelinePdf: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** CSV export of all cases for an org (ATTORNEY or OWNER). */
    @GetMapping("/orgs/{orgId}/cases/export")
    public ResponseEntity<?> exportOrgCases(@PathVariable Long orgId) {
        log.info("GET /api/immigration/orgs/{}/cases/export", orgId);
        try {
            String csv = exportService.exportOrgCases(orgId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("org-" + orgId + "-cases.csv").build().toString())
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv.getBytes());
        } catch (RuntimeException e) {
            return denied(e);
        }
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private ResponseEntity<?> denied(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
        }
        log.error("!!! {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
