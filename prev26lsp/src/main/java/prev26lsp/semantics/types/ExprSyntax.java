package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;

import java.util.ArrayList;
import java.util.List;

/**
 * An abstract view of an expression, computed on demand from the parse tree.
 */
sealed interface ExprSyntax {

    /** The parse tree node this view was built from. */
    Node node();

    /** Operand and result type of a flattened operator chain. */
    enum ChainKind { LOGICAL, ARITHMETIC }

    /** A literal whose type is fixed by its spelling. */
    record Literal(Node node, Type type) implements ExprSyntax {}

    /** A reference to a named definition. */
    record Name(Node node, Node id) implements ExprSyntax {}

    /** sizeof T */
    record SizeOf(Node node, Node type) implements ExprSyntax {}

    /** E1 , ... , En — types as its last element. */
    record Seq(Node node, List<Node> items) implements ExprSyntax {}

    /** A parenthesised sequence, which keeps the element's attributes. */
    record Group(Node node, Node items) implements ExprSyntax {}

    /** lhs = rhs */
    record Assign(Node node, Node target, Node value) implements ExprSyntax {}

    /** E as T as ... */
    record Cast(Node node, Node operand, List<Node> targets) implements ExprSyntax {}

    /** a op b op c for one precedence level, already flattened by the parser. */
    record Chain(Node node, ChainKind kind, List<Node> operands) implements ExprSyntax {}

    /** a == b, a < b, ... — never chained. */
    record Compare(Node node, Node left, Node right) implements ExprSyntax {}

    /** !E, +E, -E, ^E; op is kept for its diagnostic. */
    record Unary(Node node, Node op, Node operand) implements ExprSyntax {}

    /** A primary expression followed by its call/index/deref/member tail. */
    record Postfix(Node node, Node base, List<Step> steps) implements ExprSyntax {}

    /** while C do B end */
    record While(Node node, Node condition, Node body) implements ExprSyntax {}

    /** if C then B [else E] end; otherwise is null when there is no else part. */
    record If(Node node, Node condition, Node then, Node otherwise) implements ExprSyntax {}

    /** let defs in B end — definitions are resolved by the definition pass. */
    record Let(Node node, Node body) implements ExprSyntax {}

    /** An incomplete or error-recovered subtree. */
    record Malformed(Node node) implements ExprSyntax {}

    /** One link of a postfix tail. Each keeps its postfix_expr' node for diagnostics. */
    sealed interface Step {
        Node prime();

        record Call(Node prime, List<Node> args) implements Step {}
        record Index(Node prime, Node index) implements Step {}
        record Deref(Node prime) implements Step {}
        record Member(Node prime, Node id) implements Step {}
        record Unknown(Node prime) implements Step {}
    }

    /** Accepts any expression-level node; descends through pass-through precedence levels. */
    static ExprSyntax of(Node node) {
        return switch (node.symbol) {
            case EXPRS, EEXPRS -> new Seq(node, operands(node));

            // expr -> assign_expr
            case EXPR -> descend(node);

            case ASSIGN_EXPR -> {
                // assign_expr -> cast_expr assign_expr'; assign_expr' -> ASSIGN cast_expr | ε
                Node prime = TypeNav.childOrNull(node, Symbol.ASSIGN_EXPR_PRIME);
                Node target = TypeNav.childOrNull(node, Symbol.CAST_EXPR);
                Node value = (prime == null) ? null : TypeNav.childOrNull(prime, Symbol.CAST_EXPR);

                if (target == null) yield new Malformed(node);
                yield (value == null) ? of(target) : new Assign(node, target, value);
            }

            case CAST_EXPR -> {
                // cast_expr -> logical_or (AS type)*  (the tail is flattened into this node)
                List<Node> targets = TypeNav.getChildren(node, Symbol.TYPE);
                Node operand = TypeNav.firstChildOrNull(node);

                if (operand == null) yield new Malformed(node);
                yield targets.isEmpty() ? of(operand) : new Cast(node, operand, targets);
            }

            case LOGICAL_OR, LOGICAL_AND -> chain(node, ChainKind.LOGICAL);
            case ADD_EXPR, MUL_EXPR -> chain(node, ChainKind.ARITHMETIC);

            case COMPARISON -> {
                // comparison -> add_expr comparison'; comparison' -> (EQU|NEQ|...) add_expr | ε
                Node left = TypeNav.childOrNull(node, Symbol.ADD_EXPR);
                Node prime = TypeNav.childOrNull(node, Symbol.COMPARISON_PRIME);
                Node right = (prime == null) ? null : TypeNav.childOrNull(prime, Symbol.ADD_EXPR);

                if (left == null) yield new Malformed(node);
                yield (right == null) ? of(left) : new Compare(node, left, right);
            }

            case PFX_EXPR -> {
                // pfx_expr -> (NOT|PLUS|MINUS|CARET) pfx_expr | postfix_expr
                List<Node> children = node.getChildren();

                if (children.isEmpty()) yield new Malformed(node);
                yield (children.size() == 1)
                        ? of(children.getFirst())
                        : new Unary(node, children.getFirst(), children.getLast());
            }

            case POSTFIX_EXPR -> {
                Node base = TypeNav.childOrNull(node, Symbol.PRIMARY_EXPR);
                yield (base == null)
                        ? new Malformed(node)
                        : new Postfix(node, base, steps(TypeNav.lastChildOrNull(node, Symbol.POSTFIX_EXPR_PRIME)));
            }

            case PRIMARY_EXPR -> primary(node);
            default -> new Malformed(node);
        };
    }

