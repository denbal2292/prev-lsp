package prev26lsp.parser;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import prev26lsp.lexer.Token;

import java.util.ArrayList;
import java.util.List;

public class Node {

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
    public final int leadingWidth;  // 0 for nonterminals

    // Content span, aggregated bottom-up (see updateFromChildren). Lets a node
    // report the source it spells without its surrounding trivia, in O(1).
    // contentStart == -1 marks "no content yet" (empty/epsilon subtree).
    private int contentStart = -1;  // leading trivia of the subtree's first terminal
    private int trailingWidth;      // trailing trivia of the subtree's last terminal

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
                token.getWidth(), token.getLeadingTriviaWidth(), Kind.NORMAL);
    }

    public static Node createMissing(Symbol symbol) {
        if (!symbol.isTerminal()) {
            return new Node(symbol, Kind.ERR_MISSING);
        }

        return new Node(symbol, null, null, 0, 0, Kind.ERR_MISSING);
    }

    // Terminal constructor
    private Node(Symbol symbol, String value, String lexError,
                 int width, int leadingWidth, Kind kind) {
        assert symbol.isTerminal();
        this.id = idCounter++;
        this.symbol = symbol;
        this.value = value;
        this.kind = kind;
        this.width = width;
        this.leadingWidth = leadingWidth;
        // A missing terminal (value == null, width 0) has no content.
        this.contentStart = (value != null) ? leadingWidth : -1;
        this.trailingWidth = (value != null) ? (width - leadingWidth - value.length()) : 0;
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
        this.leadingWidth = 0;
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
        this.leadingWidth = src.leadingWidth;
        this.contentStart = src.contentStart;
        this.trailingWidth = src.trailingWidth;
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
        this.leadingWidth = src.leadingWidth;
        this.width = 0;
        this.children = children;
        this.lexError = src.lexError;
        this.errorContext = src.errorContext;

        // reuse prefix sums for all but the last token (make sure it's a valid index)
        int reuseWidth = Math.clamp(children.size() - 1, 0, src.prefixWidths.size());
        this.prefixWidths = new IntArrayList(children.size());
        this.prefixWidths.addAll(src.prefixWidths.subList(0, reuseWidth));

        // The reused prefix shares src's first `reuseWidth` children (identical
        // node objects). Keep src's contentStart only if it falls within that
        // prefix; otherwise reset to -1 so the resumed loop sets it from a later
        // child. trailingWidth is re-set by the resumed loop for each non-empty child.
        int reusedCharWidth = (reuseWidth > 0) ? src.prefixWidths.getInt(reuseWidth - 1) : 0;
        this.contentStart = (src.contentStart >= 0 && src.contentStart < reusedCharWidth) ? src.contentStart : -1;
        this.trailingWidth = src.trailingWidth;

        // Look if any of the first children was tainted
        this.firstTaintedChild = (src.firstTaintedChild >= 0 && src.firstTaintedChild < reuseWidth) ? src.firstTaintedChild : -1;
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

    public IntList getPrefixWidths() { return this.prefixWidths; }

    public int getWidth() {
        return this.width;
    }

    /**
     * Offset from this node's start (which includes leading trivia) to the first
     * non-trivia character of its content — i.e. the leading trivia of the
     * subtree's first terminal. Aggregated bottom-up, so this is O(1).
     */
    public int contentStart() {
        // -1 marks an empty/epsilon subtree: report an empty span at the start.
        return (this.contentStart < 0) ? 0 : this.contentStart;
    }

    /**
     * Width of this node's content, excluding leading trivia of its first terminal
     * and trailing trivia of its last terminal. Highlighting {@code [start + contentStart(),
     * start + contentStart() + contentWidth()]} covers exactly the source text a
     * node spells, with no surrounding whitespace or comments.
     */
    public int contentWidth() {
        return (this.contentStart < 0) ? 0 : (this.width - this.contentStart - this.trailingWidth);
    }

    // Don't update any information yet as the child may not be fully built
    void addChild(Node node) {
        this.children.add(node);
    }

    void updateFromChildren() {
        if (this.isTerminal()) return;

        // Start updating only from newly added children
        int start = this.prefixWidths.size();
        int totalWidth = (this.prefixWidths.isEmpty()) ? (0) : (this.prefixWidths.getLast());

        // Get total width of node by summing widths of its children
        for (int i = start; i < this.children.size(); i++) {
            Node child = this.children.get(i);
            int offsetBeforeChild = totalWidth;
            totalWidth += child.getWidth();

            // Aggregate the content span: leading trivia comes from the first
            // non-empty child, trailing trivia from the last non-empty child.
            if (child.getWidth() > 0) {
                if (this.contentStart < 0) {
                    this.contentStart = offsetBeforeChild + child.contentStart;
                }
                this.trailingWidth = child.trailingWidth;
            }

            this.prefixWidths.add(totalWidth);
            if (this.firstTaintedChild < 0 && child.isTainted()) {
                this.firstTaintedChild = i;
            }
        }

        this.width = totalWidth;
    }

    /**
     * Hands back the capacity the two child lists over-allocated, once the parser has attached the
     * last child. Both grow to their default capacity on the first add — 10 slots for the
     * {@link ArrayList}, 16 for the {@link IntArrayList} — which for a chain node holding a single
     * child is 26 slots for 1 element. Across a 1 MB file that is 6.5 slots allocated per child in
     * each list, about 46 MB of empty slots.
     *
     * <p>Trimming only changes capacity, so it is safe for the lists a copy shares with its source.
     * A later incremental parse that appends to this node regrows the list from its exact size,
     * which costs one copy and then resumes the usual growth.
     */
    void trimToChildren() {
        if (this.children instanceof ArrayList<Node> childList) {
            childList.trimToSize();
        }

        if (this.prefixWidths instanceof IntArrayList widths) {
            widths.trim();
        }
    }

}
