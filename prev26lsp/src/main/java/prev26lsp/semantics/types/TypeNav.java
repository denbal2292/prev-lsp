package prev26lsp.semantics.types;

import prev26lsp.parser.Node;
import prev26lsp.parser.Symbol;

import java.util.List;

public class TypeNav {

    private TypeNav() {}

    static Node firstChildOrNull(Node n) {
        List<Node> children = n.getChildren();

        return (children.isEmpty()) ? (null) : (children.getFirst());
    }

    static Node childOrNull(Node n, Symbol s) {
        for (Node child : n.getChildren()) {
            if (child.symbol == s) {
                return child;
            }
        }

        return null;
    }

    static Node lastChildOrNull(Node n, Symbol s) {
        for (Node child : n.getChildren().reversed()) {
            if (child.symbol == s) {
                return child;
            }
        }

        return null;
    }

    public static Node firstChild(Node n, Symbol s) {
        for (Node child : n.getChildren()) {
            if (child.symbol == s) {
                return child;
            }
        }

        throw new IllegalStateException();
    }

    static Node lastChild(Node n, Symbol s) {
        for (Node child : n.getChildren().reversed()) {
            if (child.symbol == s) {
                return child;
            }
        }

        throw new IllegalStateException();
    }

    public static List<Node> getChildren(Node parent, Symbol symbol) {
        return parent.getChildren().stream().filter(c -> c.symbol == symbol).toList();
    }

}
