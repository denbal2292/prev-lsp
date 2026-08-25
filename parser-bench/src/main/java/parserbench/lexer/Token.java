package parserbench.lexer;

import parserbench.parser.Symbol;

public class Token {

    public final Symbol type;
    public final String value;
    public final String errorMessage; // Non-null if this token represents a lexical error
    private int leadingTriviaWidth;
    private int trailingTriviaWidth;

    public Token(Symbol type, String value) {
        this.type = type;
        this.value = value;
        this.leadingTriviaWidth = 0;
        this.trailingTriviaWidth = 0;
        this.errorMessage = null;
    }

    public Token(Symbol type, String value, String errorMessage) {
        this.type = type;
        this.value = value;
        this.errorMessage = errorMessage;
        this.leadingTriviaWidth = 0;
        this.trailingTriviaWidth = 0;
    }

    void addLeadingTriviaWidth(int width) {
        this.leadingTriviaWidth += width;
    }

    void addTrailingTriviaWidth(int width) {
        this.trailingTriviaWidth += width;
    }

    public int getLeadingTriviaWidth() {
        return leadingTriviaWidth;
    }

    public int getTrailingTriviaWidth() {
        return trailingTriviaWidth;
    }

    public int getValueWidth() {
        return value.length();
    }

    public int getWidth() {
        return this.leadingTriviaWidth + this.value.length() + this.trailingTriviaWidth;
    }

    @Override
    public String toString() {
        return String.format("Token{type=%s, value=%s, with=%d}", type, value, getWidth());
    }

}