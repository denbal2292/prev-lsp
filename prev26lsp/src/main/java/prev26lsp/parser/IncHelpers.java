package prev26lsp.parser;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import prev26lsp.util.IntSearch;

import java.util.ArrayList;
import java.util.List;

public class IncHelpers {

    private IncHelpers() {}

    public record NodeLocation(Node node, List<Node> path, int nodeStart, IntList indices) {}
    public record TerminalLocation(Node terminal, List<Node> path, IntList indices) {}
    public record SymbolParent(Symbol symbol, Node parent) {}
    public record DivideResult(List<Node> newReversedPath, List<SymbolParent> xSequence, List<Node> dropped) {}
    public record ReparsePlan(int lexStart, int lexEnd, NodeLocation leftKeep, NodeLocation rightCut) {}

    public static NodeLocation findTerminalEndingAtLineStart(Node tree, int lineStart) {
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

    public static NodeLocation findFirstTerminalAtLineStart(Node tree, int lineStart) {
        int width = tree.getWidth();

        if (width == 0 || lineStart >= width) {
            return null;
        }

        return findTerminalAtOffset(tree, Math.max(lineStart, 0));
    }

    public static ReparsePlan planReparse(Node tree, int oldDocumentLength,  int documentDeltaLength,
                                          int editLineStartOld, int nextLineStartOld) {
        if (tree.getWidth() == 0) {
            return new ReparsePlan(0, oldDocumentLength + documentDeltaLength, null, null);
        }

        // LEFT: Start of the edited line
        NodeLocation leftKeep = findTerminalEndingAtLineStart(tree, editLineStartOld);
        // Lex from beginning of the next token
        int lexStart = (leftKeep == null) ? (0) : (leftKeep.nodeStart() + leftKeep.node().getWidth());

        // RIGHT: Start of the line after the edited line INCLUDING the first terminal after it
        NodeLocation through = findFirstTerminalAtLineStart(tree, nextLineStartOld);
        int lexEnd;
        NodeLocation rightCut;

        if (through == null) {
            lexEnd = oldDocumentLength + documentDeltaLength;
            rightCut = null;
        } else {
            lexEnd = through.nodeStart() + through.node().getWidth() + documentDeltaLength;
            rightCut = through;
        }


        return new ReparsePlan(lexStart, lexEnd, leftKeep, rightCut);
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

            // Find first child with position > offset
            int childIdx = IntSearch.upperBound(prefixWidths, offset);

            if (childIdx >= prefixWidths.size()) {
                throw new IllegalArgumentException(String.format("Offset %d is out of bounds in node %s", offset, node));
            }

            // We skipped over all childIdx - 1 children
            int prefixWidth = (childIdx > 0) ? (prefixWidths.getInt(childIdx - 1)) : (0);
            offset -= prefixWidth;

            node = node.getChildren().get(childIdx);
            path.add(node);
            indices.add(childIdx);
        }

        // Node starts at the queried offset - offset inside it
        return new NodeLocation(node, path, nodeStart - offset, indices);
    }

    public static List<Node> collectSubtrees(NodeLocation rightCut) {
        if (rightCut == null) {
            return List.of();
        }

        return divideCollect(rightCut.path(), rightCut.indices());
    }

    public static List<Node> collectSubtrees(Node parseTree, int idx) {
        // Edit stops at the start of the string -> just return children of the root
        if (idx == -1) {
            return parseTree.getChildren().stream().
                    filter(c -> c.getWidth() > 0 && c.kind != Node.Kind.ERR_MISSING)
                    .toList();
        }

        NodeLocation loc = findTerminalAtOffset(parseTree, idx);
        return divideCollect(loc.path, loc.indices);
    }

    public static DivideResult removeSubtrees(NodeLocation keep) {
        return divideRemove(keep.path(), keep.indices());
    }

