package parserbench.benchmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** PREV programs and mixed edit traces for the position benchmark. */
final class PositionWorkload {

    static final int EDIT_COUNT = 32;

    private static final String TERM_SEPARATOR = " + ";
    private static final int TERM_SEPARATOR_LENGTH = TERM_SEPARATOR.length();
    private static final int REPLACE_TERM_EDITS = 10;
    private static final int INSERT_TERM_EDITS = 6;
    private static final int TYPE_TERM_EDITS = 6;
    private static final int DELETE_TERM_EDITS = 4;
    private static final int INSERT_DEFINITION_EDITS = 3;
    private static final int DELETE_DEFINITION_EDITS = 3;

    record Program(String text, List<Definition> definitions) {}

    record EditCase(
            String newText,
            int delta,
            int editLineStartOld,
            int nextLineStartOld) {}

    private enum EditKind {
        REPLACE_TERM,
        INSERT_TERM,
        DELETE_TERM,
        INSERT_DEFINITION,
        DELETE_DEFINITION,
        TYPE_TERM
    }

    private static final String[] TERMS = {
            "x", "a", "b", "one", "acc", "1", "2", "x * 2", "a + 1"
    };

    /** Typed one character per edit, including temporarily invalid states. */
    private static final String TYPED_TERM = " + (a)";

    private PositionWorkload() {}

    static Program generate(int definitionCount, long seed) {
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder();
        List<Definition> definitions = new ArrayList<>(definitionCount);
        for (int i = 0; i < definitionCount; i++) {
            definitions.add(appendDefinition(text, i, random));
        }
        return new Program(text.toString(), List.copyOf(definitions));
    }

    static List<EditCase> generateTrace(
            Program program,
            PositionBenchmark.Position position,
            long seed) {
        Trace trace = new Trace(program);
        Random random = new Random(seed);
        List<EditCase> edits = new ArrayList<>(EDIT_COUNT);
        for (EditKind kind : editSchedule(seed)) {
            edits.add(trace.apply(kind, position, random));
        }
        return List.copyOf(edits);
    }

    private static Definition appendDefinition(StringBuilder out, int index, Random random) {
        int start = out.length();
        List<String> terms = new ArrayList<>(List.of(randomTerm(random), randomTerm(random)));
        int expressionStart;

        int roll = random.nextInt(100);
        if (roll < 5) {
            out.append("typ T").append(index).append(" = int\n");
            expressionStart = -1;
        } else if (roll < 10) {
            out.append("var v").append(index).append(" : int\n");
            expressionStart = -1;
        } else if (roll < 14) {
            out.append("fun g").append(index).append("(x: int): int =\n")
                    .append("    let\n        var a : int\n    in\n        ");
            expressionStart = appendExpression(out, terms);
            out.append("\n    end\n");
        } else if (roll < 18) {
            out.append("fun h").append(index).append("(a: int, b: int): int =\n")
                    .append("    if a < b then\n        ");
            expressionStart = appendExpression(out, terms);
            out.append("\n    else\n        b\n    end\n");
        } else if (roll < 21) {
            out.append("fun w").append(index).append("(x: int): int =\n")
                    .append("    let\n        var acc : int\n    in\n        acc = 0,\n")
                    .append("        while acc < x do\n            acc = ");
            expressionStart = appendExpression(out, terms);
            out.append("\n        end,\n        acc\n    end\n");
        } else {
            out.append("fun f").append(index).append("(x: int): int =\n    ");
            expressionStart = appendExpression(out, terms);
            out.append('\n');
        }

        return new Definition(start, out.length(), expressionStart, terms);
    }

    private static int appendExpression(StringBuilder out, List<String> terms) {
        int start = out.length();
        out.append(String.join(TERM_SEPARATOR, terms));
        return start;
    }

    private static List<EditKind> editSchedule(long seed) {
        List<EditKind> schedule = new ArrayList<>(EDIT_COUNT);
        add(schedule, EditKind.REPLACE_TERM, REPLACE_TERM_EDITS);
        add(schedule, EditKind.INSERT_TERM, INSERT_TERM_EDITS);
        add(schedule, EditKind.TYPE_TERM, TYPE_TERM_EDITS);
        add(schedule, EditKind.DELETE_TERM, DELETE_TERM_EDITS);
        add(schedule, EditKind.INSERT_DEFINITION, INSERT_DEFINITION_EDITS);
        add(schedule, EditKind.DELETE_DEFINITION, DELETE_DEFINITION_EDITS);
        Collections.shuffle(schedule, new Random(seed));
        return schedule;
    }

    private static void add(List<EditKind> schedule, EditKind kind, int count) {
        for (int i = 0; i < count; i++) schedule.add(kind);
    }

    private static String randomTerm(Random random) {
        return TERMS[random.nextInt(TERMS.length)];
    }

    /** One definition and the additive expression inside it, if it has one. */
    private static final class Definition {
        private int start;
        private int end;
        private int expressionStart;
        private int typedCharacters;
        private final List<String> terms;

        private Definition(int start, int end, int expressionStart, List<String> terms) {
            this.start = start;
            this.end = end;
            this.expressionStart = expressionStart;
            this.terms = terms;
        }

