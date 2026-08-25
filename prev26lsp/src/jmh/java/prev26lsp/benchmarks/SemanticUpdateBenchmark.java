package prev26lsp.benchmarks;

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
import prev26lsp.lexer.Lexer;
import prev26lsp.lexer.Token;
import prev26lsp.model.Diagnostic;
import prev26lsp.parser.IncHelpers;
import prev26lsp.parser.Node;
import prev26lsp.parser.Parser;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.names.NameDelta;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.ScopedDefn;
import prev26lsp.semantics.types.Type;
import prev26lsp.semantics.types.TypeChecker;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Cost of one semantic phase after a controlled edit. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 25)
@Measurement(iterations = 25)
@Fork(value = 3, jvmArgs = {"-Xms8g", "-Xmx8g"})
public class SemanticUpdateBenchmark {

    public enum Engine {
        INCREMENTAL,
        FULL
    }

    public enum Phase {
        NAMES,
        TYPES
    }

    @Param({"INCREMENTAL", "FULL"})
    public Engine engine;

    @Param({"NAMES", "TYPES"})
    public Phase phase;

    @Param({"250", "1000", "4000"})
    public int functions;

    @Param({"RENAME_LOCAL", "RENAME_GLOBAL", "CHANGE_TYPE_NARROW", "CHANGE_TYPE_WIDE"})
    public SemanticWorkload.EditKind editKind;

    private SemanticWorkload.Program program;
    private SemanticWorkload.EditCase edit;

    private Node tree;
    private NameResolver names;
    private TypeChecker types;
    private Parser.ParseResult parseResult;
    private NameDelta nameDelta;

    @Setup(Level.Trial)
    public void buildAndValidate() {
        program = SemanticWorkload.generate(functions);
        edit = SemanticWorkload.edit(program, editKind);
        validateIncrementalUpdate();
    }

    /** Prepare exactly the untimed prerequisites of the selected table cell. */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        if (engine == Engine.INCREMENTAL) {
            prepareIncremental();
            if (phase == Phase.TYPES) {
                nameDelta = names.reanalyze(parseResult.root(), parseResult.delta());
            }
            return;
        }

