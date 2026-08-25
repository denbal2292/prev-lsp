package parserbench.parser.treap;

import java.util.ArrayDeque;
import java.util.AbstractList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

final class TreapChildren implements Iterable<Node> {

    record Location(int index, int widthBefore, Node node) {}

    private static final class T {
        final Node value;
        final T left, right;
        final int priority;
        final int size, width, firstTainted;

        T(T left, Node value, T right, int priority) {
            this.left = left;
            this.value = value;
            this.right = right;
            this.priority = priority;

            int valueWidth = value.getWidth();
            this.size = size(left) + 1 + size(right);
            this.width = width(left) + valueWidth + width(right);

            if (firstTainted(left) >= 0) {
                this.firstTainted = firstTainted(left);
            } else if (value.isTainted()) {
                this.firstTainted = size(left);
            } else if (firstTainted(right) >= 0) {
                this.firstTainted = size(left) + 1 + firstTainted(right);
            } else {
                this.firstTainted = -1;
            }
        }

        T(Node value) {
            this(null, value, null, ThreadLocalRandom.current().nextInt());
        }
    }

    private T root;

    TreapChildren() {
        this.root = null;
    }

    static TreapChildren from(List<Node> children) {
        TreapChildren result = new TreapChildren();
        for (Node child : children) result.append(child);
        return result;
    }

    private TreapChildren(T root) {
        this.root = root;
    }

    int size() {
        return size(root);
    }

    int width() {
        return width(root);
    }

    int firstTainted() {
        return firstTainted(root);
    }

    Node get(int index) {
        T curr = root;

        if (curr == null || index < 0 || index >= curr.size) {
            throw new IndexOutOfBoundsException();
        }

        while (true) {
            int leftSize = size(curr.left);

            if (index < leftSize) {
                curr = curr.left;
            } else if (index == leftSize) {
                return curr.value;
            } else {
                curr = curr.right;
                index -= leftSize + 1;
            }
        }
    }

    Location locate(int offset) {
        int index = 0, widthBefore = 0;
        T curr = root;

        while (curr != null) {
            int leftWidth = width(curr.left);

            if (offset < leftWidth) {
                curr = curr.left;
            } else {
                int valueWidth = curr.value.getWidth();
                int totalWidth = leftWidth + valueWidth;

                if (offset < totalWidth) {
                    return new Location(index + size(curr.left), widthBefore + leftWidth, curr.value);
                }

                offset -= totalWidth;
                widthBefore += totalWidth;
                index += size(curr.left) + 1;

                curr = curr.right;
            }
        }

        return new Location(index, widthBefore, null);
    }

    TreapChildren append(Node child) {
        root = merge(root, new T(child));
        return this;
    }

    TreapChildren concat(TreapChildren other) {
        return new TreapChildren(merge(this.root, other.root));
    }

    TreapChildren take(int count) {
        return new TreapChildren(take(root, count));
    }

    TreapChildren drop(int count) {
        return new TreapChildren(drop(root, count));
    }
    
    private static T merge(T left, T right) {
        if (left == null) {
            return right;
        }

        if (right == null) {
            return left;
        }

        if (left.priority > right.priority) {
            return new T(left.left, left.value, merge(left.right, right), left.priority);
        } else {
            return new T(merge(left, right.left), right.value, right.right, right.priority);
        }
    }

    private static T take(T t, int i) {
        if (t == null || i <= 0) {
            return null;
        }

        if (i >= t.size) {
            return t;
        }

        int leftSize = size(t.left);
        if (i <= leftSize) {
            // Take a part of left subtree
            return take(t.left, i);
        }

        // Take whole left subtree and the remainder in right
        return new T(t.left, t.value, take(t.right, i - leftSize - 1), t.priority);
    }

    private static T drop(T t, int i) {
        if (t == null || i <= 0) {
            return t;
        }

        if (i >= t.size) {
            return null;
        }

        int leftSize = size(t.left);
        if (i > leftSize) {
            // Drop entire left subtree, recursively drop the remainder from the right
            return drop(t.right, i - leftSize - 1);
        }

        // Right node remains, part of left is dropped
        return new T(drop(t.left, i), t.value, t.right, t.priority);
    }

    private static int size(T t) {
        return (t == null) ? (0) : (t.size);
    }

    private static int width(T t) {
        return (t == null) ? (0) : (t.width);
    }

    private static int firstTainted(T t) {
        return (t == null) ? (-1) : (t.firstTainted);
    }

    private static final class InOrderIterator implements Iterator<Node> {
        private final Deque<T> stack = new ArrayDeque<>();
        private final boolean reverse;

        InOrderIterator(T root, boolean reverse) {
            this.reverse = reverse;
            pushSpine(root);
        }

        private void pushSpine(T t) {
            while (t != null) {
                stack.push(t);
                t = (reverse) ? (t.right) : (t.left);
            }
        }
        @Override
        public boolean hasNext() {
            return !stack.isEmpty();
        }

        @Override
        public Node next() {
            T t = stack.poll();
            if (t == null) {
                throw new NoSuchElementException();
            }

            pushSpine((reverse) ? (t.left) : (t.right));
            return t.value;
        }
    }

    TreapChildren emptyLike() {
        return new TreapChildren();
    }

    List<Node> asList() {
        return new AbstractList<>() {
            @Override
            public Node get(int index) {
                return TreapChildren.this.get(index);
            }

            @Override
            public int size() {
                return TreapChildren.this.size();
            }

            @Override
            public Iterator<Node> iterator() {
                return TreapChildren.this.iterator();
            }
        };
    }

    @Override
    public Iterator<Node> iterator() {
        return new InOrderIterator(root, false);
    }

    Iterable<Node> reversed() {
        return () -> new InOrderIterator(root, true);
    }
}
