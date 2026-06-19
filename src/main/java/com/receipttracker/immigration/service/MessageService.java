package com.receipttracker.immigration.service;

import com.receipttracker.immigration.dto.MessageDTO;
import com.receipttracker.immigration.model.*;
import com.receipttracker.immigration.repository.GrantRepository;
import com.receipttracker.immigration.repository.ImmigrationCaseRepository;
import com.receipttracker.immigration.repository.MessageRepository;
import com.receipttracker.immigration.repository.MessageThreadRepository;
import com.receipttracker.immigration.repository.MessageThreadReadRepository;
import com.receipttracker.immigration.repository.ImmOrgMemberRepository;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    @Autowired private MessageThreadRepository threadRepo;
    @Autowired private MessageThreadReadRepository threadReadRepo;
    @Autowired private MessageRepository messageRepo;
    @Autowired private GrantRepository grantRepo;
    @Autowired private ImmigrationCaseRepository caseRepo;
    @Autowired private ImmOrgMemberRepository immOrgMemberRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private UserRepository userRepo;

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2User principal = (OAuth2User) auth.getPrincipal();
        String googleId = principal.getAttribute("sub");
        return userRepo.findByGoogleId(googleId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @Transactional(readOnly = true)
    public List<MessageDTO> getMessages(Long caseId, String channelStr) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.MESSAGING);

        MessageChannel channel = parseChannel(channelStr);
        requireChannelAccess(user, caseId, channel);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        MessageThread thread = threadRepo.findByImmigrationCaseAndChannel(c, channel)
                .orElse(null);
        if (thread == null) return List.of();

        return messageRepo.findByThreadOrderByCreatedAtAsc(thread)
                .stream().map(m -> toDTO(m, channel)).toList();
    }

    @Transactional
    public MessageDTO sendMessage(Long caseId, String channelStr, String content) {
        if (content == null || content.isBlank()) throw new RuntimeException("Message content is required");

        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.MESSAGING);

        MessageChannel channel = parseChannel(channelStr);
        requireChannelAccess(user, caseId, channel);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        // Get or create the thread for this channel
        MessageThread thread = threadRepo.findByImmigrationCaseAndChannel(c, channel)
                .orElseGet(() -> {
                    MessageThread t = new MessageThread();
                    t.setImmigrationCase(c);
                    t.setChannel(channel);
                    return threadRepo.save(t);
                });

        Message msg = new Message();
        msg.setThread(thread);
        msg.setAuthor(user);
        msg.setContent(content.trim());

        return toDTO(messageRepo.save(msg), channel);
    }

    @Transactional
    public void markRead(Long caseId, String channelStr) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.MESSAGING);

        MessageChannel channel = parseChannel(channelStr);
        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        MessageThread thread = threadRepo.findByImmigrationCaseAndChannel(c, channel).orElse(null);
        if (thread == null) return;

        MessageThreadRead read = threadReadRepo.findByUserIdAndThreadId(user.getId(), thread.getId())
                .orElseGet(() -> {
                    MessageThreadRead r = new MessageThreadRead();
                    r.setUserId(user.getId());
                    r.setThreadId(thread.getId());
                    return r;
                });
        read.setLastReadAt(LocalDateTime.now());
        threadReadRepo.save(read);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getUnreadCounts(Long caseId) {
        User user = currentUser();
        permissionService.requireAccess(user, caseId, GrantScope.MESSAGING);

        ImmigrationCase c = caseRepo.findById(caseId)
                .orElseThrow(() -> new RuntimeException("Case not found: " + caseId));

        Map<String, Long> counts = new HashMap<>();
        for (MessageChannel channel : MessageChannel.values()) {
            MessageThread thread = threadRepo.findByImmigrationCaseAndChannel(c, channel).orElse(null);
            if (thread == null) { counts.put(channel.name(), 0L); continue; }
            MessageThreadRead read = threadReadRepo.findByUserIdAndThreadId(user.getId(), thread.getId()).orElse(null);
            long unread;
            if (read == null) {
                unread = messageRepo.findByThreadOrderByCreatedAtAsc(thread).size();
            } else {
                LocalDateTime lastRead = read.getLastReadAt();
                unread = messageRepo.findByThreadOrderByCreatedAtAsc(thread).stream()
                        .filter(m -> m.getCreatedAt().isAfter(lastRead))
                        .count();
            }
            counts.put(channel.name(), unread);
        }
        return counts;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void requireChannelAccess(User user, Long caseId, MessageChannel channel) {
        if (channel == MessageChannel.SHARED) return; // READ_CASE already checked

        Set<CaseRelationship> relationships = getUserRelationships(user, caseId);

        boolean allowed = switch (channel) {
            case ATTORNEY_BENEFICIARY ->
                relationships.contains(CaseRelationship.ATTORNEY) ||
                relationships.contains(CaseRelationship.PARALEGAL) ||
                relationships.contains(CaseRelationship.BENEFICIARY);
            case ATTORNEY_EMPLOYER ->
                relationships.contains(CaseRelationship.ATTORNEY) ||
                relationships.contains(CaseRelationship.PARALEGAL) ||
                relationships.contains(CaseRelationship.HR_ADMIN);
            case ATTORNEY_INTERNAL ->
                // Law-firm-internal channel: attorney + paralegal only; never beneficiary or HR_ADMIN
                relationships.contains(CaseRelationship.ATTORNEY) ||
                relationships.contains(CaseRelationship.PARALEGAL);
            case SHARED -> true;
        };

        if (!allowed) throw new RuntimeException(
            "Access denied to channel " + channel + " — insufficient relationship");
    }

    private Set<CaseRelationship> getUserRelationships(User user, Long caseId) {
        List<Long> orgIds = immOrgMemberRepo.findByUserIdAndStatus(user.getId(), ImmOrgMemberStatus.ACTIVE)
                .stream().map(ImmOrgMember::getImmOrgId).toList();
        List<Long> orgIdsWithFallback = orgIds.isEmpty() ? List.of(-1L) : orgIds;

        ImmigrationCase c = caseRepo.findById(caseId).orElseThrow();
        return grantRepo.findByImmigrationCaseAndRevokedAtIsNull(c).stream()
                .filter(g -> g.getSubjectUser() != null && g.getSubjectUser().getId().equals(user.getId())
                        || g.getSubjectImmOrgId() != null && orgIdsWithFallback.contains(g.getSubjectImmOrgId()))
                .map(Grant::getRelationship)
                .collect(Collectors.toSet());
    }

    private MessageChannel parseChannel(String s) {
        try { return MessageChannel.valueOf(s); }
        catch (IllegalArgumentException e) { throw new RuntimeException("Unknown channel: " + s); }
    }

    private MessageDTO toDTO(Message m, MessageChannel channel) {
        return new MessageDTO(m.getId(), m.getThread().getId(), channel.name(),
                m.getAuthor().getId(), m.getAuthor().getName(),
                m.getContent(), m.getCreatedAt());
    }
}
