package prev26lsp.semantics.names;

import prev26lsp.parser.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NameDelta {

    public final List<Node> removedNodes = new ArrayList<>();
    public final Set<Node> reboundUses = new HashSet<>();
    public final Set<Node> removedUses = new HashSet<>();
    public final Set<ScopedDefn> addedDefinitions = new HashSet<>();
    public final Set<ScopedDefn> removedDefinitions = new HashSet<>();

}
