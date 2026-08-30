package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.semantics.names.ScopedDefn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class TypeStore {

    private final Map<Integer, Type.NameType> nameTypes = new HashMap<>();
    private final Map<Integer, Type> defTypes  = new HashMap<>();

    private final Map<Integer, List<TypeDiagnostic>> defDiagnostics = new HashMap<>();

    private final Map<Integer, ExpressionTypeChecker.Attrs> exprAttrs = new HashMap<>();
    private final Map<Integer, List<TypeDiagnostic>> exprDiagnostics = new HashMap<>();

    private final Map<Integer, TypeDiagnostic> cycleDiagnostics = new HashMap<>();

    void clear() {
        nameTypes.clear();
        defTypes.clear();
        exprAttrs.clear();
        exprDiagnostics.clear();
        defDiagnostics.clear();
        cycleDiagnostics.clear();
    }

    void removeNode(int nodeId) {
        nameTypes.remove(nodeId);
        defTypes.remove(nodeId);
        exprAttrs.remove(nodeId);
        exprDiagnostics.remove(nodeId);
        defDiagnostics.remove(nodeId);
        cycleDiagnostics.remove(nodeId);
    }

    // Typedefs
    Type.NameType nameType(Node defNode) {
        return nameTypes.computeIfAbsent(defNode.id, _ -> new Type.NameType(defNode.value, defNode));
    }

    Type.NameType freshNameType(Node defNode) {
        Type.NameType fresh = new Type.NameType(defNode.value, defNode);
        nameTypes.put(defNode.id, fresh);
        return fresh;
    }

    // Definitions
    Type defType(ScopedDefn defn) {
        return defTypes.getOrDefault(defn.defNode.id, Type.UNKNOWN);
    }

    void putDefType(ScopedDefn defn, Type type) {
        defTypes.put(defn.defNode.id, (type == null) ? (Type.UNKNOWN) : (type));
    }

    // Statement cache
    ExpressionTypeChecker.Attrs exprAttrs(int nodeId) {
        return exprAttrs.get(nodeId);
    }

    List<TypeDiagnostic> statementDiagnostics(int nodeId) {
        return exprDiagnostics.get(nodeId);
    }

    void putStatement(int nodeId, ExpressionTypeChecker.Attrs attrs, List<TypeDiagnostic> diags) {
        exprAttrs.put(nodeId, attrs);
        exprDiagnostics.put(nodeId, diags.isEmpty() ? List.of() : diags);
    }

    void evict(int nodeId) {
        exprAttrs.remove(nodeId);
        exprDiagnostics.remove(nodeId);
    }

    // Diagnostics
    void clearDiagnostics(int nodeId) {
        defDiagnostics.remove(nodeId);
    }

    void clearCycleDiagnostics() {
        cycleDiagnostics.clear();
    }

    void clearCycleDiagnostics(int nodeId) {
        cycleDiagnostics.remove(nodeId);
    }

    void putCycleDiagnostic(int nodeId, TypeDiagnostic diagnostic) {
        cycleDiagnostics.put(nodeId, diagnostic);
    }

    void setDiagnostics(int nodeId, List<TypeDiagnostic> diags) {
        if (diags.isEmpty()) {
            defDiagnostics.remove(nodeId);
        } else {
            defDiagnostics.put(nodeId, diags);
        }
    }

    List<TypeDiagnostic> diagnostics() {
        List<TypeDiagnostic> diags = new ArrayList<>();
        for (List<TypeDiagnostic> diagList : defDiagnostics.values()) {
            diags.addAll(diagList);
        }
        diags.addAll(cycleDiagnostics.values());

        return diags;
    }

}
