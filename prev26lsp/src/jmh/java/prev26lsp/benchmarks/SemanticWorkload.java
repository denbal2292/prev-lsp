package prev26lsp.benchmarks;

/** Generated PREV programs and controlled single-edit semantic workloads. */
public final class SemanticWorkload {

    private static final int TYPES = 3;

    /** Two declaration-name edits and two named-type edits. */
    public enum EditKind {
        RENAME_LOCAL,
        RENAME_GLOBAL,
        CHANGE_TYPE_NARROW,
        CHANGE_TYPE_WIDE;

        public String label() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    public record Program(String text, int functions, int totalFunctions, int chars) {}
    public record EditCase(
            String newText,
            int start,
            int oldEnd,
            int delta,
            int editLineStartOld,
            int nextLineStartOld,
            EditKind kind) {}

    private SemanticWorkload() {}

    public static Program generate(int functions) {
        if (functions < 1) {
            throw new IllegalArgumentException("functions must be positive");
        }

        StringBuilder out = new StringBuilder();
        out.append("typ T0 = int\n");
        for (int i = 1; i < TYPES; i++) {
            out.append("typ T").append(i).append(" = T").append(i - 1).append('\n');
        }
        for (int i = 0; i < functions; i++) out.append(function(i));
        out.append("typ TN = int\n");
        out.append("fun narrow ( p : TN ) : int =\n    p + shared\n");
        out.append("var shared : int\n");

        String text = out.toString();
        checkProportional(text, functions);
        return new Program(text, functions, functions + 1, text.length());
    }

    private static String function(int i) {
        String body = (i == 0) ? "p" : ("f" + (i - 1) + " ( p )");
        return "fun f" + i + " ( p : T" + (i % TYPES) + " ) : int =\n"
                + "    let\n"
                + "        var loc : int\n"
                + "    in\n"
                + "        loc = 1,\n"
                + "        loc + " + body + " + shared\n"
                + "    end\n";
    }

    private static void checkProportional(String text, int functions) {
        int sharedUses = count(text, "+ shared");
        int chainedParams = count(text, " ( p : T");
        if (sharedUses != functions + 1 || chainedParams != functions + 1) {
            throw new IllegalStateException(("corpus is no longer proportional at %d functions: "
                    + "%d uses of shared, %d chained parameters, expected %d of each")
                    .formatted(functions, sharedUses, chainedParams, functions + 1));
        }
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) total++;
        return total;
    }

    public static EditCase edit(Program program, EditKind kind) {
        String text = program.text();
        int target = program.functions() / 2;
        Splice splice = switch (kind) {
            case RENAME_LOCAL -> splice(text, nth(text, "        var loc : int", target),
                    "        var loc : int", "        var lcl : int");
            case RENAME_GLOBAL -> splice(text, text.lastIndexOf("var shared : int"),
                    "var shared : int", "var total : int");
            case CHANGE_TYPE_NARROW -> splice(text, text.indexOf("typ TN = int"),
                    "typ TN = int", "typ TN = bool");
            case CHANGE_TYPE_WIDE -> splice(text, text.indexOf("typ T0 = int"),
                    "typ T0 = int", "typ T0 = bool");
        };

        int lineStart = text.lastIndexOf('\n', Math.max(0, splice.start() - 1)) + 1;
        int newline = text.indexOf('\n', splice.oldEnd());
        int nextLineStart = (newline < 0) ? text.length() : newline + 1;
        return new EditCase(splice.newText(), splice.start(), splice.oldEnd(), splice.delta(),
                lineStart, nextLineStart, kind);
    }

    private record Splice(String newText, int start, int oldEnd, int delta) {}

    private static int nth(String text, String needle, int n) {
        int at = text.indexOf(needle);
        for (int i = 0; i < n && at >= 0; i++) {
            int next = text.indexOf(needle, at + 1);
            if (next < 0) break;
            at = next;
        }
        return at;
    }

    private static Splice splice(String text, int start, String oldPart, String newPart) {
        if (start < 0) throw new IllegalStateException("anchor not found: " + oldPart);
        int oldEnd = start + oldPart.length();
        String next = text.substring(0, start) + newPart + text.substring(oldEnd);
        return new Splice(next, start, oldEnd, newPart.length() - oldPart.length());
    }
}
