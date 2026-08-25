package parserbench.parser.treap;

import parserbench.parser.Symbol;

import parserbench.lexer.Token;

import java.util.ArrayList;
import java.util.List;

public class Node {

    // TODO: This is unsafe when consumed from multiple threads
    private static int idCounter = 0;
    private static final TreapChildren EMPTY_CHILDREN = new TreapChildren();

    public enum Kind {
        NORMAL,
        ERR_MISSING,
        ERR_UNEXPECTED
    }

    // Node identity
    public final int id;
    public final Symbol symbol;
    public final Kind kind;

    // Diagnostics
    public final String lexError; // null if none
    public final Symbol errorContext; // for better error messages

    // Terminal only
    public final String value;      // null for nonterminals
    private final int width;

    // Nonterminals only
    private TreapChildren children;
    private List<Node> pending;

    @Override
    public String toString() {
        String description;

        if (this.isTerminal()) {
            description = String.format("%s (%s, w=%d)", this.symbol.name(), this.value, this.getWidth());
        } else if (this.isEpsilon()) {
            description = String.format("%s (ε)", this.symbol.name());
        } else {
            description = String.format("%s (c=%d, w=%d)", this.symbol.name(), this.children.size(), this.getWidth());
        }

        if (this.kind != Kind.NORMAL) {
            description += " [" + this.kind.name() + "]";
        }

        return description;
    }

    private static void printTree(Node node, StringBuilder sb, String prefix, boolean isLast) {
        sb.append(prefix)
                .append(isLast ? "\\__ " : "|-- ")
                .append(node)
                .append('\n');

        int i = 0;
        int childCount = node.children.size();
        String childPrefix = prefix + ((isLast) ? ("    ") : ("|   "));

        for (Node child : node.children) {
            printTree(child, sb, childPrefix, ++i == childCount);
        }
    }

    public String printTree() {
        StringBuilder sb = new StringBuilder();
        Node.printTree(this, sb, "", true);

        return sb.toString();
    }

    public static Node fromToken(Token token) {
        return new Node(token.type, token.value, token.errorMessage, token.getWidth(), Kind.NORMAL);
    }

    public static Node createMissing(Symbol symbol) {
        if (!symbol.isTerminal()) {
            return new Node(symbol, Kind.ERR_MISSING, EMPTY_CHILDREN);
        }

        return new Node(symbol, null, null, 0, Kind.ERR_MISSING);
    }

    // Terminal constructor
    private Node(Symbol symbol, String value, String lexError,
                 int width, Kind kind) {
        assert symbol.isTerminal();
        this.id = idCounter++;
        this.symbol = symbol;
        this.value = value;
        this.kind = kind;
        this.width = width;

        this.children = EMPTY_CHILDREN;
        this.pending = List.of();
        this.lexError = lexError;
        this.errorContext = null;
    }

    // Nonterminal constructor (for the parser to fill in later)
    public Node(Symbol symbol, Kind kind) {
        this(symbol, kind, new TreapChildren());
    }

    Node(Symbol symbol, Kind kind, TreapChildren children) {
        this.id = idCounter++;
        this.symbol = symbol;
        this.value = null;
        this.kind = kind;
        this.width = 0;
        this.children = children;
        this.pending = new ArrayList<>();
        this.lexError = null;
        this.errorContext = null;
    }

    // Used for copying the node
    private Node(Node src, Kind kind, Symbol errContext) {
        this.id = idCounter++;
        this.symbol = src.symbol;
        this.kind = kind;
        this.value = src.value;
        this.children = src.children; // share children
        this.pending = src.isTerminal() ? List.of() : new ArrayList<>();
        this.width = src.width;
        this.lexError = src.lexError;
        this.errorContext = (kind == Kind.ERR_UNEXPECTED) ? errContext : null;
    }

    // Id-preserving copy with new children (used for new spine creation)
    private Node(Node src, TreapChildren prefix, Node spineChild) {
        assert !src.isTerminal();

        this.id = src.id;
        this.symbol = src.symbol;
        this.kind = src.kind;
        this.value = src.value;
        this.width = 0;
        this.children = prefix;
        this.pending = new ArrayList<>();

        if (spineChild != null) {
            this.pending.add(spineChild);
        }

        this.lexError = src.lexError;
        this.errorContext = src.errorContext;
    }

    Node copyPreservingId(TreapChildren newChildren, Node spineChild) {
        return new Node(this, newChildren, spineChild);
    }

    public Node copyPreservingId(List<Node> newChildren) {
        return new Node(this, TreapChildren.from(newChildren), null);
    }

    public Node copyAsKind(Kind kind, Symbol context) {
        if (kind == this.kind) {
            return this;
        }

        return new Node(this, kind, context);
    }

    public static int peekNextId() {
        return Node.idCounter;
    }

    public Node copyAsKind(Kind kind) {
        return this.copyAsKind(kind, null);
    }

    public boolean isTerminal() {
        return this.symbol.isTerminal();
    }

    public boolean isErrorNode() {
        return this.kind != Kind.NORMAL || this.lexError != null;
    }

    public boolean isTainted() {
        return this.kind != Kind.NORMAL || this.lexError != null || children.firstTainted() >= 0;
    }

    public boolean hasNoChildren() {
        return this.children.size() == 0;
    }

    public boolean isEpsilon() {
        return !this.isTerminal() && this.hasNoChildren();
    }

    TreapChildren children() {
        return this.children;
    }

    public List<Node> getChildren() {
        return this.children.asList();
    }

    public int getWidth() {
        if (isTerminal()) {
            return this.width;
        }

        return this.children.width();
    }

    // Don't update any information yet as the child may not be fully built
    void addChild(Node node) {
        // Add to pending, build the sequence only when children are finalized
        this.pending.add(node);
    }

    void updateFromChildren() {
        if (this.isTerminal()) return;

        for (Node node : this.pending) {
            // Bulk attach tails
            this.children = (node instanceof TailNode tail)
                    ? this.children.concat(tail.tail)
                    : this.children.append(node);
        }

        this.pending.clear();
    }

}
