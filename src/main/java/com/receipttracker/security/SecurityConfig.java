package com.receipttracker.security;

import com.receipttracker.config.LocalDevSecurityFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CustomOAuth2UserService oAuth2UserService;

    @Autowired
    private OAuth2SuccessHandler successHandler;

    @Autowired
    private Environment environment;

    // Only present in local/local-mysql profiles
    @Autowired(required = false)
    private LocalDevSecurityFilter localDevSecurityFilter;

    // Injected from env var FRONTEND_URL — defaults to localhost for local dev
    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isLocalProfile = activeProfiles.contains("local") || activeProfiles.contains("local-mysql");

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable());

        // Configure authorization based on environment
        if (isLocalProfile) {
            // LOCAL DEV: Allow all API access without authentication
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**", "/oauth2/**", "/login/**", "/h2-console/**", "/error").permitAll()
                .anyRequest().permitAll()
            )
            .headers(headers -> headers.frameOptions(frame -> frame.disable())); // Allow H2 console iframe

            // Run AFTER SecurityContextHolderFilter so the session restore doesn't overwrite us
            if (localDevSecurityFilter != null) {
                http.addFilterAfter(localDevSecurityFilter, SecurityContextHolderFilter.class);
            }
        } else {
            // PRODUCTION/TEST: Require authentication for API endpoints
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/oauth2/**", "/login/**", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/shares/token/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/groups/join/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/documents/shared/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/vehicles/access/join/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/org/join/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/shop/public/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            );
        }

        http
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(ui -> ui.userService(oAuth2UserService))
                .successHandler(successHandler)
                .failureUrl(frontendUrl + "/login?error=true")
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setStatus(HttpServletResponse.SC_OK);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Logged out\"}");
                })
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"authenticated\":false}");
                })
            );

        return http.build();
    }

    // Prevent Spring Boot from auto-registering LocalDevSecurityFilter as a raw servlet filter.
    // It is registered inside the security filter chain above instead.
    @Bean
    @ConditionalOnBean(LocalDevSecurityFilter.class)
    public FilterRegistrationBean<LocalDevSecurityFilter> localDevFilterRegistration(LocalDevSecurityFilter filter) {
        FilterRegistrationBean<LocalDevSecurityFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Allow both local dev and the production frontend URL
        config.setAllowedOrigins(List.of("http://localhost:4200", frontendUrl));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