    /** A precedence level that turned out to hold exactly one operand. */
    private static ExprSyntax descend(Node node) {
        Node only = TypeNav.firstChildOrNull(node);

        return (only == null) ? new Malformed(node) : of(only);
    }

    private static ExprSyntax chain(Node node, ChainKind kind) {
        List<Node> operands = operands(node);

        if (operands.isEmpty()) return new Malformed(node);
        return (operands.size() == 1) ? of(operands.getFirst()) : new Chain(node, kind, operands);
    }

    private static ExprSyntax primary(Node node) {
        Node first = TypeNav.firstChildOrNull(node);
        if (first == null) return new Malformed(node);

        return switch (first.symbol) {
            case INTCONST -> new Literal(node, Type.INT);
            case BOOL_EXPR -> new Literal(node, Type.BOOL);
            case CHARCONST -> new Literal(node, Type.CHAR);
            case STRINGCONST -> new Literal(node, Type.STR);
            case VOID_EXPR -> new Literal(node, Type.VOID);
            case PTR_EXPR -> new Literal(node, Type.NIL);

            case ID -> new Name(node, first);

            case SIZEOF -> {
                Node type = TypeNav.childOrNull(node, Symbol.TYPE);
                yield (type == null) ? new Malformed(node) : new SizeOf(node, type);
            }

            case WHILE -> {
                // WHILE expr DO exprs END
                Node condition = TypeNav.childOrNull(node, Symbol.EXPR);
                Node body = TypeNav.childOrNull(node, Symbol.EXPRS);

                yield (condition == null || body == null) ? new Malformed(node) : new While(node, condition, body);
            }

            case IF -> {
                // IF expr THEN exprs else_part END; else_part -> ELSE exprs | ε
                Node condition = TypeNav.childOrNull(node, Symbol.EXPR);
                Node then = TypeNav.childOrNull(node, Symbol.EXPRS);
                Node elsePart = TypeNav.lastChildOrNull(node, Symbol.ELSE_PART);

                if (condition == null || then == null) yield new Malformed(node);
                yield new If(node, condition, then,
                        (elsePart == null) ? null : TypeNav.childOrNull(elsePart, Symbol.EXPRS));
            }

            case LPAREN -> {
                Node items = TypeNav.childOrNull(node, Symbol.EXPRS);
                yield (items == null) ? new Malformed(node) : new Group(node, items);
            }

            case LET -> {
                Node body = TypeNav.childOrNull(node, Symbol.EXPRS);
                yield (body == null) ? new Malformed(node) : new Let(node, body);
            }

            default -> new Malformed(node);
        };
    }

    /** Flattens the right-recursive postfix_expr' spine into the tail it spells. */
    private static List<Step> steps(Node prime) {
        List<Step> steps = new ArrayList<>();

        while (prime != null && !prime.isEpsilon()) {
            Node op = TypeNav.firstChildOrNull(prime);
            if (op == null) break;

            steps.add(switch (op.symbol) {
                // LPAREN eexprs RPAREN
                case LPAREN -> new Step.Call(prime, arguments(TypeNav.childOrNull(prime, Symbol.EEXPRS)));

                // LSQUARE expr RSQUARE
                case LSQUARE -> {
                    Node index = TypeNav.childOrNull(prime, Symbol.EXPR);
                    yield (index == null) ? new Step.Unknown(prime) : new Step.Index(prime, index);
                }

                case CARET -> new Step.Deref(prime);

                // DOT ID
                case DOT -> {
                    Node id = TypeNav.childOrNull(prime, Symbol.ID);
                    yield (id == null) ? new Step.Unknown(prime) : new Step.Member(prime, id);
                }

                default -> new Step.Unknown(prime);
            });

            prime = TypeNav.lastChildOrNull(prime, Symbol.POSTFIX_EXPR_PRIME);
        }

        return steps;
    }

    private static List<Node> arguments(Node eexprs) {
        Node exprs = (eexprs == null) ? null : TypeNav.childOrNull(eexprs, Symbol.EXPRS);

        return (exprs == null) ? List.of() : operands(exprs);
    }

    /** The expression children of a flattened node, dropping the interleaved operators. */
    private static List<Node> operands(Node node) {
        List<Node> operands = new ArrayList<>();

        for (Node child : node.getChildren()) {
            if (isExpression(child)) {
                operands.add(child);
            }
        }

        return operands;
    }

    private static boolean isExpression(Node node) {
        return switch (node.symbol) {
            case EXPRS, EEXPRS, EXPR, ASSIGN_EXPR, CAST_EXPR,
                 LOGICAL_OR, LOGICAL_AND, COMPARISON, ADD_EXPR, MUL_EXPR,
                 PFX_EXPR, POSTFIX_EXPR, PRIMARY_EXPR,
                 BOOL_EXPR, PTR_EXPR, VOID_EXPR -> true;
            default -> false;
        };
    }

}
