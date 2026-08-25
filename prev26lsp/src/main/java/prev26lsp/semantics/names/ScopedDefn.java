package prev26lsp.semantics.names;

import prev26lsp.parser.Node;

import java.util.HashSet;
import java.util.Set;

public final class ScopedDefn {

    public enum Kind {
        TYPE,
        VAR,
        FUN,
        PARAM
    }

    public final String name;
    public final Kind kind;
    public Scope scope;
    public Node defNode;
    public final Set<Node> uses = new HashSet<>();

    public ScopedDefn(String name, Kind kind, Scope scope, Node defNode) {
        this.name = name;
        this.kind = kind;
        this.scope = scope;
        this.defNode = defNode;
    }

}