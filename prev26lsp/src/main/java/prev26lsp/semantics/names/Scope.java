package prev26lsp.semantics.names;

import prev26lsp.parser.Node;

import java.util.*;

public class Scope {

    public enum Kind {
        GLOBAL,
        FUN,
        LET
    }

    public Scope parent;
    public final Set<Scope> children = new HashSet<>();
    public Node node;
    public final Kind kind;
    public boolean externalFunction;

    private final Map<String, ScopedDefn> defs = new HashMap<>();
    private final Map<String, List<ScopedDefn>> dupDefs = new HashMap<>();
    private final Map<String, Set<Node>> usesByName = new HashMap<>();

    public Scope(Node node, Scope parent, Kind kind) {
        this.node = node;
        this.parent = parent;
        this.kind = kind;
        this.externalFunction = false;

        if (parent != null) {
            parent.children.add(this);
        }
    }

    public ScopedDefn lookup(String name) {
        Scope current = this;

        // Climb parent scopes
        while (current != null) {
            ScopedDefn nameDef = current.lookupLocal(name);
            if (nameDef != null) {
                return nameDef;
            }
            current = current.parent;
        }

        return null;
    }

    public boolean isAncestorOf(Scope other) {
        Scope current = other;

        while (current != null) {
            if (current == this) {
                return true;
            }
            current = current.parent;
        }

        return false;
    }

    public ScopedDefn lookupLocal(String name) {
        return this.defs.get(name);
    }

    public List<ScopedDefn> getDuplicates(String name) {
        return this.dupDefs.getOrDefault(name, List.of());
    }

    public void remove(String name) {
        if (this.defs.remove(name) == null) {
            throw new IllegalStateException("No definition for " + name);
        }
    }

    public void removeDuplicate(String name, ScopedDefn defn) {
        List<ScopedDefn> dups = this.dupDefs.get(name);
        if (dups == null || !dups.remove(defn)) {
            throw new IllegalStateException("Duplicate definition " + defn + " is not in the list");
        }

        if (dups.isEmpty()) {
            // Clean up empty lists
            this.dupDefs.remove(name);
        }
    }

    public void insertNew(ScopedDefn defn) {
        if (this.defs.containsKey(defn.name)) {
            throw new IllegalStateException("There is already a definition for " + defn.name);
        }

        this.defs.put(defn.name, defn);
    }

    public void insertDuplicate(ScopedDefn defn) {
        if (!this.defs.containsKey(defn.name)) {
            throw new IllegalStateException("There is no definition already for " + defn.name);
        }

        this.dupDefs.computeIfAbsent(defn.name, _ -> new ArrayList<>()).add(defn);
    }

    public void addUseByName(Node node) {
        String name = node.value;
        this.usesByName.computeIfAbsent(name, _ -> new HashSet<>()).add(node);
    }

    public void removeUseByName(Node node) {
        Set<Node> uses = this.usesByName.get(node.value);

        if (uses == null || !uses.remove(node)) {
            throw new IllegalStateException("Node was not in uses");
        }
    }

    public boolean definesName(String name) {
        return this.defs.containsKey(name);
    }

    public Set<Node> getUsesByName(String name) {
        return this.usesByName.getOrDefault(name, Set.of());
    }

    public Collection<ScopedDefn> localDefinitions() {
        return this.defs.values();
    }
}
