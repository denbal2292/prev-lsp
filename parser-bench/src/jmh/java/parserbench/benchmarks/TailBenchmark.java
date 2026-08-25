package parserbench.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import parserbench.lexer.Lexer;
import parserbench.lexer.Token;
import parserbench.parser.Symbol;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Cost of retaining or losing the reusable tail of one flattened expression. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {"-Xms8g", "-Xmx8g"})
public class TailBenchmark {

    public enum Backend {
        baseline,
        array,
        treap,
        prevFull;

        boolean isIncremental() {
            return this != prevFull;
        }
    }

    public enum EditKind {
        VALID,
        ERROR
    }

    @Param({"baseline", "array", "treap", "prevFull"})
    public Backend backend;

    @Param({"1024", "4096", "16384"})
    public int terms;

    @Param({"VALID", "ERROR"})
    public EditKind editKind;

    private String baseText;
    private Object baseTree;
    private TailWorkload.EditCase edit;

    @Setup(Level.Trial)
    public void buildAndValidate() {
        baseText = TailWorkload.program(terms);
        edit = TailWorkload.edit(baseText, editKind);
        if (backend.isIncremental()) {
            baseTree = parse(baseText);
            assertSameTree(parse(edit.newText()), apply(baseTree));
        }
    }

    @Benchmark
    public Object edit() {
        return apply(baseTree);
    }

    private Object parse(String text) {
        return switch (backend) {
            case baseline, prevFull -> parserbench.parser.baseline.Parser.parse(lexBaseline(text));
            case array -> parserbench.parser.array.Parser.parse(lexArray(text));
            case treap -> parserbench.parser.treap.Parser.parse(lexTreap(text));
        };
    }

    private Object apply(Object tree) {
        return switch (backend) {
            case baseline -> applyBaseline((parserbench.parser.baseline.Node) tree);
            case array -> applyArray((parserbench.parser.array.Node) tree);
            case treap -> applyTreap((parserbench.parser.treap.Node) tree);
            case prevFull -> parserbench.parser.baseline.Parser.parse(lexBaseline(edit.newText()));
        };
    }

    private parserbench.parser.baseline.Node applyBaseline(
            parserbench.parser.baseline.Node tree) {
        var plan = parserbench.parser.baseline.IncHelpers.planReparse(
                tree, baseText.length(), edit.delta(),
                edit.editLineStartOld(), edit.nextLineStartOld());
        return parserbench.parser.baseline.Parser.incrementalParse(
                tree, plan.leftKeep(), plan.rightCut(),
                lexBaseline(window(plan.lexStart(), plan.lexEnd())));
    }

    private parserbench.parser.array.Node applyArray(parserbench.parser.array.Node tree) {
        var plan = parserbench.parser.array.IncHelpers.planReparse(
                tree, baseText.length(), edit.delta(),
                edit.editLineStartOld(), edit.nextLineStartOld());
        return parserbench.parser.array.Parser.incrementalParse(
                tree, plan.leftKeep(), plan.rightCut(),
                lexArray(window(plan.lexStart(), plan.lexEnd())));
    }

    private parserbench.parser.treap.Node applyTreap(parserbench.parser.treap.Node tree) {
        var plan = parserbench.parser.treap.IncHelpers.planReparse(
                tree, baseText.length(), edit.delta(),
                edit.editLineStartOld(), edit.nextLineStartOld());
        return parserbench.parser.treap.Parser.incrementalParse(
                tree, plan.leftKeep(), plan.rightCut(),
                lexTreap(window(plan.lexStart(), plan.lexEnd())));
    }

    private String window(int start, int end) {
        String text = edit.newText();
        return text.substring(Math.min(start, text.length()), Math.min(end, text.length()));
    }

    private static List<parserbench.parser.baseline.Node> lexBaseline(String source) {
        List<parserbench.parser.baseline.Node> nodes = new ArrayList<>();
        Lexer lexer = new Lexer(new StringReader(source));
        for (Token token = lexer.nextToken(); token.type != Symbol.EOF; token = lexer.nextToken()) {
            nodes.add(parserbench.parser.baseline.Node.fromToken(token));
        }
        return nodes;
    }

    private static List<parserbench.parser.array.Node> lexArray(String source) {
        List<parserbench.parser.array.Node> nodes = new ArrayList<>();
        Lexer lexer = new Lexer(new StringReader(source));
        for (Token token = lexer.nextToken(); token.type != Symbol.EOF; token = lexer.nextToken()) {
            nodes.add(parserbench.parser.array.Node.fromToken(token));
        }
        return nodes;
    }

    private static List<parserbench.parser.treap.Node> lexTreap(String source) {
        List<parserbench.parser.treap.Node> nodes = new ArrayList<>();
        Lexer lexer = new Lexer(new StringReader(source));
        for (Token token = lexer.nextToken(); token.type != Symbol.EOF; token = lexer.nextToken()) {
            nodes.add(parserbench.parser.treap.Node.fromToken(token));
        }
        return nodes;
    }

    private record Shape(
            Object symbol,
            Object kind,
            int width,
            Object value,
            Object lexError,
            Object errorContext,
            boolean tainted,
            List<?> children) {}

    private Shape shape(Object node) {
        return switch (backend) {
            case baseline, prevFull -> {
                var value = (parserbench.parser.baseline.Node) node;
                yield new Shape(value.symbol, value.kind, value.getWidth(), value.value,
                        value.lexError, value.errorContext, value.isTainted(), value.getChildren());
            }
            case array -> {
                var value = (parserbench.parser.array.Node) node;
                yield new Shape(value.symbol, value.kind, value.getWidth(), value.value,
                        value.lexError, value.errorContext, value.isTainted(), value.getChildren());
            }
            case treap -> {
                var value = (parserbench.parser.treap.Node) node;
                yield new Shape(value.symbol, value.kind, value.getWidth(), value.value,
                        value.lexError, value.errorContext, value.isTainted(), value.getChildren());
            }
        };
    }

    private void assertSameTree(Object expected, Object actual) {
        record Pair(Object expected, Object actual) {}

        Deque<Pair> pending = new ArrayDeque<>();
        pending.push(new Pair(expected, actual));
        while (!pending.isEmpty()) {
            Pair pair = pending.pop();
            Shape left = shape(pair.expected());
            Shape right = shape(pair.actual());
            if (!Objects.equals(left.symbol(), right.symbol())
                    || !Objects.equals(left.kind(), right.kind())
                    || left.width() != right.width()
                    || !Objects.equals(left.value(), right.value())
                    || !Objects.equals(left.lexError(), right.lexError())
                    || !Objects.equals(left.errorContext(), right.errorContext())
                    || left.tainted() != right.tainted()
                    || left.children().size() != right.children().size()) {
                throw new IllegalStateException("incremental tree differs from full parse");
            }
            for (int i = left.children().size() - 1; i >= 0; i--) {
                pending.push(new Pair(left.children().get(i), right.children().get(i)));
            }
        }
    }
}
