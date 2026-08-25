package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.ScopedDefn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Types expressions, one method per language construct.
 */
public class ExpressionTypeChecker {

    public record Attrs(Type type, boolean isConst, boolean isAddr) {
        public static final Attrs UNKNOWN = new Attrs(Type.UNKNOWN, false, false);

        static Attrs rvalue(Type t) { return new Attrs(t, false, false); }
        static Attrs lvalue(Type t) { return new Attrs(t, false, true); }
        static Attrs constant(Type t) { return new Attrs(t, true, false); }
    }

    private final NameResolver nameRes;
    private final TypeStore store;
    private final DeclaredTypeResolver typeResolver;
    private List<TypeDiagnostic> current;

    ExpressionTypeChecker(NameResolver nameRes, TypeStore store) {
        this.nameRes = nameRes;
        this.store = store;
        this.typeResolver = new DeclaredTypeResolver(nameRes, store);
    }

    public Attrs checkBody(Node body, List<TypeDiagnostic> sink) {
        List<TypeDiagnostic> saved = current;
        current = sink;

        try {
            return typeOf(body);
        } finally {
            current = saved;
        }
    }

    public Attrs typeOf(Node node) {
        // Don't type check tainted subtrees
        if (node.isTainted()) {
            return Attrs.UNKNOWN;
        }

        // Only EXPRs are cached
        if (node.symbol != Symbol.EXPR) {
            return compute(node);
        }

        // Check if its type is cached
        Attrs attrs = store.exprAttrs(node.id);
        if (attrs == null) {
            // Populate the cache
            List<TypeDiagnostic> saved = current;
            current = new ArrayList<>();

            try {
                attrs = compute(node);
                store.putStatement(node.id, attrs, current);
            } finally {
                current = saved;
            }
        }

        // Bubble up diagnostics to the parent
        current.addAll(store.statementDiagnostics(node.id));

        return attrs;
    }

    private Attrs compute(Node node) {
        return switch (ExprSyntax.of(node)) {
            case ExprSyntax.Literal(Node _, Type type) -> Attrs.constant(type);
            case ExprSyntax.Name(Node _, Node id) -> name(id);
            case ExprSyntax.SizeOf(Node _, Node type) -> sizeOf(type);
            case ExprSyntax.Seq(Node _, List<Node> items) -> sequence(items);
            case ExprSyntax.Group(Node _, Node items) -> typeOf(items);
            case ExprSyntax.Assign(Node _, Node target, Node value) -> assignment(target, value);
            case ExprSyntax.Cast(Node n, Node operand, List<Node> targets) -> cast(n, operand, targets);
            case ExprSyntax.Chain(Node _, ExprSyntax.ChainKind kind, List<Node> operands) -> chain(kind, operands);
            case ExprSyntax.Compare(Node n, Node left, Node right) -> comparison(n, left, right);
            case ExprSyntax.Unary(Node _, Node op, Node operand) -> unary(op, operand);
            case ExprSyntax.Postfix(Node _, Node base, List<ExprSyntax.Step> steps) -> postfix(base, steps);
            case ExprSyntax.While(Node _, Node condition, Node body) -> loop(condition, body);
            case ExprSyntax.If(Node _, Node condition, Node then, Node otherwise) -> conditional(condition, then, otherwise);
            case ExprSyntax.Let(Node _, Node body) -> Attrs.rvalue(typeOf(body).type());

            case ExprSyntax.Malformed _ -> Attrs.UNKNOWN;
        };
    }

    // E1 , ... , En: types as its last element, constant only if all of them are
    private Attrs sequence(List<Node> items) {
        Attrs last = Attrs.rvalue(Type.VOID);
        boolean allConst = true;

        for (Node item : items) {
            last = typeOf(item);
            allConst &= last.isConst();
        }

        return new Attrs(last.type(), allConst, last.isAddr());
    }

    // and/or (all bool), +,-,*,/,% (all int)
    private Attrs chain(ExprSyntax.ChainKind kind, List<Node> operands) {
        Type type = (kind == ExprSyntax.ChainKind.LOGICAL) ? Type.BOOL : Type.INT;
        boolean allConst = true;

        for (Node operand : operands) {
            Attrs operandAttrs = typeOf(operand);

            expectType(operandAttrs.type(), type, operand);
            allConst &= operandAttrs.isConst();
        }

        return new Attrs(type, allConst, false);
    }

