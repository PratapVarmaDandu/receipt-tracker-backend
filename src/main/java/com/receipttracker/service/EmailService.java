package com.receipttracker.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public void sendInvite(String to, String storeName, String ownerName, BigDecimal amount, String tokenUrl) {
        String subject = ownerName + " invited you to split an expense";
        String body = "<div style='font-family:sans-serif;max-width:500px;margin:auto'>"
            + "<h2 style='color:#2d6cdf'>Expense Share Invitation</h2>"
            + "<p><strong>" + escapeHtml(ownerName) + "</strong> has invited you to share a "
            + "<strong>" + escapeHtml(storeName) + "</strong> expense.</p>"
            + "<p>Your share: <strong>$" + amount.toPlainString() + "</strong></p>"
            + "<p><a href='" + tokenUrl + "' style='background:#2d6cdf;color:#fff;padding:10px 20px;"
            + "border-radius:6px;text-decoration:none;display:inline-block'>View &amp; Respond</a></p>"
            + "<p style='color:#888;font-size:12px'>If you did not expect this email, you can ignore it.</p>"
            + "</div>";
        send(to, subject, body);
    }

    public void sendChangeRequest(String ownerEmail, String inviteeEmail, BigDecimal counter, String note, String tokenUrl) {
        String subject = inviteeEmail + " requested a change to their share";
        String body = "<div style='font-family:sans-serif;max-width:500px;margin:auto'>"
            + "<h2 style='color:#e67e22'>Change Request Received</h2>"
            + "<p><strong>" + escapeHtml(inviteeEmail) + "</strong> has requested a change to their share.</p>"
            + "<p>Requested amount: <strong>$" + counter.toPlainString() + "</strong></p>"
            + (note != null && !note.isBlank()
                ? "<p>Note: <em>" + escapeHtml(note) + "</em></p>" : "")
            + "<p><a href='" + tokenUrl + "' style='background:#e67e22;color:#fff;padding:10px 20px;"
            + "border-radius:6px;text-decoration:none;display:inline-block'>Review Request</a></p>"
            + "</div>";
        send(ownerEmail, subject, body);
    }

    public void sendInviteeDecision(String ownerEmail, String inviteeEmail, String action, BigDecimal shareAmount, String receiptUrl) {
        boolean accepted = "ACCEPT".equalsIgnoreCase(action);
        String subject = escapeHtml(inviteeEmail) + (accepted ? " accepted" : " denied") + " their expense share";
        String color  = accepted ? "#27ae60" : "#e74c3c";
        String heading = accepted ? "Share Accepted" : "Share Denied";
        String body = "<div style='font-family:sans-serif;max-width:500px;margin:auto'>"
            + "<h2 style='color:" + color + "'>" + heading + "</h2>"
            + "<p><strong>" + escapeHtml(inviteeEmail) + "</strong> has "
            + (accepted ? "accepted" : "denied") + " the expense share"
            + (shareAmount != null ? " of <strong>$" + shareAmount.toPlainString() + "</strong>" : "") + ".</p>"
            + "<p><a href='" + receiptUrl + "' style='background:" + color + ";color:#fff;padding:10px 20px;"
            + "border-radius:6px;text-decoration:none;display:inline-block'>View Receipt</a></p>"
            + "</div>";
        send(ownerEmail, subject, body);
    }

    public void sendOwnerDecision(String inviteeEmail, boolean approved, BigDecimal finalAmount, String note, String tokenUrl) {
        String subject = approved ? "Your change request was approved" : "Your change request was rejected";
        String color = approved ? "#27ae60" : "#e74c3c";
        String heading = approved ? "Change Request Approved" : "Change Request Rejected";
        String body = "<div style='font-family:sans-serif;max-width:500px;margin:auto'>"
            + "<h2 style='color:" + color + "'>" + heading + "</h2>"
            + (approved
                ? "<p>Your change request was approved. Final amount: <strong>$" + finalAmount.toPlainString() + "</strong></p>"
                : "<p>Your change request was not approved.</p>")
            + (note != null && !note.isBlank()
                ? "<p>Message from owner: <em>" + escapeHtml(note) + "</em></p>" : "")
            + "<p><a href='" + tokenUrl + "' style='background:" + color + ";color:#fff;padding:10px 20px;"
            + "border-radius:6px;text-decoration:none;display:inline-block'>View Details</a></p>"
            + "</div>";
        send(inviteeEmail, subject, body);
    }

    public void sendDocumentShare(String to, String recipientName, String senderName,
                                  String purpose, String message, int docCount,
                                  String shareUrl, int expiryDays) {
        String name = recipientName != null && !recipientName.isBlank() ? recipientName : to;
        String purposeLabel = purpose != null && !purpose.isBlank() ? purpose : "Document Share";
        String subject = escapeHtml(senderName) + " shared " + docCount
                + " document" + (docCount == 1 ? "" : "s") + " with you";
        String body = "<div style='font-family:sans-serif;max-width:560px;margin:auto'>"
            + "<div style='background:#4f46e5;padding:24px 28px;border-radius:10px 10px 0 0'>"
            + "<h2 style='color:#fff;margin:0;font-size:1.3rem'>📄 Documents Shared With You</h2>"
            + "</div>"
            + "<div style='background:#fff;border:1px solid #e2e8f0;border-top:none;"
            + "padding:24px 28px;border-radius:0 0 10px 10px'>"
            + "<p>Hi <strong>" + escapeHtml(name) + "</strong>,</p>"
            + "<p><strong>" + escapeHtml(senderName) + "</strong> has shared <strong>"
            + docCount + " document" + (docCount == 1 ? "" : "s") + "</strong> with you"
            + " for: <em>" + escapeHtml(purposeLabel) + "</em>.</p>"
            + (message != null && !message.isBlank()
                ? "<blockquote style='border-left:4px solid #4f46e5;margin:16px 0;"
                + "padding:8px 16px;background:#f8f9ff;border-radius:0 6px 6px 0;color:#374151'>"
                + escapeHtml(message) + "</blockquote>"
                : "")
            + "<p style='text-align:center;margin:28px 0'>"
            + "<a href='" + shareUrl + "' style='background:#4f46e5;color:#fff;padding:12px 28px;"
            + "border-radius:8px;text-decoration:none;font-weight:600;display:inline-block'>"
            + "View &amp; Download Documents</a></p>"
            + "<p style='color:#94a3b8;font-size:12px'>This link expires in " + expiryDays + " day"
            + (expiryDays == 1 ? "" : "s") + ". Do not forward this email.</p>"
            + "</div></div>";
        send(to, subject, body);
    }

    public void sendOrgInvite(String to, String ownerName, String orgName, String role, String inviteUrl) {
        String subject = escapeHtml(ownerName) + " invited you to join " + escapeHtml(orgName);
        String body = "<div style='font-family:sans-serif;max-width:560px;margin:auto'>"
            + "<div style='background:#4f46e5;padding:24px 28px;border-radius:10px 10px 0 0'>"
            + "<h2 style='color:#fff;margin:0;font-size:1.3rem'>You're invited to join an organization</h2>"
            + "</div>"
            + "<div style='background:#fff;border:1px solid #e2e8f0;border-top:none;"
            + "padding:24px 28px;border-radius:0 0 10px 10px'>"
            + "<p><strong>" + escapeHtml(ownerName) + "</strong> has invited you to join "
            + "<strong>" + escapeHtml(orgName) + "</strong> as <strong>" + escapeHtml(role) + "</strong>.</p>"
            + "<p style='text-align:center;margin:28px 0'>"
            + "<a href='" + inviteUrl + "' style='background:#4f46e5;color:#fff;padding:12px 28px;"
            + "border-radius:8px;text-decoration:none;font-weight:600;display:inline-block'>"
            + "Accept Invite</a></p>"
            + "<p style='color:#94a3b8;font-size:12px'>If you did not expect this email, you can ignore it.</p>"
            + "</div></div>";
        send(to, subject, body);
    }

    public void sendVehicleShareInvite(String to, String ownerName, String vehicleName, String inviteUrl) {
        String subject = escapeHtml(ownerName) + " shared their vehicle with you: " + escapeHtml(vehicleName);
        String body = "<div style='font-family:sans-serif;max-width:560px;margin:auto'>"
            + "<div style='background:#d97706;padding:24px 28px;border-radius:10px 10px 0 0'>"
            + "<h2 style='color:#fff;margin:0;font-size:1.3rem'>🚗 Vehicle Shared With You</h2>"
            + "</div>"
            + "<div style='background:#fff;border:1px solid #e2e8f0;border-top:none;"
            + "padding:24px 28px;border-radius:0 0 10px 10px'>"
            + "<p><strong>" + escapeHtml(ownerName) + "</strong> has shared their vehicle "
            + "<strong>" + escapeHtml(vehicleName) + "</strong> with you.</p>"
            + "<p>You'll be able to view all details, add maintenance records, and log fuel fill-ups.</p>"
            + "<p style='text-align:center;margin:28px 0'>"
            + "<a href='" + inviteUrl + "' style='background:#d97706;color:#fff;padding:12px 28px;"
            + "border-radius:8px;text-decoration:none;font-weight:600;display:inline-block'>"
            + "Accept Invite</a></p>"
            + "<p style='color:#94a3b8;font-size:12px'>If you did not expect this email, you can ignore it.</p>"
            + "</div></div>";
        send(to, subject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        if (mailSender == null) {
            log.warn("EMAIL NOT SENT (no SMTP configured): to={} | subject={} | body={}", to, subject, htmlBody);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);
            log.info("Email sent to={} subject={}", to, subject);
        } catch (MailException | MessagingException e) {
            log.warn("Failed to send email to={} subject={}: {}", to, subject, e.getMessage());
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
