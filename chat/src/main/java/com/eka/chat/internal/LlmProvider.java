package com.eka.chat.internal;

import reactor.core.publisher.Flux;

@FunctionalInterface
interface LlmProvider {
    String answer(String instructions, String input);

    default Flux<String> stream(String instructions, String input) {
        return Flux.error(new ChatUnavailable());
    }
}