    private Attrs comparison(Node node, Node leftNode, Node rightNode) {
        Attrs left = typeOf(leftNode);
        Attrs right = typeOf(rightNode);

        if (!left.type().isUnknown() && !right.type().isUnknown()) {
            if (!left.type().isSimple()) {
                report(leftNode, "Cannot compare values of type " + left.type());
            } else if (!right.type().isSimple()) {
                report(rightNode, "Cannot compare values of type " + right.type());
            } else if (!left.type().isEquivalentTo(right.type())) {
                report(node, "Cannot compare " + left.type() + " and " + right.type());
            }
        }

        return new Attrs(Type.BOOL, left.isConst() && right.isConst(), false);
    }

    private Attrs assignment(Node lhsNode, Node rhsNode) {
        Attrs target = typeOf(lhsNode);
        Attrs value = typeOf(rhsNode);

        if (!target.type().isUnknown()) {
            if (!target.isAddr()) {
                report(lhsNode, "Target of assignment is not an lvalue");
            }

            if (!target.type().isSimple()) {
                report(lhsNode, "Cannot assign values of type " + target.type());
            } else {
                expectType(value.type(), target.type(), rhsNode);
            }
        }

        return Attrs.rvalue(Type.VOID);
    }

    private Attrs cast(Node node, Node operandNode, List<Node> targetNodes) {
        Attrs original = typeOf(operandNode);
        Type currentType = original.type();

        for (Node targetNode : targetNodes) {
            Type targetType = typeResolver.resolve(targetNode, current);

            if (!currentType.isUnknown() && currentType.isVoid()) {
                report(node, "Cannot cast an expression of type void");
            }

            if (!targetType.isUnknown() && targetType.isVoid()) {
                report(targetNode, "Cannot cast to void");
            }

            currentType = targetType;
        }

        return new Attrs(currentType, original.isConst(), original.isAddr());
    }

    private Attrs unary(Node operatorNode, Node operandNode) {
        Attrs operand = typeOf(operandNode);

        return switch (operatorNode.symbol) {
            case NOT -> {
                expectType(operand.type(), Type.BOOL, operandNode);
                yield new Attrs(Type.BOOL, operand.isConst(), false);
            }

            case PLUS, MINUS -> {
                expectType(operand.type(), Type.INT, operandNode);
                yield new Attrs(Type.INT, operand.isConst(), false);
            }

            case CARET -> {
                if (!operand.type().isUnknown() && (!operand.isAddr() || operand.type().isVoid())) {
                    report(operatorNode, "Expression is not addressable");
                }

                yield Attrs.rvalue(new Type.PtrType(operand.type()));
            }

            default -> Attrs.UNKNOWN;
        };
    }

    private Attrs postfix(Node base, List<ExprSyntax.Step> steps) {
        Attrs attrs = typeOf(base);

        for (ExprSyntax.Step step : steps) {
            attrs = apply(attrs, step);
        }

        return attrs;
    }

    /**
     * The type of a postfix tail up to but excluding stopPrime, diagnostics discarded.
     * Used by autocomplete to suggest members of a record or the type of an expression before a function call.
     */
    Attrs chainTypeBefore(Node postfixExpr, Node stopPrime) {
        List<TypeDiagnostic> saved = current;
        current = new ArrayList<>();

        try {
            if (!(ExprSyntax.of(postfixExpr) instanceof ExprSyntax.Postfix(Node _, Node base, List<ExprSyntax.Step> steps))) {
                return Attrs.UNKNOWN;
            }

            Attrs attrs = typeOf(base);
            for (ExprSyntax.Step step : steps) {
                if (step.prime().id == stopPrime.id) break;
                attrs = apply(attrs, step);
            }

            return attrs;
        } finally {
            current = saved;
        }
    }

    private Attrs apply(Attrs receiver, ExprSyntax.Step step) {
        return switch (step) {
            case ExprSyntax.Step.Call(Node prime, List<Node> args) -> call(receiver, prime, args);
            case ExprSyntax.Step.Index(Node prime, Node index) -> index(receiver, prime, index);
            case ExprSyntax.Step.Deref(Node prime) -> deref(receiver, prime);
            case ExprSyntax.Step.Member(Node prime, Node id) -> member(receiver, prime, id);
            case ExprSyntax.Step.Unknown _ -> Attrs.UNKNOWN;
        };
    }

