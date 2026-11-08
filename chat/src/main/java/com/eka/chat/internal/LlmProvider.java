package com.eka.chat.internal;

@FunctionalInterface
interface LlmProvider {
    String answer(String instructions, String input);
}
