package xyz.zcraft.osurenderer.console;

import java.util.ArrayList;
import java.util.List;

final class ConsoleInputParser {
    private ConsoleInputParser() {
    }

    static ParsedInput parse(String input) {
        String raw = input == null ? "" : input;
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) index++;
            if (index == raw.length()) break;
            int start = index;
            StringBuilder value = new StringBuilder();
            char quote = 0;
            while (index < raw.length()) {
                char current = raw.charAt(index);
                if (quote != 0) {
                    if (current == quote) {
                        quote = 0;
                        index++;
                    } else if (current == '\\' && index + 1 < raw.length()) {
                        value.append(raw.charAt(index + 1));
                        index += 2;
                    } else {
                        value.append(current);
                        index++;
                    }
                } else if (current == '\'' || current == '"') {
                    quote = current;
                    index++;
                } else if (Character.isWhitespace(current)) {
                    break;
                } else if (current == '\\' && index + 1 < raw.length()) {
                    value.append(raw.charAt(index + 1));
                    index += 2;
                } else {
                    value.append(current);
                    index++;
                }
            }
            if (quote != 0) throw new IllegalArgumentException("Unclosed quote in console command");
            tokens.add(new Token(value.toString(), start));
        }
        return new ParsedInput(raw, List.copyOf(tokens));
    }

    record ParsedInput(String raw, List<Token> tokens) {
        int size() { return tokens.size(); }
        String value(int index) { return tokens.get(index).value(); }
    }

    record Token(String value, int start) {
    }
}
