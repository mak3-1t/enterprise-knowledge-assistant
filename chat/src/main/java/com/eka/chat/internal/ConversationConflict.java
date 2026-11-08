package com.eka.chat.internal;

final class ConversationConflict extends RuntimeException {
    ConversationConflict(String code) {
        super(code);
    }
}
