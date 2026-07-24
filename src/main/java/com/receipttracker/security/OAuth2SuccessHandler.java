package com.receipttracker.security;

import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import com.receipttracker.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Value("${app.mobile.redirect-url:receipttracker://auth-callback}")
    private String mobileRedirectUrl;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String googleId = null;
        if (authentication.getPrincipal() instanceof OAuth2User principal) {
            Boolean isNewSignup = principal.getAttribute("isNewSignup");
            if (Boolean.TRUE.equals(isNewSignup)) {
                request.getSession().setAttribute(NewSignupFlag.SESSION_KEY, true);
            }
            googleId = principal.getAttribute("sub");
        }

        if (Boolean.TRUE.equals(request.getSession().getAttribute(MobileOAuthFlag.SESSION_KEY))) {
            request.getSession().removeAttribute(MobileOAuthFlag.SESSION_KEY);
            String sessionId = request.getSession().getId();
            
            String refreshToken = "";
            if (googleId != null) {
                Optional<User> userOpt = userRepository.findByGoogleId(googleId);
                if (userOpt.isPresent()) {
                    refreshToken = refreshTokenService.createToken(userOpt.get());
                }
            }
            
            String redirectUrl = mobileRedirectUrl + "?sessionId=" + sessionId + "&refreshToken=" + refreshToken;
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
            return;
        }

        getRedirectStrategy().sendRedirect(request, response, frontendUrl + "/dashboard");
    }
}