        private Definition(Definition source) {
            this(source.start, source.end, source.expressionStart, new ArrayList<>(source.terms));
            typedCharacters = source.typedCharacters;
        }

        private int termStart(int index) {
            int offset = expressionStart;
            for (int i = 0; i < index; i++) {
                offset += terms.get(i).length() + TERM_SEPARATOR_LENGTH;
            }
            return offset;
        }

        private int expressionEnd() {
            return termStart(terms.size()) - TERM_SEPARATOR_LENGTH + typedCharacters;
        }
    }

    /** Mutable document and anchors used only while generating one trace. */
    private static final class Trace {
        private final List<Definition> definitions = new ArrayList<>();
        private String text;
        private int insertedDefinitions;

        private Trace(Program program) {
            text = program.text();
            for (Definition definition : program.definitions()) {
                definitions.add(new Definition(definition));
            }
        }

        private EditCase apply(
                EditKind kind,
                PositionBenchmark.Position position,
                Random random) {
            Definition target = choose(kind, position);
            int start;
            int end;
            String replacement;

            switch (kind) {
                case REPLACE_TERM -> {
                    int index = random.nextInt(target.terms.size());
                    String oldTerm = target.terms.get(index);
                    String newTerm;
                    do {
                        newTerm = randomTerm(random);
                    } while (newTerm.equals(oldTerm));
                    start = target.termStart(index);
                    end = start + oldTerm.length();
                    replacement = newTerm;
                    target.terms.set(index, newTerm);
                }
                case INSERT_TERM -> {
                    String term = randomTerm(random);
                    start = target.expressionEnd();
                    end = start;
                    replacement = TERM_SEPARATOR + term;
                    target.terms.add(term);
                }
                case DELETE_TERM -> {
                    int index = random.nextInt(target.terms.size());
                    int termStart = target.termStart(index);
                    start = index == 0 ? termStart : termStart - TERM_SEPARATOR_LENGTH;
                    end = termStart + target.terms.get(index).length()
                            + (index == 0 ? TERM_SEPARATOR_LENGTH : 0);
                    replacement = "";
                    target.terms.remove(index);
                }
                case INSERT_DEFINITION -> {
                    start = target.start;
                    end = start;
                    String expression = "x + 1";
                    replacement = "fun n" + insertedDefinitions++ + "(x: int): int =\n    "
                            + expression + '\n';
                    Definition inserted = new Definition(
                            start,
                            start + replacement.length(),
                            start + replacement.indexOf(expression),
                            new ArrayList<>(List.of("x", "1")));
                    definitions.add(definitions.indexOf(target), inserted);
                    target = inserted;
                }
                case DELETE_DEFINITION -> {
                    start = target.start;
                    end = target.end;
                    replacement = "";
                    definitions.remove(target);
                }
                case TYPE_TERM -> {
                    start = target.expressionEnd();
                    end = start;
                    replacement = String.valueOf(TYPED_TERM.charAt(target.typedCharacters));
                    if (++target.typedCharacters == TYPED_TERM.length()) {
                        target.terms.add("(a)");
                        target.typedCharacters = 0;
                    }
                }
                default -> throw new IllegalStateException();
            }

            int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
            int newline = text.indexOf('\n', end);
            int nextLineStart = newline < 0 ? text.length() : newline + 1;
            int delta = replacement.length() - (end - start);

            text = text.substring(0, start) + replacement + text.substring(end);
            shiftAnchors(start, end, delta, kind == EditKind.INSERT_DEFINITION ? target : null);
            return new EditCase(text, delta, lineStart, nextLineStart);
        }

        private Definition choose(
                EditKind kind,
                PositionBenchmark.Position position) {
            List<Definition> eligible = definitions.stream()
                    .filter(definition -> accepts(definition, kind))
                    .toList();
            if (eligible.isEmpty()) throw new IllegalStateException("no definition accepts " + kind);
            int index = switch (position) {
                case FRONT -> 0;
                case MIDDLE -> eligible.size() / 2;
                case END -> eligible.size() - 1;
            };
            return eligible.get(index);
        }

        private boolean accepts(Definition definition, EditKind kind) {
            return switch (kind) {
                case DELETE_TERM -> definition.expressionStart >= 0
                        && definition.terms.size() > 1
                        && definition.typedCharacters == 0;
                case REPLACE_TERM, INSERT_TERM -> definition.expressionStart >= 0
                        && definition.typedCharacters == 0;
                case TYPE_TERM -> definition.expressionStart >= 0;
                case DELETE_DEFINITION -> definitions.size() > EDIT_COUNT;
                case INSERT_DEFINITION -> true;
            };
        }

        private void shiftAnchors(int editStart, int editEnd, int delta, Definition inserted) {
            if (delta == 0) return;
            for (Definition definition : definitions) {
                if (definition == inserted) continue;
                if (definition.start >= editEnd) definition.start += delta;
                if (definition.end > editStart) definition.end += delta;
                if (definition.expressionStart >= editEnd) definition.expressionStart += delta;
            }
        }

    }
}
