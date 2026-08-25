package prev26lsp.parser;

import java.util.*;

public class Parser {

    enum Action {
        PARSE,
        FILL_WIDTH
    }

    record ParseItem(Action action, Symbol symbol, Node node, Node parent) {
        static ParseItem parse(Symbol s, Node parent) {
            return new ParseItem(Action.PARSE, s, null, parent);
        }

        static ParseItem finalize(Node n) {
            return new ParseItem(Action.FILL_WIDTH, null, n, null);
        }
    }

    public record ParseResult(Node root, ParseDelta delta) {}

    public static Node incrementalParse(Node oldTree, IncHelpers.NodeLocation leftCut, IncHelpers.NodeLocation rightCut, List<Node> changeStr) {
        List<Node> zSubtrees = IncHelpers.collectSubtrees(rightCut);
        IncHelpers.DivideResult divideResult;

        if (leftCut != null) {
            divideResult = IncHelpers.removeSubtrees(leftCut);
        } else {
            divideResult = IncHelpers.removeSubtrees(oldTree, -1);
        }

        return incrementalParse(divideResult, zSubtrees, changeStr);
    }

    public static ParseResult incrementalParseWithDelta(Node oldTree, IncHelpers.NodeLocation leftCut, IncHelpers.NodeLocation rightCut, List<Node> changeStr) {
        List<Node> zSubtrees = IncHelpers.collectSubtrees(rightCut);
        IncHelpers.DivideResult divideResult;

        if (leftCut != null) {
            divideResult = IncHelpers.removeSubtrees(leftCut);
        } else {
            divideResult = IncHelpers.removeSubtrees(oldTree, -1);
        }

        // All nodes with >= ids are newly allocated (dividing mints no ids:
        // spine copies preserve them, so the watermark is valid tree-wide)
        int createdIdFloor = (changeStr.isEmpty()) ? (Node.peekNextId()) : (changeStr.getFirst().id);

        // Exclude the leading terminal since it's not relocated
        List<Node> path = divideResult.newReversedPath();
        int first = path.getFirst().isTerminal() ? (1) : (0);
        List<Node> spine = List.copyOf(path.subList(first, path.size()));

        ParseDelta parseDelta = new ParseDelta(spine, List.copyOf(divideResult.dropped()), createdIdFloor);

        Node newTree = incrementalParse(divideResult, zSubtrees, changeStr);
        return new ParseResult(newTree, parseDelta);
    }

    private static Node incrementalParse(Node oldTree, int changeStart, int changeEnd, List<Node> changeStr) {
        // No-op edit on a non-empty tree
        if (changeStart == changeEnd && changeStr.isEmpty() && !oldTree.hasNoChildren()) {
            return oldTree;
        }

        // Divide
        List<Node> zSubtrees = IncHelpers.collectSubtrees(oldTree, changeEnd);
        IncHelpers.DivideResult divideResult = IncHelpers.removeSubtrees(oldTree, changeStart);

        return incrementalParse(divideResult, zSubtrees, changeStr);
    }

    public static Node parse(List<Node> tokens) {
        Node node = new Node(Symbol.START_SYMBOL, Node.Kind.NORMAL);
        return incrementalParse(node, -1, -1, tokens);
    }

    private static void attach(Node node, Node parent) {
        // EOF has no parent
        if (node.symbol == Symbol.EOF) return;

        parent.addChild(node);
    }

