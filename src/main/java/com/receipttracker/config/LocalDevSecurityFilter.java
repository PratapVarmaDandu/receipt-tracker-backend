package com.receipttracker.config;

import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Auto-authenticates every request as a local dev user when running with
 * spring.profiles.active=local. Removes the need for Google OAuth2 during
 * local development and testing.
 *
 * The dev user (googleId=local-dev-user) is created in the DB on first use.
 */
@Component
@Profile({"local", "local-mysql"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalDevSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalDevSecurityFilter.class);

    private static final String DEV_GOOGLE_ID = "local-dev-user";
    private static final String DEV_EMAIL     = "dev@localhost.local";
    private static final String DEV_NAME      = "Local Dev User";

    @Autowired
    private UserRepository userRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {

            userRepo.findByGoogleId(DEV_GOOGLE_ID).orElseGet(() -> {
                User u = new User();
                u.setGoogleId(DEV_GOOGLE_ID);
                u.setEmail(DEV_EMAIL);
                u.setName(DEV_NAME);
                User saved = userRepo.save(u);
                log.info("Created local dev user id={}", saved.getId());
                return saved;
            });

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub",   DEV_GOOGLE_ID);
            attrs.put("email", DEV_EMAIL);
            attrs.put("name",  DEV_NAME);

            OAuth2User principal = new DefaultOAuth2User(
                    Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                    attrs, "sub");

            OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(
                    principal, principal.getAuthorities(), "google");

            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(req, res);
    }
}
