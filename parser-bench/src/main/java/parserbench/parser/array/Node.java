package parserbench.parser.array;

import parserbench.parser.Symbol;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import parserbench.lexer.Token;

import java.util.ArrayList;
import java.util.List;

public class Node {

    static final int PREFIX_WIDTH_THRESHOLD = 16;

    // TODO: This is unsafe when consumed from multiple threads
    private static int idCounter = 0;

    public enum Kind {
        NORMAL,
        ERR_MISSING,
        ERR_UNEXPECTED
    }

    // Node identity
    public final int id;
    public final Symbol symbol;
    public final Kind kind;

    // Offset bookkeeping
    private int width;

    // Diagnostics
    public final String lexError; // null if none
    public final Symbol errorContext; // for better error messages
    private int firstTaintedChild = -1; // index of first tainted child or -1 if none

    // Terminal only
    public final String value;      // null for nonterminals
    // Nonterminals only
    private final List<Node> children;
    private final IntList prefixWidths;

    @Override
    public String toString() {
        String description;

        if (this.isTerminal()) {
            description = String.format("%s (%s, w=%d)", this.symbol.name(), this.value, this.getWidth());
        } else if (this.isEpsilon()) {
            description = String.format("%s (ε)", this.symbol.name());
        } else {
            description = String.format("%s (c=%d, w=%d)", this.symbol.name(), this.getChildren().size(), this.getWidth());
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

        String childPrefix = prefix + ((isLast) ? ("    ") : ("|   "));
        List<Node> children = node.getChildren();

        for (int i = 0; i < children.size(); i++) {
            printTree(children.get(i), sb, childPrefix, i == children.size() - 1);
        }
    }

    public String printTree() {
        StringBuilder sb = new StringBuilder();
        Node.printTree(this, sb, "", true);

        return sb.toString();
    }

    public static Node fromToken(Token token) {
        return new Node(token.type, token.value, token.errorMessage,
                token.getWidth(), Kind.NORMAL);
    }

    public static Node createMissing(Symbol symbol) {
        if (!symbol.isTerminal()) {
            return new Node(symbol, Kind.ERR_MISSING);
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
        this.children = List.of();
        this.prefixWidths = IntList.of();
        this.lexError = lexError;
        this.errorContext = null;
    }

    // Nonterminal constructor (for the parser to fill in later)
    public Node(Symbol symbol, Kind kind) {
        // assert !symbol.isTerminal();
        this.id = idCounter++;
        this.symbol = symbol;
        this.value = null;
        this.kind = kind;
        this.width = 0;
        this.children = new ArrayList<>();
        this.prefixWidths = new IntArrayList();
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
        this.prefixWidths = src.prefixWidths; // shared
        this.firstTaintedChild = src.firstTaintedChild;
        this.width = src.width;
        this.lexError = src.lexError;
        this.errorContext = (kind == Kind.ERR_UNEXPECTED) ? errContext : null;
    }

    // Id-preserving copy with new children (used for new spine creation)
    private Node(Node src, List<Node> children) {
        assert !src.isTerminal();

        this.id = src.id;
        this.symbol = src.symbol;
        this.kind = src.kind;
        this.value = src.value;
        this.width = 0;
        this.children = children;
        this.lexError = src.lexError;
        this.errorContext = src.errorContext;

        // The last child is the rebuilt spine child and may not be finalized yet.
        // Reuse every prefix sum before it; updateFromChildren resumes there.
        int reusedChildren = Math.clamp(
                children.size() - 1, 0, src.prefixWidths.size());
        this.prefixWidths = new IntArrayList(children.size());
        this.prefixWidths.addAll(src.prefixWidths.subList(0, reusedChildren));
        this.width = reusedChildren == 0
                ? 0
                : this.prefixWidths.getLast();

        this.firstTaintedChild = src.firstTaintedChild >= 0
                && src.firstTaintedChild < reusedChildren
                ? src.firstTaintedChild
                : -1;
    }

    // Synthetic tail node: a window onto an existing child list, never grown.
    protected Node(Symbol symbol, List<Node> childrenView, int width) {
        this.id = idCounter++;
        this.symbol = symbol;
        this.kind = Kind.NORMAL;
        this.value = null;
        this.width = width;
        this.children = childrenView;
        this.prefixWidths = IntList.of();
        this.lexError = null;
        this.errorContext = null;
    }

    public Node copyPreservingId(List<Node> newChildren) {
        return new Node(this, newChildren);
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
        return this.kind != Kind.NORMAL || this.lexError != null || this.firstTaintedChild >= 0;
    }

    public boolean hasNoChildren() {
        return this.children.isEmpty();
    }

    public boolean isEpsilon() {
        return !this.isTerminal() && this.hasNoChildren();
    }

    public List<Node> getChildren() {
        return this.children;
    }

    IntList getPrefixWidths() {
        return this.prefixWidths;
    }

    public int getWidth() {
        return this.width;
    }

    // Don't update any information yet as the child may not be fully built
    void addChild(Node node) {
        if (node instanceof TailNode tail) {
            this.children.addAll(tail.tail);
        } else {
            this.children.add(node);
        }
    }

    void updateFromChildren() {
        if (this.isTerminal()) return;

        boolean indexWidths = this.children.size() > PREFIX_WIDTH_THRESHOLD;
        int start;

        if (indexWidths) {
            start = this.prefixWidths.size();
            this.width = start == 0 ? 0 : this.prefixWidths.getLast();
        } else {
            // A linear lookup is cheaper for narrow nodes, so they carry no index.
            this.prefixWidths.clear();
            this.width = 0;
            this.firstTaintedChild = -1;
            start = 0;
        }

        for (int i = start; i < this.children.size(); i++) {
            Node child = this.children.get(i);
            this.width += child.getWidth();

            if (indexWidths) this.prefixWidths.add(this.width);
            if (this.firstTaintedChild < 0 && child.isTainted()) {
                this.firstTaintedChild = i;
            }
        }
    }

}
