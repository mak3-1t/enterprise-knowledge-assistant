package com.eka.auth.internal.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthSettings.class)
public class AuthConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    RSAKey signingKey(AuthSettings settings) throws IOException {
        try (var privateStream = settings.privateKey().getInputStream();
                var publicStream = settings.publicKey().getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateStream);
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicStream);
            if (privateKey == null
                    || publicKey == null
                    || publicKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException(
                        "Auth requires a matching RSA key pair of at least 2048 bits");
            }
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("eka-local").build();
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder((selector, context) -> selector.select(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, AuthSettings settings, Clock clock) throws Exception {
        var decoder =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamps,
                        new JwtIssuerValidator(settings.issuer()),
                        requiredClaims(settings, clock)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> requiredClaims(AuthSettings settings, Clock clock) {
        return jwt -> {
            try {
                if (!UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                        || !UUID.fromString(jwt.getClaimAsString("tenant_id"))
                                .toString()
                                .equals(jwt.getClaimAsString("tenant_id"))
                        || !jwt.getAudience().contains(settings.audience())
                        || jwt.getIssuedAt() == null
                        || jwt.getExpiresAt() == null
                        || !clock.instant().isBefore(jwt.getExpiresAt())
                        || jwt.getIssuedAt().isAfter(clock.instant())
                        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(900))) {
                    return invalidClaims();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException | NullPointerException ex) {
                return invalidClaims();
            }
        };
    }

    private static OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Invalid authentication claims", null));
    }
}