    private static Node incrementalParse(IncHelpers.DivideResult divideResult, List<Node> zSubtrees, List<Node> changeStr) {
        // Build stacks
        Node root = divideResult.newReversedPath().getLast();

        Deque<ParseItem> parseStack = new ArrayDeque<>();
        Deque<Node>      inputStack = new ArrayDeque<>();

        parseStack.push(ParseItem.parse(Symbol.EOF, null));
        inputStack.push(new Node(Symbol.EOF, Node.Kind.NORMAL)); // TODO: This is a bit hacky

        for (Node node: divideResult.newReversedPath().reversed()) {
            parseStack.push(ParseItem.finalize(node));
        }

        for (IncHelpers.SymbolParent symbolParent : divideResult.xSequence().reversed()) {
            Symbol symbol = symbolParent.symbol();
            Node parent = symbolParent.parent();

            parseStack.push(ParseItem.parse(symbol, parent));
        }

        for (Node subtree: zSubtrees.reversed()) {
            Node normalized = subtree.copyAsKind(Node.Kind.NORMAL);
            inputStack.push(normalized);
        }

        // 1c) Push the change string
        for (Node node: changeStr.reversed()) {
            inputStack.push(node);
        }

        // 2) Main loop
        while (!parseStack.isEmpty()) {
            ParseItem X = parseStack.peek();
            Node      Y = inputStack.peek();
            assert Y != null;

            // Logger.info(String.format("Top of parse stack: %s, top of input stack: %s%n", X.action(), Y.toString()));

            // Update content after all children were fully parsed
            if (X.action() == Action.FILL_WIDTH) {
                X.node.updateFromChildren();
                X.node.trimToChildren();
                parseStack.pop();
                continue;
            }

            // MATCH
            if (X.symbol == Y.symbol) {
                attach(Y, X.parent);

                parseStack.pop();
                inputStack.pop();
                continue;
            }

            // Input has trailing tokens after parse completed (Y != EOF)
            if (X.symbol == Symbol.EOF) {
                if (!Y.isTerminal()) {
                    inputStack.pop();
                    // Undo all nonterminals
                    for (Node child : Y.getChildren().reversed()) {
                        if (child.getWidth() > 0) {
                            inputStack.push(child);
                        }
                    }
                } else {
                    // Attach directly to root since EOF has no parent
                    Node err = Y.copyAsKind(Node.Kind.ERR_UNEXPECTED);
                    attach(err, root);
                    inputStack.pop();
                }
                continue;
            }

            // Get production index
            byte productionIdx = X.symbol.isTerminal()
                    ? ParseTable.NO_ENTRY
                    : ParseTable.PARSE_TABLE[X.symbol.ordinal()][Y.symbol.ordinal()];

            // EXPAND: We got a valid production
            if (productionIdx >= 0) {
                parseStack.pop();
                Symbol[] prodSymbols = ParseTable.PRODUCTIONS[productionIdx];

                // Fold right-recursive list tails into the parent list node.
                // The LL table still drives parsing, but *_PRIME nodes are not
                // materialized in the CST.
                if (ParseTable.FLATTEN_SYMBOLS.contains(X.symbol)) {
                    for (int i = prodSymbols.length - 1; i >= 0; i--) {
                        Symbol symbol = prodSymbols[i];
                        parseStack.push(ParseItem.parse(symbol, X.parent));
                    }
                    continue;
                }

                // Create a new node for the expected nonterminal
                Node nonterminal = new Node(X.symbol, Node.Kind.NORMAL);
                attach(nonterminal, X.parent);

                // Push finalize first (it will be processed last)
                parseStack.push(ParseItem.finalize(nonterminal));

                // Push them in reverse order with new nonterminal as the parent
                for (int i = prodSymbols.length - 1; i >= 0; i--) {
                    Symbol symbol = prodSymbols[i];
                    parseStack.push(ParseItem.parse(symbol, nonterminal));
                }
                continue;
            }

            // UNDO: A nonterminal didn't match: expand Z-subtree and retry
            if (!Y.isTerminal()) {
                inputStack.pop();

                // TODO: This repeats?
                for (Node child : Y.getChildren().reversed()) {
                    if (child.getWidth() > 0) {
                        Node normalized = child.copyAsKind(Node.Kind.NORMAL);
                        inputStack.push(normalized);
                    }
                }
                continue;
            }

            // INSERTION: expected terminal X is missing
            if (X.symbol.isTerminal()) {
                Node err = Node.createMissing(X.symbol);
                attach(err, X.parent);

                parseStack.pop();
                continue;
            }

            // Y is now a terminal that doesn't match a nonterminal X

            // ABANDON: we got a sync symbol -> stop parsing X
            if (productionIdx == ParseTable.ABANDON || Y.symbol == Symbol.EOF) {
                Node err = Node.createMissing(X.symbol);
                attach(err, X.parent);

                // Delete X, keep trying to match Y
                parseStack.pop();
                continue;
            }

            // DELETION: Y is unexpected, skip it but keep trying to parse X
            Node err = Y.copyAsKind(Node.Kind.ERR_UNEXPECTED, X.symbol);
            attach(err, X.parent);

            // X is retried with the next token
            inputStack.pop();
        }

        return root;
    }

}