    public static DivideResult removeSubtrees(Node parseTree, int idx) {
        // Edit starts at the start of the string -> create new root
        if (idx == -1) {
            List<Node> dropped = parseTree.getChildren();
            Node rootCopy = parseTree.copyPreservingId(new ArrayList<>());
            List<Node> reversedPath = List.of(rootCopy);
            List<SymbolParent> xSequence = List.of(new SymbolParent(Symbol.DEFINITIONS, rootCopy));

            return new DivideResult(reversedPath, xSequence, dropped);
        }

        NodeLocation loc = findTerminalAtOffset(parseTree, idx);
        return divideRemove(loc.path, loc.indices);
    }


    public static List<Node> divideCollect(List<Node> path, IntList indices) {
        List<Node> collectedSubtrees = new ArrayList<>();

        for (int i = indices.size() - 1; i >= 0; i--) {
            Node parent = path.get(i);
            List<Node> children = parent.getChildren();
            int nodeIdx = indices.getInt(i);

            // Collect all siblings to the right of the current node
            for (int j = nodeIdx + 1; j < children.size(); j++) {
                Node child = children.get(j);

                // Skip missing and epsilon nodes
                if (child.getWidth() == 0 || child.kind == Node.Kind.ERR_MISSING) {
                    continue;
                }

                collectedSubtrees.add(child);
            }
        }

        return collectedSubtrees;
    }

    public static DivideResult divideRemove(List<Node> path, IntList indices) {
        List<SymbolParent> xSequence = new ArrayList<>();
        List<Node> dropped = new ArrayList<>();
        List<Node> newReversedPath = new ArrayList<>();

        // Leaf is unchanged -> don't copy it
        Node leaf = path.getLast();
        newReversedPath.add(leaf);

        // Walk up the tree (from the leaf backwards)
        Node current = leaf;
        for (int d = indices.size() - 1; d >= 0; d--) {
            Node parent = path.get(d);
            int idx = indices.getInt(d);

            // Left nodes are left alone, right nodes must be reparsed
            List<Node> children = parent.getChildren();

            // new children = children[:idx] + current
            List<Node> newChildren = new ArrayList<>(idx + 1);
            newChildren.addAll(children.subList(0, idx));
            newChildren.add(current);

            // Create a copy of the parent since it will be mutated
            Node parentCopy = parent.copyPreservingId(newChildren);

            ParseTable.FlatListInfo flatListInfo = ParseTable.FLAT_LIST.get(parent.symbol);

            if (flatListInfo != null) {
                Symbol lastExpectedSymbol = null;
                for (int k = idx; k >= 0; k--) {
                    Node child = children.get(k);
                    if (child.kind != Node.Kind.ERR_UNEXPECTED) {
                        lastExpectedSymbol = child.symbol;
                        break;
                    }
                }

                if (lastExpectedSymbol != null && flatListInfo.separators().contains(lastExpectedSymbol)) {
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

            // children[idx + 1:] are dropped
            dropped.addAll(children.subList(idx  + 1, children.size()));
            newReversedPath.add(current);
        }

        // Current should now point to the new root
        return new DivideResult(newReversedPath, xSequence, dropped);
    }

    public static TerminalLocation rightmostTerminal(Node node) {
        List<Node> path = new ArrayList<>();
        IntList indices = new IntArrayList();
        path.add(node);

        while (!node.isTerminal()) {
            List<Node> children = node.getChildren();
            boolean descended = false;

            // Look for the rightmost child with width > 0
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

            // If no children with width > 0
            if (!descended) {
                throw new RuntimeException(String.format("No terminal found in subtree rooted at %s", node));
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

            int nextStart = loc.nodeStart() + loc.node().getWidth();

            return new NodeLocation(termLoc.terminal(), termPath, nextStart, termIndices);
        }

        return null;
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
                throw new RuntimeException(String.format("No terminal found in subtree rooted at %s", node));
            }
        }

        return new TerminalLocation(node, path, indices);
    }

}
