package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.ScopedDefn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static prev26lsp.semantics.types.TypeNav.*;

public class DefinitionTypeChecker {

    private final NameResolver nameRes;
    private final TypeStore store;
    private List<TypeDiagnostic> diags;
    private final DeclaredTypeResolver declared;

    DefinitionTypeChecker(NameResolver nameRes, TypeStore store) {
        this.nameRes = nameRes;
        this.store = store;
        this.declared = new DeclaredTypeResolver(nameRes, store);
    }

    public Type resolve(ScopedDefn defn) {
        Node owner = nameRes.getParent(defn.defNode); // VAR_DEF, FUN_DEF, TYPE_DEF, PARAM

        // Reject tainted nodes
        if (owner.isTainted()) {
            if (defn.kind == ScopedDefn.Kind.TYPE) {
                store.nameType(defn.defNode).setType(Type.UNKNOWN);
            }

            store.putDefType(defn, Type.UNKNOWN);
            store.clearDiagnostics(defn.defNode.id);
            return Type.UNKNOWN;
        }

        diags = new ArrayList<>();

        Type type =  switch (defn.kind) {
            case TYPE -> resolveTypeDef(defn, owner);
            case FUN -> resolveFun(owner);
            case VAR -> resolveVar(owner);
            case PARAM -> resolveParam(owner);
        };

        store.putDefType(defn, type);
        store.setDiagnostics(defn.defNode.id, diags);
        diags = null;

        return type;
    }

    private Type resolveTypeDef(ScopedDefn defn, Node owner) {
        Type.NameType nameType = store.nameType(defn.defNode);
        Node type = firstChild(owner, Symbol.TYPE);

        Type resolved = declared.resolve(type, this.diags);

        nameType.setType(resolved);
        return resolved;
    }

    private Type resolveFun(Node funNode) {
        // FUN ID ( EPARAMS ) : TYPE fun_body
        List<Type> paramTypes = new ArrayList<>();
        Node eparamsNode = firstChild(funNode, Symbol.EPARAMS);

        if (!eparamsNode.isEpsilon()) {
            Node paramNodes = firstChild(eparamsNode, Symbol.PARAMS);

            for (Node paramNode : getChildren(paramNodes, Symbol.PARAM)) {
                Node paramName = firstChild(paramNode, Symbol.ID);

                Optional<ScopedDefn> paramDefn = nameRes.definitionForIdentifier(paramName);
                Type paramType = (paramDefn.isPresent()) ?  store.defType(paramDefn.get()) : Type.UNKNOWN;

                paramTypes.add(paramType);
            }
        }

        Node retTypeNode = lastChild(funNode, Symbol.TYPE);
        Type returnType = declared.resolve(retTypeNode, this.diags);

        if (!returnType.isUnknown() && !returnType.isSimpleOrVoid()) {
            reportError(retTypeNode, "Function return type must be simple or void");
        }

        return new Type.FunType(paramTypes, returnType);
    }

    private Type resolveVar(Node owner) {
        // VAR ID COLON TYPE
        Node typeNode = firstChild(owner, Symbol.TYPE);
        Type type =  declared.resolve(typeNode, this.diags);

        if (!type.isUnknown() && type.actualType() == Type.VOID) {
            reportError(typeNode, "A variable cannot have void type");
        }

        return type;
    }

    private Type resolveParam(Node owner) {
        Node typeNode = firstChild(owner, Symbol.TYPE);
        Type type = declared.resolve(typeNode, this.diags);

        if (!type.isUnknown() && !type.actualType().isSimple()) {
            reportError(typeNode, "A parameter must be a simple type, got " + type);
        }

        return type;
    }

    private void reportError(Node node, String msg) {
        diags.add(new TypeDiagnostic(node, msg));
    }

}
