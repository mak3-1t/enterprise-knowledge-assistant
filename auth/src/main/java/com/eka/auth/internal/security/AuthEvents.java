package com.eka.auth.internal.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AuthEvents {
    private static final Logger LOG = LoggerFactory.getLogger(AuthEvents.class);
    private final ObjectProvider<MeterRegistry> registries;

    public AuthEvents(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    public void record(Outcome outcome) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        LOG.info("auth_outcome={}", label);
        registries.ifAvailable(
                registry -> registry.counter("eka.auth.events", "outcome", label).increment());
    }

    public enum Outcome {
        LOGIN_SUCCEEDED,
        LOGIN_DENIED,
        UNAUTHORIZED,
        FORBIDDEN
    }
}
