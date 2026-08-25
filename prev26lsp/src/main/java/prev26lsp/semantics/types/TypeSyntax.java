package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;

import java.util.ArrayList;
import java.util.List;

/**
 * An abstract view of a declared type, computed on demand from the concrete parse tree.
 */
sealed interface TypeSyntax {

    /** The parse tree node this view was built from. */
    Node node();

    /** T — a named type reference; id is the identifier to resolve. */
    record Named(Node node, Node id) implements TypeSyntax {}

    /**
     * int, char, bool, void
     */
    record Atom(Node node, Type.AtomType type) implements TypeSyntax {}

    /**^T */
    record Pointer(Node node, Node base) implements TypeSyntax {}

    /** [n]T. size is the INTCONST node; the sign is folded into negated. */
    record Array(Node node, Node size, boolean negated, Node elem) implements TypeSyntax {}

    /** A struct in parens or a union in curly braces, first field included. */
    record Rec(Node node, boolean isUnion, List<Field> fields) implements TypeSyntax {}

    /** (: T, ... : R) */
    record Fun(Node node, List<Node> params, Node result) implements TypeSyntax {}

    /** A shape the grammar cannot produce: an incomplete or error-recovered subtree. */
    record Malformed(Node node) implements TypeSyntax {}

    record Field(Node id, Node type) {}

    /** Accepts a type, type_non_id or type_paren node. */
    static TypeSyntax of(Node node) {
        return switch (node.symbol) {
            case TYPE -> {
                // type -> ID | type_non_id
                Node child = TypeNav.childOrNull(node, Symbol.ID);
                if (child != null) yield new Named(node, child);

                Node nonId = TypeNav.childOrNull(node, Symbol.TYPE_NON_ID);
                yield (nonId == null) ? new Malformed(node) : nonId(nonId);
            }

            case TYPE_NON_ID -> nonId(node);
            case TYPE_PAREN -> paren(node);
            default -> new Malformed(node);
        };
    }

    private static TypeSyntax nonId(Node node) {
        Node first = TypeNav.firstChildOrNull(node);
        if (first == null) return new Malformed(node);

        return switch (first.symbol) {
            case INT -> new Atom(node, Type.INT);
            case CHAR -> new Atom(node, Type.CHAR);
            case BOOL -> new Atom(node, Type.BOOL);
            case VOID -> new Atom(node, Type.VOID);

            case CARET -> {
                // CARET type
                Node base = TypeNav.childOrNull(node, Symbol.TYPE);
                yield (base == null) ? new Malformed(node) : new Pointer(node, base);
            }

            case LSQUARE -> {
                // LSQUARE int_pfx_expr INTCONST RSQUARE type
                Node size = TypeNav.childOrNull(node, Symbol.INTCONST);
                Node elem = TypeNav.lastChildOrNull(node, Symbol.TYPE);

                yield (size == null || elem == null)
                        ? new Malformed(node)
                        : new Array(node, size, isNegated(TypeNav.childOrNull(node, Symbol.INT_PFX_EXPR)), elem);
            }

            case LCURLY -> {
                // LCURLY fields RCURLY
                Node fields = TypeNav.childOrNull(node, Symbol.FIELDS);
                yield new Rec(node, true, (fields == null) ? List.of() : fieldsOf(fields));
            }

            case LPAREN -> {
                // LPAREN type_paren
                Node paren = TypeNav.childOrNull(node, Symbol.TYPE_PAREN);
                yield (paren == null) ? new Malformed(node) : paren(paren);
            }

            default -> new Malformed(node);
        };
    }

    private static TypeSyntax paren(Node node) {
        Node first = TypeNav.firstChildOrNull(node);
        if (first == null) return new Malformed(node);

        return switch (first.symbol) {
            case ID -> {
                // type_paren -> ID type_paren_id; type_paren_id -> RPAREN | COLON type pfields RPAREN
                Node suffix = TypeNav.childOrNull(node, Symbol.TYPE_PAREN_ID);
                if (suffix == null) yield new Malformed(node);

                // No colon: this was just a parenthesised type name, not a struct.
                if (TypeNav.childOrNull(suffix, Symbol.COLON) == null) yield new Named(node, first);

                // The lookahead split pulled the first field's ID up into type_paren and left its
                // type in the suffix. Put the pair back at the head of the field list.
                Node firstType = TypeNav.childOrNull(suffix, Symbol.TYPE);
                if (firstType == null) yield new Malformed(node);

                List<Field> fields = new ArrayList<>();
                fields.add(new Field(first, firstType));

                Node rest = TypeNav.childOrNull(suffix, Symbol.PFIELDS);
                if (rest != null) fields.addAll(fieldsOf(rest));

                yield new Rec(node, false, fields);
            }

            case COLON -> {
                // COLON etypes COLON type RPAREN
                Node result = TypeNav.lastChildOrNull(node, Symbol.TYPE);
                if (result == null) yield new Malformed(node);

                Node etypes = TypeNav.childOrNull(node, Symbol.ETYPES);
                yield new Fun(node, typeList(etypes), result);
            }

            case TYPE_NON_ID -> nonId(first); // type_non_id RPAREN
            default -> new Malformed(node);
        };
    }

    /** etypes -> types | ε, types -> type (COMMA type)*, tail flattened. */
    private static List<Node> typeList(Node etypes) {
        Node types = (etypes == null) ? null : TypeNav.childOrNull(etypes, Symbol.TYPES);

        return (types == null) ? List.of() : TypeNav.getChildren(types, Symbol.TYPE);
    }

    /** fields and pfields, whose tails are flattened into the holder. */
    private static List<Field> fieldsOf(Node holder) {
        List<Field> fields = new ArrayList<>();

        for (Node fieldNode : TypeNav.getChildren(holder, Symbol.FIELD)) {
            // field -> ID COLON type
            Node id = TypeNav.childOrNull(fieldNode, Symbol.ID);
            Node type = TypeNav.childOrNull(fieldNode, Symbol.TYPE);

            // An incomplete field carries no name/type pair to check; the parser already
            // reported the missing token.
            if (id != null && type != null) {
                fields.add(new Field(id, type));
            }
        }

        return fields;
    }

    private static boolean isNegated(Node signNode) {
        if (signNode == null || signNode.isEpsilon()) return false;

        Node sign = TypeNav.firstChildOrNull(signNode);
        return sign != null && sign.symbol == Symbol.MINUS;
    }

}
