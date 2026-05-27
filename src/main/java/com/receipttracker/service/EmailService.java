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