    private Attrs call(Attrs callee, Node prime, List<Node> args) {
        List<Attrs> argAttrs = args.stream().map(this::typeOf).toList();

        Type calleeType = callee.type().actualType();
        if (calleeType.isUnknown()) {
            return Attrs.UNKNOWN;
        }
        if (!(calleeType instanceof Type.FunType(List<Type> paramTypes, Type returnType))) {
            report(prime, "Called expression is not a function (" + callee.type() + ")");
            return Attrs.UNKNOWN;
        }

        int paramCount = paramTypes.size();
        if (paramCount != args.size()) {
            report(prime, "Expected " + paramCount + " arguments, got " + args.size());
        } else {
            // Check if arguments match expected ones
            for (int i = 0; i < paramCount; i++) {
                expectType(argAttrs.get(i).type(), paramTypes.get(i), args.get(i));
            }
        }

        return Attrs.rvalue(returnType);
    }

    private Attrs index(Attrs base, Node prime, Node indexNode) {
        Attrs index = typeOf(indexNode);

        expectType(index.type(), Type.INT, indexNode);

        Type baseType = base.type().actualType();
        if (baseType.isUnknown()) {
            return Attrs.UNKNOWN;
        }

        if (!(baseType instanceof Type.ArrType arr)) {
            report(prime, "Cannot index into a value of type " + baseType);
            return Attrs.UNKNOWN;
        }

        if (!base.isAddr()) {
            report(prime, "Indexed expression must be addressable");
        }

        return Attrs.lvalue(arr.elemType());
    }

    private Attrs deref(Attrs expr, Node prime) {
        Type exprType = expr.type().actualType();
        if (exprType.isUnknown()) {
            return Attrs.UNKNOWN;
        }

        if (!(exprType instanceof Type.PtrType(Type baseType))) {
            report(prime, "Cannot dereference a value of type " + exprType);
            return Attrs.UNKNOWN;
        }

        if (expr.isConst()) {
            report(prime, "Cannot dereference a constant pointer");
        }

        if (baseType.actualType() == Type.VOID) {
            report(prime, "Cannot dereference a void pointer");
            return Attrs.UNKNOWN;
        }

        return Attrs.lvalue(baseType);
    }

    private Attrs member(Attrs expr, Node prime, Node idNode) {
        Type exprType = expr.type().actualType();

        if (exprType.isUnknown()) {
            return Attrs.UNKNOWN;
        }

        if (!(exprType instanceof Type.RecType rec)) {
            report(idNode, "Type " + expr.type() + " has no components");
            return Attrs.UNKNOWN;
        }

        if (!expr.isAddr()) {
            report(idNode, "Record is not addressable");
        }

        // Try to find a component with this name
        String fieldName = idNode.value;
        int compIdx = rec.compNames().indexOf(fieldName);

        if (compIdx < 0) {
            report(idNode, "No component '" + fieldName + "' in '" + expr.type() + "'");
            return Attrs.UNKNOWN;
        }

        return Attrs.lvalue(rec.compTypes().get(compIdx));
    }

    private Attrs name(Node idNode) {
        Optional<ScopedDefn> maybe = nameRes.definitionForIdentifier(idNode);
        if (maybe.isEmpty()) {
            return Attrs.UNKNOWN; // Name resolver error
        }

        ScopedDefn defn = maybe.get();
        if (defn.kind == ScopedDefn.Kind.TYPE) {
            report(idNode, "'" + idNode.value + "' is a type, not a value");
            return Attrs.UNKNOWN;
        }

        // Functions are not lvalues
        boolean isAddr = (defn.kind != ScopedDefn.Kind.FUN);
        return new Attrs(store.defType(defn), false, isAddr);
    }

    private Attrs sizeOf(Node typeNode) {
        Type type = typeResolver.resolve(typeNode, current);

        if (!type.isUnknown() && type.isVoid()) {
            report(typeNode, "sizeof void is not allowed");
        }

        return Attrs.constant(Type.INT);
    }

    private Attrs loop(Node condNode, Node body) {
        expectType(typeOf(condNode).type(), Type.BOOL, condNode);
        typeOf(body);

        return Attrs.rvalue(Type.VOID);
    }

    private Attrs conditional(Node condNode, Node then, Node otherwise) {
        expectType(typeOf(condNode).type(), Type.BOOL, condNode);
        typeOf(then);

        if (otherwise != null) {
            typeOf(otherwise);
        }

        return Attrs.rvalue(Type.VOID);
    }

    private void report(Node node, String msg) {
        current.add(new TypeDiagnostic(node, msg));
    }

    private void expectType(Type actual, Type wanted, Node node) {
        if (actual.isUnknown() || wanted.isUnknown()) {
            return;
        }

        if (!wanted.isEquivalentTo(actual)) {
            report(node, "Expected " + wanted + ", got " + actual);
        }
    }

}
