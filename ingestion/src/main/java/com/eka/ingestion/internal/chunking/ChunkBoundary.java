package com.eka.ingestion.internal.chunking;

@FunctionalInterface
interface ChunkBoundary {
    int end(String text, int minimum, int maximum);

    static int recursive(String text, int minimum, int maximum) {
        int paragraph = -1;
        int line = -1;
        int sentence = -1;
        int space = -1;
        for (int index = maximum; index >= minimum; ) {
            int before = text.codePointBefore(index);
            if (before == '\n') {
                if (line < 0) {
                    line = index;
                }
                if (paragraph < 0 && index >= 2 && text.charAt(index - 2) == '\n') {
                    paragraph = index;
                }
            }
            if (sentence < 0
                    && (before == '.'
                            || before == '!'
                            || before == '?'
                            || before == '。'
                            || before == '！'
                            || before == '？')
                    && (index == text.length()
                            || Character.isWhitespace(text.codePointAt(index)))) {
                sentence = index;
            }
            if (space < 0 && Character.isWhitespace(before)) {
                space = index;
            }
            index -= Character.charCount(before);
        }
        if (paragraph >= 0) {
            return paragraph;
        }
        if (line >= 0) {
            return line;
        }
        if (sentence >= 0) {
            return sentence;
        }
        return space >= 0 ? space : maximum;
    }
}
