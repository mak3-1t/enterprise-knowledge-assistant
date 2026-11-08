package com.eka.chat.internal;

final class ChatUnavailable extends RuntimeException {
    ChatUnavailable() {
        super("CHAT_UNAVAILABLE");
    }
}
