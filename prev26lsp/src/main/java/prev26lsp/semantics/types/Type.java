package prev26lsp.semantics.types;

import prev26lsp.parser.Node;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("NullableProblems")
public interface Type {

    enum Atom {
        INT,
        CHAR,
        BOOL,
        VOID
    }

    AtomType INT = new AtomType(Atom.INT);
    AtomType CHAR = new AtomType(Atom.CHAR);
    AtomType BOOL = new AtomType(Atom.BOOL);
    AtomType VOID = new AtomType(Atom.VOID);
    PtrType NIL = new PtrType(VOID);
    PtrType STR = new PtrType(CHAR);
    UnknownType UNKNOWN = new UnknownType();

    default Type actualType() {
        return this;
    }

    default Type actualType(Set<Type> seen) {
        return actualType();
    }

    default boolean isUnknown() {
        return actualType() == UNKNOWN;
    }

    default boolean isSimple() {
        Type actual = actualType();

        return switch (actual) {
          case AtomType atom -> atom.atom() != Atom.VOID;
          case PtrType _, FunType _ -> true;
          default -> false;
        };
    }

    default boolean isVoid() {
        return actualType() == VOID;
    }

    default boolean isSimpleOrVoid() {
        Type actual = actualType();
        return actual == VOID || isSimple();
    }

    /**
     * Language-level type equivalence: UNKNOWN is equivalent to everything
     */
    default boolean isEquivalentTo(Type other) {
        return compare(this, other, false, null);
    }

    /**
     * Strict structural equality, used for change detection between recomputes: UNKNOWN is equal only to UNKNOWN
     */
    static boolean sameShape(Type a, Type b) {
        return compare(a, b, true, null);
    }

    /**
     * The shared walk behind both comparisons. actualType() unwraps chains of names, and visited
     * pairs guard the recursion so recursive types terminate.
     */
    private static boolean compare(Type a, Type b, boolean strict, Set<TypePair> seen) {
        if (a == b) return true;

        if (a instanceof NameType || b instanceof NameType) {
            // A named type whose definition is currently broken unwraps to UNKNOWN, which would
            // make it compare equal to "no type at all" and hide the difference from change
            // detection. Being named is part of the shape.
            if (strict && (a == UNKNOWN || b == UNKNOWN)) return false;

            if (seen == null) seen = new HashSet<>();
            if (!seen.add(new TypePair(a, b))) return true;

            return compare(a.actualType(), b.actualType(), strict, seen);
        }

        if (!strict && (a == UNKNOWN || b == UNKNOWN)) return true;

        return switch (a) {
            case AtomType(Atom atom) -> b instanceof AtomType(Atom other) && atom == other;
            case PtrType(Type base) -> b instanceof PtrType(Type otherBase)
                    && compare(base, otherBase, strict, seen);
            case ArrType(Type elem, long numElems) -> b instanceof ArrType(Type otherElem, long otherNum)
                    && numElems == otherNum && compare(elem, otherElem, strict, seen);
            case RecType rec -> b instanceof RecType other
                    && rec.isUnion == other.isUnion
                    && (!strict || rec.compNames.equals(other.compNames))
                    && compareAll(rec.compTypes, other.compTypes, strict, seen);
            case FunType(List<Type> params, Type ret) -> b instanceof FunType(List<Type> otherParams, Type otherRet)
                    && compare(ret, otherRet, strict, seen) && compareAll(params, otherParams, strict, seen);
            default -> false; // UnknownType: only equal to itself, caught by a == b
        };
    }

    private static boolean compareAll(List<Type> types1, List<Type> types2, boolean strict, Set<TypePair> seen) {
        if (types1.size() != types2.size()) return false;

        for (int i = 0; i < types1.size(); i++) {
            if (!compare(types1.get(i), types2.get(i), strict, seen)) return false;
        }

        return true;
    }

    record TypePair(Type a, Type b) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TypePair other = (TypePair) o;
            return a == other.a && b == other.b;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(a) * 31 + System.identityHashCode(b);
        }
    }

    class UnknownType implements Type {
        private UnknownType() {}

        @Override
        public String toString() {
            return "<unknown>";
        }
    }

    record AtomType(Atom atom) implements Type {
        @Override
        public String toString() {
            return atom.name().toLowerCase();
        }
    }

    record PtrType(Type baseType) implements Type {
        @Override
        public String toString() {
            return "^" + baseType;
        }
    }

    record ArrType(Type elemType, long numElems) implements Type {
        @Override
        public String toString() {
            return "[" + numElems + "]" + elemType;
        }
    }

    record RecType(boolean isUnion, List<String> compNames, List<Type> compTypes) implements Type {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < compNames.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(compNames.get(i)).append(": ").append(compTypes.get(i));
            }
            String comps = sb.toString();

            return (isUnion) ? ("{" + comps + "}") : ("(" + comps + ")");
        }
    }

    record FunType(List<Type> paramTypes, Type returnType) implements Type {
        @Override
        public String toString() {
            return "(:" + String.join(", ", paramTypes.stream().map(Object::toString).toList()) + ": " + returnType + ")";
        }
    }

    class NameType implements Type {
        private final String name;
        private final Node definitionNode;
        private Type type;

        public NameType(String name, Node definitionNode) {
            this.name = name;
            this.definitionNode = definitionNode;
            this.type = UNKNOWN;
        }

        public String name() {
            return this.name;
        }

        public Node definitionNode() {
            return this.definitionNode;
        }

        public Type type() {
            return this.type;
        }

        public void setType(Type type) {
            this.type = (type == null) ? (UNKNOWN) : (type);
        }

        public Type actualType(Set<Type> seen) {
            if (!seen.add(this)) {
                return UNKNOWN;
            }

            return this.type.actualType(seen);
        }

        public Type actualType() {
            return actualType(new HashSet<>());
        }

        @Override
        public String toString() {
            return this.name;
        }

    }

}
