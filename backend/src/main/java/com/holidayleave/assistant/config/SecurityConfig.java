package com.holidayleave.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 * Authentication is handled manually via custom login controller with bcrypt check.
 * Spring Security's form login is disabled; we manage sessions ourselves.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for API endpoints (SPA uses JSON, not form POSTs for API calls)
            .csrf(AbstractHttpConfigurer::disable)
            // Allow all requests — authentication is enforced by LoginRequiredInterceptor
            .authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
            // Disable Spring Security's default form login
            .formLogin(AbstractHttpConfigurer::disable)
            // Disable HTTP Basic
            .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
