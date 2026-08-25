package prev26lsp.semantics.types;

import prev26lsp.semantics.names.ScopedDefn;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

class CycleDetector {

    private CycleDetector() {}

    static void detect(Collection<ScopedDefn> defns, TypeStore store) {
        for (ScopedDefn defn : defns) {
            if (defn.kind != ScopedDefn.Kind.TYPE) continue;

            store.clearCycleDiagnostics(defn.defNode.id);
            Type.NameType type = store.nameType(defn.defNode);

            if (isCircular(type, new HashSet<>())) {
                store.putCycleDiagnostic(defn.defNode.id,
                        new TypeDiagnostic(defn.defNode, "Circular type definition '" + defn.name + "'"));
            }
        }

    }

    private static boolean isCircular(Type type, Set<Type> visited) {
        return switch (type) {

            case Type.NameType nt -> {
                // Check if it was already seen
                if (!visited.add(nt)) {
                    yield true;
                }

                // Peel back one layer and check again
                if (isCircular(nt.type(), visited)) {
                    yield true;
                }

                // backtrack
                visited.remove(nt);

                yield false;
            }

            case Type.RecType rec -> {
                // If at least one component mentions a visited type, it is circular
                for (Type compType : rec.compTypes()) {
                    if (isCircular(compType, visited)) {
                        yield true;
                    }
                }

                yield false;
            }

            case Type.ArrType arr -> isCircular(arr.elemType(), visited);

            // Other types break it
            default -> false;
        };
    }

}
