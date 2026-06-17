package com.receipttracker.security;

import com.receipttracker.model.User;
import com.receipttracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);

        String googleId = oAuth2User.getAttribute("sub");
        String email    = oAuth2User.getAttribute("email");
        String name     = oAuth2User.getAttribute("name");
        String picture  = oAuth2User.getAttribute("picture");

        // Primary lookup: by Google ID (returning users)
        // Fallback: by email for stub users created when an employer invited a beneficiary
        //   before they had logged in. Stub users have googleId starting with "PENDING_".
        User user = userRepository.findByGoogleId(googleId)
                .or(() -> userRepository.findByEmail(email)
                        .filter(u -> u.getGoogleId() != null && u.getGoogleId().startsWith("PENDING_")))
                .orElse(new User());

        boolean isNew = user.getId() == null;
        boolean wasStub = !isNew && user.getGoogleId() != null && user.getGoogleId().startsWith("PENDING_");

        user.setGoogleId(googleId);
        user.setEmail(email);
        user.setName(name);
        user.setPicture(picture);
        userRepository.save(user);

        if (wasStub) {
            log.info("Merged stub user → real Google account: {} ({})", name, email);
        } else {
            log.info("{} user: {} ({})", isNew ? "Created" : "Returning", name, email);
        }
        return oAuth2User;
    }
}
