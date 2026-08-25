package prev26lsp.document;

import prev26lsp.model.DocumentEdit;
import prev26lsp.model.Position;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public class AvlLineTreeDocumentBuffer implements DocumentBuffer {

    private static final class Node {
        StringBuilder line;
        Node left, right;
        int height;
        int subtreeLines;
        int subtreeLength; // excluding new lines

        Node(StringBuilder line) {
            this.line = line;
            this.left = null;
            this.right = null;
            this.subtreeLines = 1;
            this.subtreeLength = line.length();
        }

        void update() {
            this.subtreeLines = 1 + subtreeLines(this.left) + subtreeLines(this.right);
            this.height = 1 + Math.max(subtreeHeight(this.left), subtreeHeight(this.right));
            this.subtreeLength = this.line.length() + subtreeLength(this.left) + subtreeLength(this.right);
        }

        int balance() {
            return subtreeHeight(this.right) - subtreeHeight(this.left);
        }
    }

    private Node root;

    public AvlLineTreeDocumentBuffer(String initialText) {
        for (String line : initialText.split("\n", -1)) {
            root = insertAt(root, subtreeLines(root), new StringBuilder(line));
        }
    }

    @Override
    public int convertToOffset(Position position) {
        int line = position.getLine();
        int column = position.getCharacter();

        Node node = root;
        int sum = 0;

        while (node != null) {
            int leftLines = subtreeLines(node.left);
            int leftLength = subtreeLength(node.left);

            if (line == leftLines) {
                // \n == 1 byte
                return sum + leftLength + leftLines + column;
            } else if (line < leftLines) {
                node = node.left;
            } else {
                sum += leftLength + leftLines + node.line.length() + 1;
                line -= leftLines + 1;
                node = node.right;
            }
        }

        throw new IndexOutOfBoundsException();
    }

    @Override
    public Position convertToPosition(int offset) {
        int lineCount = 0;
        Node node = root;

        while (node != null) {
            int leftLines = subtreeLines(node.left);
            int leftLength = subtreeLength(node.left);
            int leftWidth = leftLength + leftLines;

            if (offset < leftWidth) {
                node = node.left;
            } else {
                int thisLineLength = node.line.length();
                int lineOffset = offset - leftWidth;

                if (lineOffset <= thisLineLength) {
                    return new Position(lineCount + leftLines, lineOffset);
                } else {
                    lineCount += leftLines + 1;
                    offset = lineOffset - thisLineLength - 1;
                    node = node.right;
                }
            }
        }

        throw new IndexOutOfBoundsException();
    }

    @Override
    public int getDocumentLength() {
        if (root == null) {
            return 0;
        }

        return root.subtreeLength + Math.max(0, root.subtreeLines - 1);
    }

    @Override
    public int getLineCount() {
        return subtreeLines(this.root);
    }

    @Override
    public String getFullText() {
        StringBuilder text = new StringBuilder(subtreeLength(this.root));
        collectInOrder(root, text, true);
        return text.toString();
    }

    private static boolean collectInOrder(Node node, StringBuilder out, boolean first) {
        // In-order traversal of the tree.
        if (node == null) {
            return first;
        }

        first = collectInOrder(node.left, out, first);

        // Append '\n' between lines
        if (!first) {
            out.append('\n');
        }
        out.append(node.line);

        return collectInOrder(node.right, out, false);
    }

    @Override
    public String read(int offset, int length) {
        StringBuilder result = new StringBuilder(length);
        int remaining = length;

        Deque<Node> stack = new ArrayDeque<>();
        int columnNumber = descendToOffset(root, offset, stack);

        while (remaining > 0 && !stack.isEmpty()) {
            Node node = stack.peek();
            StringBuilder line = node.line;
            int available = line.length() - columnNumber;

            if (available >= remaining) {
                // If line is longer than what we take
                result.append(line, columnNumber, columnNumber + remaining);
                remaining = 0;
            } else {
                // Take whole line
                result.append(line, columnNumber, line.length());
                remaining -= available;

                if (remaining > 0) {
                    result.append('\n');
                    remaining--;
                }

                columnNumber = 0;
                advance(stack);
            }
        }

        return result.toString();
    }

    private static int descendToOffset(Node node, int offset, Deque<Node> stack) {
        while (node != null) {
            stack.push(node);
            int leftWidth = subtreeLength(node.left) + subtreeLines(node.left);

            if (offset < leftWidth) {
                node = node.left;
            } else {
                int thisLineWidth = node.line.length();
                int lineOffset = offset - leftWidth;

                if (lineOffset <= thisLineWidth) {
                    return lineOffset;
                }

                stack.pop(); // Parent node is not the successor
                offset = lineOffset - thisLineWidth - 1;
                node = node.right;
            }
        }

        throw new IndexOutOfBoundsException();
    }

    private static void advance(Deque<Node> stack) {
        Node node = stack.pop();

        if (node.right != null) {
            node = node.right;

            while (node != null) {
                stack.push(node);
                node = node.left;
            }
        }
    }

    @Override
    public void applyEdit(DocumentEdit edit) {
        Position start = edit.getStart();
        Position end = edit.getEnd();

        int startLine = start.getLine();
        int startCol = start.getCharacter();
        int endLine = end.getLine();
        int endCol = end.getCharacter();

        String newText = edit.getNewText();
        String[] newTextLines = newText.split("\n", -1);

        if (startLine == endLine) {
            // Edit inside a single line
            if (newTextLines.length == 1) {
                // Single line not getting broken up
                root = mutateAt(root, startLine, sb -> sb.replace(startCol, endCol, newText));
            } else {
                // Single line getting broken into multiple
                // Capture suffix (to be appended to the end of the last line)
                String suffix = lineAt(startLine).substring(endCol);

                // Replace the suffix with the content of the first line
                root = mutateAt(root, startLine, sb -> sb.replace(startCol, sb.length(), newTextLines[0]));

                // Insert all middle lines after startLine
                int insertIndex = startLine + 1;
                for (int i = 1; i < newTextLines.length - 1; i++) {
                    root = insertAt(root, insertIndex++, new StringBuilder(newTextLines[i]));
                }

                // Last line = last insertion + suffix
                StringBuilder lastLine = new StringBuilder(newTextLines[newTextLines.length - 1].length() + suffix.length());
                lastLine.append(newTextLines[newTextLines.length - 1]).append(suffix);

                // Insert last line
                root = insertAt(root, insertIndex, lastLine);
            }
        } else {
            // Edit inside multiple lines
            String suffix = lineAt(endLine).substring(endCol);

            if (newTextLines.length == 1) {
                // Multiple lines replaced by one (prefix of first line + newText + suffix of last line)
                root = mutateAt(root, startLine, sb ->
                        sb.replace(startCol, sb.length(), newText + suffix)
                );

                // Delete all (startLine, endLine] (always delete at position startLine + 1)
                int deleteCount = endLine - startLine;
                for (int i = 0; i < deleteCount; i++) {
                    root = deleteAt(root, startLine + 1);
                }
            } else {
                // Multiple lines replaced by multiple lines
                // First line = content up to prefix + first new line
                root = mutateAt(
                        root, startLine, sb -> sb.replace(startCol, sb.length(), newTextLines[0])
                );

                // Last line = last new line + suffix
                root = mutateAt(
                        root, endLine, sb -> {
                            sb.setLength(0); // Clear the sb
                            sb.append(newTextLines[newTextLines.length - 1]);
                            sb.append(suffix);
                        }
                );

                int oldMiddleCount = endLine - startLine - 1; // [startLine, endLine] - 2
                int newMiddleCount = newTextLines.length - 2;
                int sharedMiddleCount = Math.min(oldMiddleCount, newMiddleCount);

                // Try to reuse middle nodes
                for (int i = 0; i < sharedMiddleCount; i++) {
                    int lineIndex = startLine + i + 1;
                    String replacement = newTextLines[i + 1];

                    root = mutateAt(root, lineIndex, sb -> {
                        sb.setLength(0);
                        sb.append(replacement);
                    });
                }

                // Delete extra old middle lines (after shared count)
                for (int i = sharedMiddleCount; i < oldMiddleCount; i++) {
                    root = deleteAt(root, startLine + 1 + sharedMiddleCount);
                }

                // Insert new middle lines
                int insertIndex = startLine + 1 + sharedMiddleCount;

                for (int i = sharedMiddleCount; i < newMiddleCount; i++) {
                    root = insertAt(root, insertIndex++, new StringBuilder(newTextLines[i + 1]));
                }
            }
        }
    }

    // Insert before index (index == line count means append)
    private static Node insertAt(Node node, int index, StringBuilder line) {
        if (node == null) {
            return new Node(line);
        }

        int leftLines = subtreeLines(node.left);

        if (index <= leftLines) {
            node.left = insertAt(node.left, index, line);
        } else {
            // skip current node and all to the left
            node.right = insertAt(node.right, index - leftLines - 1, line);
        }

        node.update();
        return rebalance(node);
    }

    private static Node deleteAt(Node node, int index) {
        int leftLines = subtreeLines(node.left);

        if (index < leftLines) {
            node.left = deleteAt(node.left, index);
        } else if (index > leftLines) {
            node.right = deleteAt(node.right, index - leftLines - 1);
        } else {
            // We found the node
            // 1. No left child -> replace it with the right one
            if (node.left == null) {
                return node.right;
            }

            // 2. No right child -> replace it with the left one
            if (node.right == null) {
                return node.left;
            }

            // 3. Both children are present -> replace it with the leftmost node in right subtree (successor)
            Node succ = leftmost(node.right);

            // Move the value of the successor to the node we want to delete
            node.line = succ.line;

            // Delete the successor (0th node in right subtree)
            node.right = deleteAt(node.right, 0);
        }

        // At each step rebalance
        node.update();
        return rebalance(node);
    }

    private static Node leftmost(Node node) {
        Node result = node;
        while (result.left != null) {
            result = result.left;
        }
        return result;
    }

    private StringBuilder lineAt(int index) {
        Node node = root;

        while (node != null) {
            int leftCount = subtreeLines(node.left);

            if (index == leftCount) {
                return node.line;
            } else if (index < leftCount) {
                node = node.left;
            } else {
                node = node.right;
                // Skip current + nodes to the left
                index -= leftCount + 1;
            }
        }

        throw new IndexOutOfBoundsException("Index: " + index + " out of range");
    }

    private static Node mutateAt(Node node, int index, Consumer<StringBuilder> action) {
        int leftLines = subtreeLines(node.left);

        if (index == leftLines) {
            action.accept(node.line);
        } else if (index < leftLines) {
            node.left = mutateAt(node.left, index, action);
        } else {
            node.right = mutateAt(node.right, index - leftLines - 1, action);
        }

        node.update();
        return rebalance(node);
    }

    private static Node rebalance(Node node) {
        int balanceFactor = node.balance();

        if (balanceFactor == 2) {
            // right subtree is too high
            if (node.right.balance() < 0) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);

        } else if (balanceFactor == -2) {
            // left subtree is too high
            if (node.left.balance() > 0) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }

        return node;
    }

    private static Node rotateLeft(Node node) {
        Node right = node.right;
        node.right = right.left;
        right.left = node;

        node.update();
        right.update();
        return right;
    }

    private static Node rotateRight(Node node) {
        Node left = node.left;
        node.left = left.right;
        left.right = node;

        node.update();
        left.update();
        return left;
    }

    private static int subtreeHeight(Node node) {
        return (node != null) ?  node.height : 0;
    }

    private static int subtreeLines(Node node) {
        return (node != null) ? (node.subtreeLines) : 0;
    }

    private static int subtreeLength(Node node) {
        return (node != null) ? (node.subtreeLength) : 0;
    }

}
