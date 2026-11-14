package com.eka.agent.internal;

public final class AgentUnavailable extends RuntimeException {
    public AgentUnavailable() {
        super("AGENT_UNAVAILABLE");
    }
}