        tree = Parser.parse(lex(edit.newText()));
        names = new NameResolver();
        types = new TypeChecker();
        if (phase == Phase.TYPES) {
            names.resolveFull(tree);
        }
    }

    @Benchmark
    public Object semanticPhase() {
        return switch (engine) {
            case INCREMENTAL -> switch (phase) {
                case NAMES -> names.reanalyze(parseResult.root(), parseResult.delta());
                case TYPES -> {
                    types.recompute(parseResult.root(), names, parseResult.delta(), nameDelta);
                    yield types;
                }
            };
            case FULL -> switch (phase) {
                case NAMES -> {
                    names.resolveFull(tree);
                    yield names;
                }
                case TYPES -> {
                    types.computeFull(tree, names);
                    yield types;
                }
            };
        };
    }

    private void prepareIncremental() {
        names = new NameResolver();
        types = new TypeChecker();
        tree = Parser.parse(lex(program.text()));
        names.resolveFull(tree);
        types.computeFull(tree, names);

        IncHelpers.ReparsePlan plan = IncHelpers.planReparse(
                tree, program.text().length(), edit.delta(),
                edit.editLineStartOld(), edit.nextLineStartOld());
        int lexStart = Math.min(plan.lexStart(), edit.newText().length());
        int lexEnd = Math.min(plan.lexEnd(), edit.newText().length());
        parseResult = Parser.incrementalParseWithDelta(
                tree, plan.leftKeep(), plan.rightCut(),
                lex(edit.newText().substring(lexStart, lexEnd)));
    }

    private void validateIncrementalUpdate() {
        prepareIncremental();
        NameDelta delta = names.reanalyze(parseResult.root(), parseResult.delta());
        types.recompute(parseResult.root(), names, parseResult.delta(), delta);

        Node fullTree = Parser.parse(lex(edit.newText()));
        NameResolver fullNames = new NameResolver();
        fullNames.resolveFull(fullTree);
        TypeChecker fullTypes = new TypeChecker();
        fullTypes.computeFull(fullTree, fullNames);

        assertSameTree(fullTree, parseResult.root());
        assertEqual("name bindings", bindings(fullTree, fullNames),
                bindings(parseResult.root(), names));
        assertEqual("name diagnostics", diagnostics(fullNames.collectDiagnostics()),
                diagnostics(names.collectDiagnostics()));
        assertEqual("type diagnostics", diagnostics(fullTypes.collectDiagnostics()),
                diagnostics(types.collectDiagnostics()));
        assertSameDefinitionTypes(fullNames, fullTypes, names, types);
    }

    private static Map<Integer, String> bindings(Node root, NameResolver resolver) {
        Map<Integer, String> out = new HashMap<>();
        record At(Node node, int offset) {}
        ArrayDeque<At> pending = new ArrayDeque<>();
        pending.push(new At(root, 0));

        while (!pending.isEmpty()) {
            At at = pending.pop();
            Node node = at.node();
            if (node.isTerminal()) {
                if (node.symbol == Symbol.ID) {
                    String target = resolver.definitionForIdentifier(node)
                            .map(defn -> (resolver.absoluteOffsetOf(defn.defNode)
                                    + defn.defNode.leadingWidth) + ":" + defn.name + ":" + defn.kind)
                            .orElse("UNRESOLVED");
                    out.put(at.offset() + node.leadingWidth, target);
                }
                continue;
            }

            int cursor = at.offset() + node.getWidth();
            List<Node> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                Node child = children.get(i);
                cursor -= child.getWidth();
                pending.push(new At(child, cursor));
            }
        }
        return out;
    }

    private static List<String> diagnostics(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.start() + ":" + d.length() + ":" + d.message())
                .sorted()
                .toList();
    }

    private static void assertSameDefinitionTypes(
            NameResolver expectedNames,
            TypeChecker expectedTypes,
            NameResolver actualNames,
            TypeChecker actualTypes) {
        Map<String, Type> expected = definitionTypes(expectedNames, expectedTypes);
        Map<String, Type> actual = definitionTypes(actualNames, actualTypes);
        assertEqual("typed definitions", expected.keySet(), actual.keySet());
        for (String key : expected.keySet()) {
            if (!Type.sameShape(expected.get(key), actual.get(key))) {
                throw new IllegalStateException("definition type differs at " + key
                        + " (expected " + expected.get(key) + ", got " + actual.get(key) + ")");
            }
        }
    }

    private static Map<String, Type> definitionTypes(NameResolver names, TypeChecker types) {
        Map<String, Type> out = new HashMap<>();
        for (ScopedDefn defn : names.definitions()) {
            int offset = names.absoluteOffsetOf(defn.defNode) + defn.defNode.leadingWidth;
            out.put(offset + ":" + defn.name + ":" + defn.kind, types.typeOf(defn));
        }
        return out;
    }

    private static void assertEqual(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(what + " differ (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertSameTree(Node expected, Node actual) {
        record Pair(Node expected, Node actual) {}
        ArrayDeque<Pair> pending = new ArrayDeque<>();
        pending.push(new Pair(expected, actual));

        while (!pending.isEmpty()) {
            Pair pair = pending.pop();
            Node left = pair.expected();
            Node right = pair.actual();
            if (left.symbol != right.symbol
                    || left.kind != right.kind
                    || left.getWidth() != right.getWidth()
                    || !Objects.equals(left.value, right.value)
                    || left.getChildren().size() != right.getChildren().size()) {
                throw new IllegalStateException("incremental tree differs from full parse at "
                        + left.symbol + " (expected " + left + ", got " + right + ")");
            }
            for (int i = left.getChildren().size() - 1; i >= 0; i--) {
                pending.push(new Pair(left.getChildren().get(i), right.getChildren().get(i)));
            }
        }
    }

    private static List<Node> lex(String text) {
        List<Node> nodes = new ArrayList<>();
        Lexer lexer = new Lexer(new StringReader(text));
        for (Token token = lexer.nextToken(); token.type != Symbol.EOF; token = lexer.nextToken()) {
            nodes.add(Node.fromToken(token));
        }
        return nodes;
    }
}
