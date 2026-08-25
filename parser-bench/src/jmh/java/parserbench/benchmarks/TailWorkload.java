package parserbench.benchmarks;

/** Programs and edits for the tail benchmark. */
final class TailWorkload {

    record EditCase(
            String newText,
            int delta,
            int editLineStartOld,
            int nextLineStartOld) {}

    private static final String OPERAND_LINE = "  + x";

    private TailWorkload() {}

    static String program(int terms) {
        StringBuilder text = new StringBuilder("fun f(x: int): int =\n    x\n");
        for (int i = 1; i < terms; i++) {
            text.append(OPERAND_LINE).append('\n');
        }
        return text.toString();
    }

    static EditCase edit(String text, TailBenchmark.EditKind kind) {
        int start = text.indexOf(OPERAND_LINE);
        String replacement = switch (kind) {
            case VALID -> "  + 1";
            case ERROR -> "  + end";
        };
        int end = start + OPERAND_LINE.length();
        int nextLineStart = text.indexOf('\n', end) + 1;
        String newText = text.substring(0, start) + replacement + text.substring(end);
        return new EditCase(newText, replacement.length() - OPERAND_LINE.length(),
                text.lastIndexOf('\n', start - 1) + 1, nextLineStart);
    }
}
