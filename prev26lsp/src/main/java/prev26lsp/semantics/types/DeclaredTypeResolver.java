package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.semantics.names.ScopedDefn;
import prev26lsp.semantics.names.NameResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a declared type into a Type.
 */
public class DeclaredTypeResolver {

    private final NameResolver nameRes;
    private final TypeStore store;
    private List<TypeDiagnostic> diagnostics;

    DeclaredTypeResolver(NameResolver names, TypeStore store) {
        this.nameRes = names;
        this.store = store;
    }

    Type resolve(Node typeNode, List<TypeDiagnostic> sink) {
        List<TypeDiagnostic> saved = this.diagnostics;

        this.diagnostics = sink;
        Type t = resolve(typeNode);

        this.diagnostics = saved;
        return t;
    }

    private Type resolve(Node typeNode) {
        return switch (TypeSyntax.of(typeNode)) {
            case TypeSyntax.Atom(Node _, Type.AtomType type) -> type;
            case TypeSyntax.Named(Node _, Node id) -> named(id);
            case TypeSyntax.Pointer(Node _, Node base) -> pointer(base);
            case TypeSyntax.Array(Node node, Node size, boolean negated, Node elem) -> array(node, size, negated, elem);
            case TypeSyntax.Rec(Node _, boolean isUnion, List<TypeSyntax.Field> fields) -> record(isUnion, fields);
            case TypeSyntax.Fun(Node _, List<Node> params, Node result) -> function(params, result);

            // An incomplete subtree: the parser has already reported why.
            case TypeSyntax.Malformed _ -> Type.UNKNOWN;
        };
    }

    private Type named(Node idNode) {
        Optional<ScopedDefn> maybeDef = nameRes.definitionForIdentifier(idNode);
        if (maybeDef.isEmpty()) {
            return Type.UNKNOWN;
        }

        ScopedDefn defn = maybeDef.get();
        if (defn.kind != ScopedDefn.Kind.TYPE) {
            reportError(idNode, "'" + idNode.value + "' is not a type");
            return Type.UNKNOWN;
        }

        return store.nameType(defn.defNode);
    }

    private Type pointer(Node baseNode) {
        Type baseType = resolve(baseNode);

        if (!baseType.isUnknown() && baseType.actualType() == Type.VOID) {
            reportError(baseNode, "Pointers to void are not allowed");
        }

        return new Type.PtrType(baseType);
    }

    private Type array(Node node, Node sizeNode, boolean negated, Node elemNode) {
        if (negated) {
            reportError(node, "Array size must be non-negative");
            return Type.UNKNOWN;
        }

        if (sizeNode.value == null) {
            return Type.UNKNOWN; // Missing length token, already reported by the parser
        }

        long arrLength;
        try {
            arrLength = Long.parseLong(sizeNode.value);
        } catch (NumberFormatException e) {
            reportError(sizeNode, "Array size is too large");
            return Type.UNKNOWN;
        }

        if (arrLength == 0) {
            reportError(sizeNode, "Array size cannot be 0");
            return Type.UNKNOWN;
        }

        Type elemType = resolve(elemNode);

        if (!elemType.isUnknown() && elemType.actualType() == Type.VOID) {
            reportError(elemNode, "Array of type void is not allowed");
        }

        return new Type.ArrType(elemType, arrLength);
    }

    private Type record(boolean isUnion, List<TypeSyntax.Field> fields) {
        List<String> compNames = new ArrayList<>();
        List<Type>   compTypes = new ArrayList<>();

        for (TypeSyntax.Field field : fields) {
            Type fieldType = resolve(field.type());

            if (!fieldType.isUnknown() && fieldType.actualType() == Type.VOID) {
                reportError(field.type(), "A component cannot have void type");
            }

            String fieldName = field.id().value;
            if (compNames.contains(fieldName)) {
                reportError(field.id(), "Duplicate field: '" + fieldName + "'");
            }

            compNames.add(fieldName);
            compTypes.add(fieldType);
        }

        return new Type.RecType(isUnion, compNames, compTypes);
    }

    private Type function(List<Node> paramNodes, Node resultNode) {
        List<Type> paramTypes = new ArrayList<>();

        for (Node paramNode : paramNodes) {
            Type paramType = resolve(paramNode);

            if (!paramType.isUnknown() && !paramType.isSimple()) {
                reportError(paramNode, "A parameter type must be simple, got " + paramType);
            }

            paramTypes.add(paramType);
        }

        Type returnType = resolve(resultNode);

        if (!returnType.isUnknown() && !returnType.isSimpleOrVoid()) {
            reportError(resultNode, "A return type must be simple or void, got " + returnType);
        }

        return new Type.FunType(paramTypes, returnType);
    }

    private void reportError(Node node, String msg) {
        diagnostics.add(new TypeDiagnostic(node, msg));
    }

}
