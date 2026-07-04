package com.receipttracker.security;

/**
 * Shared HttpSession attribute key for the one-shot "this login just created a brand
 * new account" flag. Set by {@link OAuth2SuccessHandler} (read from the enriched
 * OAuth2User attribute {@link CustomOAuth2UserService} adds during loadUser()),
 * surfaced once via {@code GET /api/auth/me}, and consumed by
 * {@code POST /api/referrals/claim} to gate referral rewards to genuine first logins.
 */
public final class NewSignupFlag {
    public static final String SESSION_KEY = "NEW_SIGNUP";
    private NewSignupFlag() {}
}
