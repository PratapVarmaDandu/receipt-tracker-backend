package com.receipttracker.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Value("${app.mobile.redirect-url:receipttracker://auth-callback}")
    private String mobileRedirectUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (authentication.getPrincipal() instanceof OAuth2User principal) {
            Boolean isNewSignup = principal.getAttribute("isNewSignup");
            if (Boolean.TRUE.equals(isNewSignup)) {
                request.getSession().setAttribute(NewSignupFlag.SESSION_KEY, true);
            }
        }

        if (Boolean.TRUE.equals(request.getSession().getAttribute(MobileOAuthFlag.SESSION_KEY))) {
            request.getSession().removeAttribute(MobileOAuthFlag.SESSION_KEY);
            getRedirectStrategy().sendRedirect(request, response, mobileRedirectUrl);
            return;
        }

        getRedirectStrategy().sendRedirect(request, response, frontendUrl + "/dashboard");
    }
}
