package com.eka.auth.internal.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpSecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, AccountJwtConverter converter, AuthEvents events) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(HttpMethod.POST, "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/tenant/members", "/api/tenant/members/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/actuator/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) -> {
                                                    events.record(AuthEvents.Outcome.UNAUTHORIZED);
                                                    response.setStatus(401);
                                                    response.setHeader(
                                                            "WWW-Authenticate", "Bearer");
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"unauthorized\"}");
                                                })
                                        .accessDeniedHandler(
                                                (request, response, exception) -> {
                                                    events.record(AuthEvents.Outcome.FORBIDDEN);
                                                    response.setStatus(403);
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"forbidden\"}");
                                                }))
                .oauth2ResourceServer(
                        resource ->
                                resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(
                                                (request, response, exception) -> {
                                                    events.record(AuthEvents.Outcome.UNAUTHORIZED);
                                                    response.setStatus(401);
                                                    response.setHeader(
                                                            "WWW-Authenticate", "Bearer");
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"unauthorized\"}");
                                                }))
                .build();
    }
}
