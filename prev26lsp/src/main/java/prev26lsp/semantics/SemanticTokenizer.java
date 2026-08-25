package prev26lsp.semantics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import prev26lsp.document.LineIndex;
import prev26lsp.model.Position;
import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.names.NameDelta;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.ScopedDefn;

/** Maintains semantic tokens for identifiers in document order. */
public class SemanticTokenizer {

    public static final List<String> TOKEN_TYPES = List.of("function", "variable", "parameter", "type");
    public static final List<String> TOKEN_MODIFIERS = List.of("declaration");

    private static final int TYPE_FUNCTION = 0;
    private static final int TYPE_VARIABLE = 1;
    private static final int TYPE_PARAMETER = 2;
    private static final int TYPE_TYPE = 3;

    private static final int DECLARATION_MODIFIER = 1;

    private static final int TUPLE_SIZE = 5;

    /** A semantic token with an absolute document offset. */
    private record Token(int id, int start, int length, int type, int mods) {

        Token shiftedBy(int delta) {
            return (delta == 0) ? this : new Token(id, start + delta, length, type, mods);
        }
    }

    private record Kind(int type, int mods) {}

    // Ordered by start offset.
    private ArrayList<Token> tokens = new ArrayList<>();

    private List<Integer> lspDataCache;

    public void rebuildFull(Node root, NameResolver resolver) {
        ArrayList<Token> out = new ArrayList<>();

        record Frame(Node node, int start) {}
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, 0));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Node node = frame.node();

            if (node.isTerminal()) {
                Kind kind = classify(node, resolver);
                if (kind == null) continue;

                out.add(token(node, frame.start() + node.leadingWidth, kind));
            } else {
                // Push in reverse so the traversal remains left-to-right.
                int childEnd = frame.start() + node.getWidth();

                for (Node child : node.getChildren().reversed()) {
                    int childStart = childEnd - child.getWidth();

                    stack.push(new Frame(child, childStart));
                    childEnd = childStart;
                }
            }
        }

        tokens = out;
        lspDataCache = null;
    }

    /** Applies one incremental reparse; the supplied end offset refers to the updated document. */
    public void applyUpdate(NameResolver resolver, NameDelta nameDelta, int unchangedStart, int unchangedEndNew, int lengthDelta) {
        // Remove deleted and reclassified identifiers.
        Set<Integer> dropped = new HashSet<>();
        for (Node node : nameDelta.removedUses) {
            dropped.add(node.id);
        }
        for (Node node : nameDelta.removedNodes) {
            dropped.add(node.id);
        }
        for (ScopedDefn defn : nameDelta.removedDefinitions) {
            dropped.add(defn.defNode.id);
        }

        // Recompute added and rebound identifiers.
        Set<Node> changed = new HashSet<>(nameDelta.reboundUses);
        for (ScopedDefn defn : nameDelta.addedDefinitions) {
            changed.add(defn.defNode);
        }

        List<Token> fresh = new ArrayList<>();
        for (Node node : changed) {
            dropped.add(node.id);

            Kind kind = classify(node, resolver);
            if (kind == null) continue;

            fresh.add(token(node, resolver.absoluteOffsetOf(node) + node.leadingWidth, kind));
        }
        fresh.sort(Comparator.comparingInt(Token::start));

        splice(dropped, fresh, unchangedStart, unchangedEndNew - lengthDelta, lengthDelta);
        lspDataCache = null;
    }

    /** Returns LSP semantic-token data with delta-encoded positions. */
    public List<Integer> lspDataSnapshot(LineIndex lineIndex) {
        List<Integer> cached = lspDataCache;
        if (cached != null) {
            return cached;
        }

        List<Integer> out = new ArrayList<>(tokens.size() * TUPLE_SIZE);
        int prevLine = 0;
        int prevChar = 0;

        for (Token token : tokens) {
            Position pos = lineIndex.convertToPosition(token.start());

            out.add(pos.getLine() - prevLine);
            out.add((pos.getLine() == prevLine) ? (pos.getCharacter() - prevChar) : (pos.getCharacter()));
            out.add(token.length());
            out.add(token.type());
            out.add(token.mods());

            prevLine = pos.getLine();
            prevChar = pos.getCharacter();
        }

        return lspDataCache = List.copyOf(out);
    }

    /** Returns the encoded token data as an array for tests. */
    public int[] dataSnapshot(LineIndex lineIndex) {
        List<Integer> encoded = lspDataSnapshot(lineIndex);
        int[] out = new int[encoded.size()];

        for (int i = 0; i < out.length; i++) {
            out[i] = encoded.get(i);
        }

        return out;
    }

    /** Returns the absolute token offsets for tests. */
    public int[] startsSnapshot() {
        int[] out = new int[tokens.size()];

        for (int i = 0; i < out.length; i++) {
            out[i] = tokens.get(i).start();
        }

        return out;
    }

    /** Returns the current token count for tests. */
    public int tokenCount() {
        return tokens.size();
    }

    private static Kind classify(Node node, NameResolver resolver) {
        if (node.symbol != Symbol.ID || node.kind != Node.Kind.NORMAL) {
            return null;
        }

        Optional<ScopedDefn> defn = resolver.definitionForIdentifier(node);

        if (defn.isEmpty()) {
            if (!resolver.isTrackedUse(node)) {
                return null;
            }

            // Infer the token type from syntax when name resolution fails.
            Node parent = resolver.getParent(node);
            return new Kind(switch (parent.symbol) {
                case Symbol.TYPE -> TYPE_TYPE;
                default -> TYPE_VARIABLE;
            }, 0);
        }

        boolean isDefinition = (defn.get().defNode == node);
        int type = switch (defn.get().kind) {
            case FUN -> TYPE_FUNCTION;
            case VAR -> TYPE_VARIABLE;
            case PARAM -> TYPE_PARAMETER;
            case TYPE -> TYPE_TYPE;
        };

        return new Kind(type, (isDefinition ? DECLARATION_MODIFIER : 0));
    }

    private static Token token(Node node, int start, Kind kind) {
        return new Token(node.id, start, node.contentWidth(), kind.type(), kind.mods());
    }

    /** Replaces changed tokens and adjusts offsets; the supplied range uses pre-edit offsets. */
    private void splice(Set<Integer> dropped, List<Token> fresh,
            int unchangedStart, int unchangedEndOld, int lengthDelta) {
        if (dropped.isEmpty() && fresh.isEmpty() && lengthDelta == 0) {
            return;
        }
        if (unchangedEndOld < unchangedStart) {
            throw new IllegalArgumentException("end cannot be before start: " + unchangedStart + " > " + unchangedEndOld);
        }

        // Name resolution can change identifiers outside the edited text.
        int windowStart = unchangedStart;
        int windowEndOld = unchangedEndOld;

        if (!fresh.isEmpty()) {
            windowStart = Math.min(windowStart, fresh.getFirst().start());
            windowEndOld = Math.max(windowEndOld, fresh.getLast().start() - lengthDelta + 1);
        }
        
        for (Token token : tokens) {
            if (!dropped.contains(token.id())) continue;

            windowStart = Math.min(windowStart, token.start());
            windowEndOld = Math.max(windowEndOld, token.start() + 1);
        }

        int lo = lowerBound(windowStart);
        int hi = lowerBound(windowEndOld);

        ArrayList<Token> merged = new ArrayList<>(tokens.size() + fresh.size());

        // Tokens before the window keep their offsets.
        merged.addAll(tokens.subList(0, lo));

        // Merge surviving and replacement tokens in document order.
        int i = lo, j = 0;
        while (i < hi || j < fresh.size()) {
            if (i < hi && dropped.contains(tokens.get(i).id())) {
                i++;
                continue;
            }

            if (i >= hi) {
                merged.add(fresh.get(j++));
                continue;
            }

            Token survivor = shifted(tokens.get(i), unchangedStart, lengthDelta);
            if (j < fresh.size() && fresh.get(j).start() < survivor.start()) {
                merged.add(fresh.get(j++));
            } else {
                merged.add(survivor);
                i++;
            }
        }

        // Shift tokens after the window by the edit delta.
        for (int k = hi; k < tokens.size(); k++) {
            merged.add(tokens.get(k).shiftedBy(lengthDelta));
        }

        tokens = merged;
    }

    private static Token shifted(Token token, int unchangedStart, int lengthDelta) {
        return (token.start() >= unchangedStart) ? token.shiftedBy(lengthDelta) : token;
    }

    /** Returns the first token whose start is at least offset. */
    private int lowerBound(int offset) {
        int lo = 0;
        int hi = tokens.size();

        while (lo < hi) {
            int mid = (lo + hi) >>> 1;

            if (tokens.get(mid).start() < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        return lo;
    }

}
