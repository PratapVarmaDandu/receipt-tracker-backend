package com.receipttracker.service;

import com.receipttracker.model.RefreshToken;
import com.receipttracker.model.User;
import com.receipttracker.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final long EXPIRATION_DAYS = 30;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    /** Generates a secure random 256-bit token. */
    public String generateRawToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /** Hashes a raw token string using SHA-256. */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /** Creates and persists a new RefreshToken for a user. */
    @Transactional
    public String createToken(User user) {
        String rawToken = generateRawToken();
        String hashedToken = hashToken(rawToken);

        RefreshToken tokenObj = new RefreshToken();
        tokenObj.setUser(user);
        tokenObj.setTokenHash(hashedToken);
        tokenObj.setExpiryDate(LocalDateTime.now().plusDays(EXPIRATION_DAYS));

        refreshTokenRepository.save(tokenObj);
        log.info("Created new refresh token for user id={}", user.getId());
        return rawToken;
    }

    /**
     * Validates a raw refresh token and rotates it.
     * Returns the rotated new raw token.
     * Implements breach detection if the token has been replayed.
     */
    public static class RotationResult {
        private final String rotatedRawToken;
        private final User user;

        public RotationResult(String rotatedRawToken, User user) {
            this.rotatedRawToken = rotatedRawToken;
            this.user = user;
        }

        public String getRotatedRawToken() { return rotatedRawToken; }
        public User getUser() { return user; }
    }

    /**
     * Validates a raw refresh token and rotates it.
     * Returns a RotationResult holding the rotated new raw token and user details.
     * Implements breach detection if the token has been replayed.
     */
    @Transactional
    public RotationResult validateAndRotate(String rawToken) {
        String hashedToken = hashToken(rawToken);
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(hashedToken);

        if (tokenOpt.isEmpty()) {
            throw new RuntimeException("Invalid refresh token");
        }

        RefreshToken tokenObj = tokenOpt.get();

        // 1. Check expiration
        if (tokenObj.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(tokenObj);
            throw new RuntimeException("Refresh token has expired");
        }

        User user = tokenObj.getUser();

        // 2. BREACH DETECTION: If a refresh token is used more than once, it indicates
        // that a token has been stolen and replayed. Automatically invalidate all sessions.
        if (tokenObj.isUsed()) {
            refreshTokenRepository.deleteByUser(user);
            log.error("SECURITY ALERT: Replay attack detected for user id={}! All active refresh tokens revoked.", user.getId());
            throw new RuntimeException("Suspicious activity detected. All sessions revoked.");
        }

        // 3. Mark current token as used
        tokenObj.setUsed(true);
        refreshTokenRepository.save(tokenObj);

        // 4. Generate and return a rotated new token
        String nextRawToken = createToken(user);
        return new RotationResult(nextRawToken, user);
    }

    /** Revokes all tokens for a user (e.g. on explicit logout). */
    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.deleteByUser(user);
        log.info("Revoked all refresh tokens for user id={}", user.getId());
    }
}
