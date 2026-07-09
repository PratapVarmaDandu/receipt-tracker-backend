package com.receipttracker.security;

/**
 * Shared HttpSession attribute key for the one-shot "this OAuth login started from
 * the Capacitor app" flag. Set by {@link MobileAwareOAuth2AuthorizationRequestResolver}
 * when the initial /oauth2/authorization/google request carries ?mobile=true, and
 * consumed by {@link OAuth2SuccessHandler} to redirect back to the app's custom URL
 * scheme instead of the web dashboard. Same session-stash pattern as {@link NewSignupFlag}.
 */
public final class MobileOAuthFlag {
    public static final String SESSION_KEY = "MOBILE_OAUTH";
    private MobileOAuthFlag() {}
}
