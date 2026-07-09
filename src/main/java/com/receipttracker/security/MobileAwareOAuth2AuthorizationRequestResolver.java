package com.receipttracker.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Wraps the default resolver to stash a one-shot "mobile" flag in the HttpSession
 * when the Capacitor app kicks off login with ?mobile=true. The session survives the
 * full redirect round-trip to Google and back, so OAuth2SuccessHandler can read the
 * flag afterwards to decide whether to redirect to the web dashboard or the app's
 * custom URL scheme. Web login never sends the param, so its behavior is unchanged.
 */
public class MobileAwareOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public MobileAwareOAuth2AuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        stashMobileFlag(request);
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        stashMobileFlag(request);
        return delegate.resolve(request, clientRegistrationId);
    }

    private void stashMobileFlag(HttpServletRequest request) {
        if ("true".equals(request.getParameter("mobile"))) {
            request.getSession().setAttribute(MobileOAuthFlag.SESSION_KEY, true);
        }
    }
}
