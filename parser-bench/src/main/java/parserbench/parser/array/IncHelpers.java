package parserbench.parser.array;

import parserbench.parser.ParseTable;
import parserbench.parser.Symbol;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import parserbench.util.IntSearch;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class IncHelpers {

    private IncHelpers() {}

    public record NodeLocation(Node node, List<Node> path, int nodeStart, IntList indices) {
        public int nodeEnd() {
            return nodeStart + node.getWidth();
        }
    }
    public record TerminalLocation(Node terminal, List<Node> path, IntList indices) {}
    public record SymbolParent(Symbol symbol, Node parent) {}
    public record DivideResult(List<Node> newReversedPath, List<SymbolParent> xSequence) {}
    public record ReparsePlan(int lexStart, int lexEnd, NodeLocation leftKeep, NodeLocation rightCut) {}

    public static ReparsePlan planReparse(Node tree, int oldDocumentLength, int documentDeltaLength, int editLineStartOld, int nextLineStartOld) {
        if (tree.getWidth() == 0) {
            return new ReparsePlan(0, oldDocumentLength + documentDeltaLength, null, null);
        }

        // LEFT: Start of the edited line
        NodeLocation leftKeep = findTerminalEndingAtLineStart(tree, editLineStartOld);
        // Lex from beginning of the next token
        int lexStart = (leftKeep == null) ? (0) : (leftKeep.nodeEnd());

        // RIGHT: Start of the line after the edited line INCLUDING the first terminal after it
        NodeLocation through = findFirstTerminalAtLineStart(tree, nextLineStartOld);
        int lexEnd;
        NodeLocation rightCut;

        if (through == null) {
            lexEnd = oldDocumentLength + documentDeltaLength;
            rightCut = null;
        } else {
            lexEnd = through.nodeEnd() + documentDeltaLength;
            rightCut = through;
        }

        return new ReparsePlan(lexStart, lexEnd, leftKeep, rightCut);
    }

    private static NodeLocation findTerminalEndingAtLineStart(Node tree, int lineStart) {
        int width = tree.getWidth();

        if (lineStart <= 0 || width == 0) {
            return null; // Nothing can be kept
        }

        // Edit lies in EOF-owned trivia
        if (lineStart >= width) {
            TerminalLocation rm = rightmostTerminal(tree);

            return new NodeLocation(rm.terminal(), rm.path(), width - rm.terminal().getWidth(), rm.indices());
        }

        return findPreviousTerminal(findTerminalAtOffset(tree, lineStart));
    }

    private static NodeLocation findFirstTerminalAtLineStart(Node tree, int lineStart) {
        int width = tree.getWidth();

        if (width == 0 || lineStart >= width) {
            return null;
        }

        return findTerminalAtOffset(tree, Math.max(lineStart, 0));
    }

    public static NodeLocation findTerminalAtOffset(Node node, int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative: " + offset);
        }

        final int nodeStart = offset;
        List<Node> path = new ArrayList<>();
        IntList indices = new IntArrayList();
        path.add(node);

        while (!node.isTerminal()) {
            IntList prefixWidths = node.getPrefixWidths();
            int childIdx;
            int prefixWidth;

            if (prefixWidths.isEmpty()) {
                // Narrow nodes omit prefix sums.
                childIdx = 0;
                prefixWidth = 0;
                List<Node> children = node.getChildren();
                while (childIdx < children.size() && offset >= prefixWidth + children.get(childIdx).getWidth()) {
                    prefixWidth += children.get(childIdx).getWidth();
                    childIdx++;
                }
            } else {
                childIdx = IntSearch.upperBound(prefixWidths, offset);
                prefixWidth = (childIdx > 0) ? prefixWidths.getInt(childIdx - 1) : 0;
            }

            if (childIdx >= node.getChildren().size()) {
                throw new IllegalArgumentException(String.format("Offset %d is out of bounds in node %s", offset, node));
            }

            offset -= prefixWidth;

            node = node.getChildren().get(childIdx);
            path.add(node);
            indices.add(childIdx);
        }

        // Node starts at the queried offset - offset inside it
        return new NodeLocation(node, path, nodeStart - offset, indices);
    }

    static void collectSubtrees(Deque<Node> inputStack, NodeLocation rightCut) {
        if (rightCut == null) return;
        divideCollect(inputStack, rightCut.path(), rightCut.indices());
    }

    static DivideResult removeSubtrees(NodeLocation keep) {
        return divideRemove(keep.path(), keep.indices());
    }

    /**
     * A child can start a reusable tail only if it re-derives the tail production on its own.
     */
    private static boolean startsTail(Node child, ParseTable.FlatListInfo info) {
        return child.kind == Node.Kind.NORMAL
                && (info.separators().isEmpty()
                ? child.symbol == info.afterSeparator()
                : info.separators().contains(child.symbol));
    }

    /**
     * Index in suffix where a reusable tail begins, or -1 if none does.
     */
    private static int tailStart(List<Node> suffix, ParseTable.FlatListInfo info) {
        for (int i = 0; i < suffix.size(); i++) {
            if (startsTail(suffix.get(i), info)) return i;
        }
        return -1;
    }

    // Edit starts at the start of the document -> create a new, empty root.
    static DivideResult removeSubtreesFromStart(Node parseTree) {
        Node rootCopy = parseTree.copyPreservingId(new ArrayList<>());
        List<Node> reversedPath = List.of(rootCopy);
        List<SymbolParent> xSequence = List.of(new SymbolParent(Symbol.DEFINITIONS, rootCopy));

        return new DivideResult(reversedPath, xSequence);
    }

    /** Pushes reusable tail nodes or ordinary right siblings in replay order. */
    static void divideCollect(Deque<Node> inputStack, List<Node> path, IntList indices) {
        for (int i = 0; i < indices.size(); i++) {
            Node parent = path.get(i);
            int nodeIdx = indices.getInt(i);
            List<Node> children = parent.getChildren();

            if (nodeIdx + 1 >= children.size()) continue;

            List<Node> suffix = children.subList(nodeIdx + 1, children.size());
            ParseTable.FlatListInfo flat = ParseTable.FLAT_LIST.get(parent.symbol);
            int tailStart = (flat == null) ? -1 : tailStart(suffix, flat);

            if (tailStart >= 0) {
                inputStack.push(new TailNode(
                        flat.tail(), suffix.subList(tailStart, suffix.size()),
                        suffixWidth(parent, nodeIdx + tailStart), flat));
            }

            // Anything ahead of the tail is replayed singly so the parser re-derives it.
            List<Node> replayed = (tailStart >= 0) ? suffix.subList(0, tailStart) : suffix;
            for (Node child : replayed.reversed()) {
                if (child.getWidth() > 0) {
                    inputStack.push(child.copyAsKind(Node.Kind.NORMAL));
                }
            }
        }
    }

    /**
     * Lazily expands a tail whose symbol did not match at replay time.
     */
    static void expandTail(Deque<Node> inputStack, TailNode tail) {
        List<Node> children = tail.tail;

        int next = -1;
        for (int i = 1; i < children.size(); i++) {
            if (startsTail(children.get(i), tail.info)) {
                next = i;
                break;
            }
        }

        int replayEnd = (next >= 0) ? next : children.size();
        if (next >= 0) {
            int replayedWidth = 0;
            for (int i = 0; i < replayEnd; i++) {
                replayedWidth += children.get(i).getWidth();
            }
            inputStack.push(new TailNode(
                    tail.symbol, children.subList(next, children.size()),
                    tail.getWidth() - replayedWidth, tail.info));
        }

        for (int i = replayEnd - 1; i >= 0; i--) {
            Node child = children.get(i);
            if (child.getWidth() > 0) {
                inputStack.push(child.copyAsKind(Node.Kind.NORMAL));
            }
        }
    }

    private static Symbol lastExpectedSymbol(List<Node> children, int from) {
        for (int i = from; i >= 0; i--) {
            Node child = children.get(i);
            if (child.kind != Node.Kind.ERR_UNEXPECTED) return child.symbol;
        }
        return null;
    }

    /** Total width of a node's children after nodeIdx. */
    private static int suffixWidth(Node parent, int nodeIdx) {
        IntList prefixWidths = parent.getPrefixWidths();
        if (!prefixWidths.isEmpty()) {
            return prefixWidths.getLast() - prefixWidths.getInt(nodeIdx);
        }

        int width = 0;
        List<Node> children = parent.getChildren();
        for (int i = nodeIdx + 1; i < children.size(); i++) {
            width += children.get(i).getWidth();
        }
        return width;
    }

    static DivideResult divideRemove(List<Node> path, IntList indices) {
        List<SymbolParent> xSequence = new ArrayList<>();
        List<Node> newReversedPath = new ArrayList<>();

        // Leaf is unchanged -> don't copy it
        Node leaf = path.getLast();
        newReversedPath.add(leaf);

        // Walk up the tree (from the leaf backwards)
        Node current = leaf;
        for (int d = indices.size() - 1; d >= 0; d--) {
            Node parent = path.get(d);
            int idx = indices.getInt(d);

            List<Node> children = parent.getChildren();
            List<Node> newChildren = new ArrayList<>(idx + 1);
            newChildren.addAll(children.subList(0, idx));
            newChildren.add(current);
            Node parentCopy = parent.copyPreservingId(newChildren);

            ParseTable.FlatListInfo flatListInfo = ParseTable.FLAT_LIST.get(parent.symbol);

            if (flatListInfo != null) {
                Symbol last = lastExpectedSymbol(children, idx);

                if (last != null && flatListInfo.separators().contains(last)) {
                    xSequence.add(new SymbolParent(flatListInfo.afterSeparator(), parentCopy));
                }

                xSequence.add(new SymbolParent(flatListInfo.tail(), parentCopy));
            } else {
                for (int k = idx + 1; k < children.size(); k++) {
                    Node child = children.get(k);

                    if (child.kind != Node.Kind.ERR_UNEXPECTED) {
                        // Mark symbols that need to be parsed along with their new parent
                        xSequence.add(new SymbolParent(child.symbol, parentCopy));
                    }
                }
            }

            current = parentCopy;
            newReversedPath.add(current);
        }

        // Current should now point to the new root
        return new DivideResult(newReversedPath, xSequence);
    }

    public static TerminalLocation rightmostTerminal(Node node) {
        List<Node> path = new ArrayList<>();
        IntList indices = new IntArrayList();
        path.add(node);

        while (!node.isTerminal()) {
            List<Node> children = node.getChildren();
            boolean descended = false;
            for (int i = children.size() - 1; i >= 0; i--) {
                Node child = children.get(i);
                if (child.getWidth() > 0) {
                    node = child;
                    path.add(node);
                    indices.add(i);
                    descended = true;
                    break;
                }
            }
            if (!descended) {
                throw new IllegalStateException(String.format("No terminal found in subtree rooted at %s", node));
            }
        }

        return new TerminalLocation(node, path, indices);
    }

    public static TerminalLocation leftmostTerminal(Node node) {
        List<Node> path = new ArrayList<>();
        IntList indices = new IntArrayList();
        path.add(node);

        while (!node.isTerminal()) {
            List<Node> children = node.getChildren();
            boolean descended = false;
            for (int i = 0; i < children.size(); i++) {
                Node child = children.get(i);
                if (child.getWidth() > 0) {
                    node = child;
                    path.add(node);
                    indices.add(i);
                    descended = true;
                    break;
                }
            }
            if (!descended) {
                throw new IllegalStateException(String.format("No terminal found in subtree rooted at %s", node));
            }
        }

        return new TerminalLocation(node, path, indices);
    }

    public static NodeLocation findPreviousTerminal(NodeLocation loc) {
        List<Node> path = loc.path();
        IntList indices = loc.indices();

        // Walk up the path and indices in reverse
        for (int depth = indices.size() - 1; depth >= 0; depth--) {
            Node parent = path.get(depth);
            int childIdx = indices.getInt(depth);
            List<Node> parentChildren = parent.getChildren();

            // Find the nearest left sibling with width > 0
            int leftIdx = -1;
            Node leftSibling = null;

            for (int i = childIdx - 1; i >= 0; i--) {
                leftSibling = parentChildren.get(i);

                if (leftSibling.getWidth() > 0) {
                    leftIdx = i;
                    break;
                }
            }

            // If we didn't find such a sibling, go up a level
            if (leftIdx == -1) {
                continue;
            }

            // Find the rightmost terminal in the left sibling
            TerminalLocation termLoc = rightmostTerminal(leftSibling);

            // Splice the two paths together
            List<Node> termPath = new ArrayList<>(depth + 1 + termLoc.path().size());
            IntList termIndices = new IntArrayList(depth + 1 + termLoc.indices().size());

            // The path up to and including the parent is the same
            termPath.addAll(path.subList(0, depth + 1));
            termPath.addAll(termLoc.path());

            // The indices are the same except the last index
            termIndices.addAll(indices.subList(0, depth));
            termIndices.add(leftIdx);
            termIndices.addAll(termLoc.indices());

            // prev.end() == loc.start() (only zero-width nodes are between)
            int prevStart = loc.nodeStart() - termLoc.terminal().getWidth();

            return new NodeLocation(termLoc.terminal(), termPath, prevStart, termIndices);
        }

        // We didn't find a previous terminal
        return null;
    }

    public static NodeLocation findNextTerminal(NodeLocation loc) {
        List<Node> path = loc.path();
        IntList indices = loc.indices();

        for (int depth = indices.size() - 1; depth >= 0; depth--) {
            Node parent = path.get(depth);
            int childIdx = indices.getInt(depth);
            List<Node> parentChildren = parent.getChildren();

            int rightIdx = -1;
            Node rightSibling = null;

            for (int i = childIdx + 1; i < parentChildren.size(); i++) {
                rightSibling = parentChildren.get(i);

                if (rightSibling.getWidth() > 0) {
                    rightIdx = i;
                    break;
                }
            }

            if (rightIdx == -1) {
                continue;
            }

            TerminalLocation termLoc = leftmostTerminal(rightSibling);

            List<Node> termPath = new ArrayList<>(depth + 1 + termLoc.path().size());
            IntList termIndices = new IntArrayList(depth + 1 + termLoc.indices().size());

            termPath.addAll(path.subList(0, depth + 1));
            termPath.addAll(termLoc.path());

            termIndices.addAll(indices.subList(0, depth));
            termIndices.add(rightIdx);
            termIndices.addAll(termLoc.indices());

            int nextStart = loc.nodeEnd();

            return new NodeLocation(termLoc.terminal(), termPath, nextStart, termIndices);
        }

        return null;
    }

}
