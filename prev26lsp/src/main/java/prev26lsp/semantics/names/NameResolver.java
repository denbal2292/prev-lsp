package prev26lsp.semantics.names;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import prev26lsp.model.Diagnostic;
import prev26lsp.parser.Node;
import prev26lsp.parser.ParentLink;
import prev26lsp.parser.ParseDelta;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.types.TypeNav;

import java.util.*;

public class NameResolver {

    enum NodeRole {
        DEF,
        REF,
        SCOPE,
        SKIP
    }

    private Scope globalScope;

    private final Map<Integer, ParentLink> parentLink = new HashMap<>();
    private final Map<Integer, Scope> scopes = new HashMap<>();
    private final Map<Integer, ScopedDefn> definitions = new HashMap<>();
    private final Map<Integer, ScopedDefn> references = new HashMap<>();
    private final Map<Integer, Scope> referenceScope = new HashMap<>();

    // Diagnostics for the node with a given id
    private final Map<Integer, NameDiagnostic> diagnostics = new HashMap<>();

    // The set of nodes we have to recheck diagnostics for.
    private Set<Node> diagnosticsDirty = new HashSet<>();

    // Current changes recorded for the type checker
    private NameDelta delta = new NameDelta();

    /** Returns the list of all defined identifiers. */
    public List<ScopedDefn> definitions() {
        return List.copyOf(this.definitions.values());
    }

    /** Collects all diagnostics for the current parse tree. */
    public List<Diagnostic> collectDiagnostics() {
        Collection<NameDiagnostic> nameDiags = this.diagnostics.values();
        List<Diagnostic> out = new ArrayList<>(nameDiags.size());

        for (NameDiagnostic diag : nameDiags) {
            Node node = diag.node();
            int offset = absoluteOffsetOf(node) + node.leadingWidth;
            int length = (node.value != null) ? (node.value.length()) : (node.getWidth());

            out.add(Diagnostic.fromNameDiagnostic(diag, offset, length));
        }

        return out;
    }

    /** Whether this identifier is a tracked use (bound/unbound) */
    public boolean isTrackedUse(Node idNode) {
        return this.referenceScope.containsKey(idNode.id);
    }

    /** Returns the definition for a given identifier node, if it exists. */
    public Optional<ScopedDefn> definitionForIdentifier(Node idNode) {
        ScopedDefn defn = definitions.get(idNode.id);
        if (defn != null) return Optional.of(defn);

        defn = references.get(idNode.id);
        if (defn != null) return Optional.of(defn);

        return Optional.empty();
    }

    /** Reanalyzes the parse tree with the given delta. */
    public NameDelta reanalyze(Node parseTree, ParseDelta delta) {
        // Check for no-op
        if (delta.isEmpty()) {
            return new NameDelta();
        }

        if (this.globalScope == null) {
            throw new IllegalStateException("resolveFull must be called before reanalyze");
        }

        // Reset the delta
        this.delta = new NameDelta();

        // Update the global scope pointer
        this.globalScope.node = parseTree;

        // Clear dirty nodes
        this.diagnosticsDirty = new HashSet<>();

        // 1. Derive created/reattached nodes and lexical-scope moves from the
        // spine. Created nodes have id >= createdIdFloor, reattached nodes have id < createdIdFloor. 
        // The parent links must not be changed yet.
        int createdIdFloor = delta.createdIdFloor();

        List<Node> created = new ArrayList<>();     // ancestors before descendants
        List<Node> recheckRoots = new ArrayList<>();
        IntSet reattachedIds = new IntOpenHashSet();

        record NodePair(Node parent, int scopeId) {}
        ArrayDeque<NodePair> stack = new ArrayDeque<>();
        int spineScopeId = globalScope.node.id;

        for (Node parent : delta.spine().reversed()) {
            if (isScopeNode(parent)) {
                spineScopeId = parent.id;
            }
            stack.add(new NodePair(parent, spineScopeId));
        }

        while (!stack.isEmpty()) {
            NodePair item = stack.pop();
            Node parent = item.parent();

            for (Node child : parent.getChildren()) {
                if (child.id >= createdIdFloor) {
                    created.add(child);
                    if (!child.isTerminal()) {
                        int childScopeId = isScopeNode(child) ? child.id : item.scopeId();
                        stack.push(new NodePair(child, childScopeId));
                    }
                } else {
                    reattachedIds.add(child.id);

                    ParentLink oldLink = this.parentLink.get(child.id);
                    if (oldLink == null) {
                        throw new IllegalStateException("Reattached node " + child.id + " has no recorded parent");
                    }

                    // The parent changed
                    if (oldLink.parent().id != parent.id) {
                        Node oldParent = oldLink.parent();

                        // Check if enclosing scope or definition kind changed. If so, rebind the subtree.
                        boolean crossedScope = findEnclosingScope(oldParent).node.id != item.scopeId();
                        boolean reclassified = child.symbol == Symbol.ID && oldParent.symbol != parent.symbol;

                        if (crossedScope || reclassified) {
                            recheckRoots.add(child);
                        }
                    }
                }
            }
        }

        // 2. DFS to collect removed nodes (dropped nodes that weren't reattached)
        List<Node> removed = collectSubtrees(delta.dropped(), reattachedIds);

        // 3. DFS to collect changed roots and everything under them for rechecking.
        List<Node> dirty = collectSubtrees(recheckRoots, null);

        // 4. Remove information for removed nodes
        Set<Node> orphans = new HashSet<>(); // nodes who lost a definition

        for (Node node: removed) {
            if (isScopeNode(node)) {
                removeScope(node);
            } else if (definitions.containsKey(node.id)) {
                removeDefinition(node, orphans);
            } else if (referenceScope.containsKey(node.id)) {
                // reference map might be cleared from the removeDefinition
                // so we check referenceScope
                removeUse(node);
            }
        }

        // 5. Remove definitions and uses from moved nodes
        for (Node node : dirty) {
            if (definitions.containsKey(node.id)) {
                removeDefinition(node, orphans);
            } else if (referenceScope.containsKey(node.id)) {
                removeUse(node);
            }
        }

        // 6. Repoint to the new tree: recheck spine scopes and refresh the
        // parent links of everything the walk visits (same traversal as step 1)
        ArrayDeque<Node> updateStack = new ArrayDeque<>();
        for (Node copy : delta.spine()) {
            recheckSpineScope(copy);
            updateStack.push(copy);
        }

        while (!updateStack.isEmpty()) {
            Node parent = updateStack.pop();
            List<Node> children = parent.getChildren();

            for (int i = 0; i < children.size(); i++) {
                Node child = children.get(i);
                this.parentLink.put(child.id, new ParentLink(parent, i));

                if (child.id >= createdIdFloor && !child.isTerminal()) {
                    updateStack.push(child);
                }
            }
        }

        // 7. Rebuild: created + dirty
        // a) Create new scopes for created (ancestors first: outer scopes before inner)
        for (Node node: created) {
            if (isScopeNode(node)) {
                addScope(node);
            }
        }

        // b) Relink dirty scopes
        for (Node node: dirty) {
            if (isScopeNode(node)){
                relinkScope(node);
            }
        }

        // c) Insert definitions from dirty and created nodes
        for (Node node: created)
            if (classifyNode(node) == NodeRole.DEF)
                addDefinition(node, true);

        for (Node node: dirty)
            if (classifyNode(node) == NodeRole.DEF)
                addDefinition(node, true);

        // d) Connect uses with their definitions
        for (Node node: created)
            if (classifyNode(node) == NodeRole.REF) {
                addUse(node);
            }
        for (Node node: dirty)
            if (classifyNode(node) == NodeRole.REF) {
                addUse(node);
            }

        // 8. Re-resolve orphans
        IntSet removedIds = new IntOpenHashSet(removed.size());
        for (Node node: removed) {
            removedIds.add(node.id);
            this.diagnostics.remove(node.id);
        }

        for (Node orphan: orphans)
            if (!removedIds.contains(orphan.id) && !references.containsKey(orphan.id) && classifyNode(orphan) == NodeRole.REF)
                resolveUse(orphan);

        // 9. Remove removed nodes from the parent map and clear diagnostics
        for (Node node: removed)
            this.parentLink.remove(node.id);

        // 10. Refresh diagnostics
        for (Node node: this.diagnosticsDirty) {
            if (removedIds.contains(node.id)) {
                continue;
            }
            refreshDiags(node);
        }

        NameDelta out = this.delta;
        this.delta = new NameDelta();

        // Copy over removed
        out.removedNodes.addAll(removed);

        // Make sure all rebound uses are still in the tree
        out.reboundUses.removeIf(use -> removedIds.contains(use.id));

        return out;
    }

    /**
     * Rebind a scope owned by a spine node to its id-preserving copy.
     */
    private void recheckSpineScope(Node copy) {
        Scope scope = this.scopes.get(copy.id);
        if (scope == null) return;

        scope.node = copy;

        if (scope.kind == Scope.Kind.FUN) {
            boolean externalFunction = isExternalFunction(copy);

            // If we added/removed a body all params have to be added/removed
            if (scope.externalFunction != externalFunction) {
                for (ScopedDefn paramDefn : scope.localDefinitions()) {
                    diagnosticsDirty.add(paramDefn.defNode);
                }
                scope.externalFunction = externalFunction;
            }
        }
    }

    /** Resolves all names in the parse tree. */
    public void resolveFull(Node parseTree) {
        // Reset all maps
        parentLink.clear();
        scopes.clear();
        definitions.clear();
        references.clear();
        referenceScope.clear();

        // Clear diagnostics
        diagnostics.clear();
        diagnosticsDirty = new HashSet<>();

        // Create the global scope
        this.globalScope = new Scope(parseTree, null, Scope.Kind.GLOBAL);
        this.scopes.put(parseTree.id, this.globalScope);

        List<Node> defNodes = new ArrayList<>();
        List<Node> refNodes = new ArrayList<>();

        // Pass 1: Collect defs/refs, create scope objects
        // in source order = DFS preorder
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(parseTree);

        while (!stack.isEmpty()) {
            Node node = stack.pop();

            switch (classifyNode(node)) {
                case NodeRole.SCOPE -> {
                    if (node != parseTree) addScope(node);
                }

                case NodeRole.DEF -> defNodes.add(node);
                case NodeRole.REF -> refNodes.add(node);
                case NodeRole.SKIP -> {}
            }

            List<Node> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                Node child = children.get(i);

                parentLink.put(child.id, new ParentLink(node, i));
                stack.push(child);
            }
        }

        // Pass 2: Add definitions
        for (Node defNode : defNodes) {
            addDefinition(defNode, false);
        }

        // Pass 3: Add uses
        refNodes.forEach(this::addUse);

        // Pass 4: Build diagnostics
        defNodes.forEach(this::refreshDiags);
        refNodes.forEach(this::refreshDiags);
    }

    /** Returns the absolute offset of a node within the global scope. */
    public int absoluteOffsetOf(Node node) {
        return this.offsetIn(node, this.globalScope);
    }

    /** A renameable identifier occurrence: its text offset and length. */
    public record Span(int offset, int length) {}

    /**
     * Collect the spans of every occurrence (definition + uses) that a rename must touch.
     *
     * @param defn         the definition whose name is being renamed
     * @param defNodeStart the raw start offset of {@code defn.defNode} in the document (derived from cursor position of the caller)
     */
    public List<Span> renameSpans(ScopedDefn defn, int defNodeStart) {
        Node defNode = defn.defNode;
        Scope scope = defn.scope;

        // Every use lives within defn.scope's subtree, so we resolve use offsets relative
        // to the scope (a short climb) instead of all the way to the global root.
        int scopeBase = defNodeStart - offsetIn(defNode, scope);

        List<Span> spans = new ArrayList<>(defn.uses.size() + 1);
        spans.add(new Span(defNodeStart + defNode.leadingWidth, defNode.value.length()));

        for (Node use : defn.uses) {
            int useStart = scopeBase + offsetIn(use, scope);
            spans.add(new Span(useStart + use.leadingWidth, use.value.length()));
        }

        return spans;
    }

    /**
     * Insert a new definition node into its enclosing scope.
     */
    public void addDefinition(Node node, boolean adopt) {
        if (this.definitions.containsKey(node.id)) {
            throw new IllegalStateException("Definition already added");
        }

        String name = node.value;
        ScopedDefn.Kind kind = findDefinitionKind(node);
        Scope scope = findEnclosingScope(node);

        // Function names belong to the enclosing scope
        if (kind == ScopedDefn.Kind.FUN) {
            scope = scope.parent;
        }

        // Creating a new definition
        ScopedDefn newDefn = new ScopedDefn(name, kind, scope, node);
        this.definitions.put(node.id, newDefn);
        this.delta.addedDefinitions.add(newDefn);
        this.diagnosticsDirty.add(node);

        // Check if there is already a definition with this name in current scope
        ScopedDefn oldDefn = scope.lookupLocal(name);

        if (oldDefn == null) {
            // If there isn't, insert it as the new
            scope.insertNew(newDefn);
            if (adopt) {
                adoptDefinition(scope, newDefn);
            }
        } else if (offsetIn(node, scope) < offsetIn(oldDefn.defNode, scope)) {
            // ... If there is, pick the one with lower offset
            // 1. Move over uses to the new definition
            moveOverUses(oldDefn, newDefn);

            // 2. Remove old definition as the winner
            scope.remove(name);

            // 3. Insert new definition
            scope.insertNew(newDefn);

            // 4. Insert old definition as the duplicate
            scope.insertDuplicate(oldDefn);

            // 5. Dirty definition has to be rechecked for diags
            this.diagnosticsDirty.add(oldDefn.defNode);

        } else {
            // New definition is a duplicate of an existing one
            scope.insertDuplicate(newDefn);
        }
    }

    /**
     * Register a reference to the definition.
     */
    public void addUse(Node node) {
        Scope scope = findEnclosingScope(node);

        // Set its scope
        setEnclosingScope(node, scope);
        scope.addUseByName(node);

        // Find its definition
        resolveUse(node);
    }

    public void resolveUse(Node node) {
        String name = node.value;
        Scope scope = getEnclosingScope(node);

        if (this.references.containsKey(node.id)) {
            throw new IllegalStateException("Use must be unbound before resolving");
        }

        // Try to find a definition
        ScopedDefn defn = scope.lookup(name);

        if (defn != null) {
            // Bind to a new definition
            this.references.put(node.id, defn);
            defn.uses.add(node);
            this.diagnosticsDirty.add(defn.defNode);
        }

        // Add to rebound uses even if the definition was not found
        this.delta.reboundUses.add(node);
        this.diagnosticsDirty.add(node);
    }

    public void addScope(Node node) {
        if (this.scopes.containsKey(node.id)) {
            throw new IllegalStateException("Node already has a scope");
        }

        Scope.Kind scopeKind = switch (node.symbol) {
            case Symbol.PROGRAM ->  Scope.Kind.GLOBAL;
            case Symbol.FUN_DEF ->  Scope.Kind.FUN;
            default ->  Scope.Kind.LET;
        };

        Scope enclosingScope = findEnclosingScope(node);
        Scope newScope = new Scope(node, enclosingScope, scopeKind);

        if (newScope.kind == Scope.Kind.FUN) {
            newScope.externalFunction = isExternalFunction(node);
        }

        this.scopes.put(node.id, newScope);
    }

    /**
     * Reparent a scope if the node that owns it was reparented.
     */
    public void relinkScope(Node node) {
        Scope scope = this.scopes.get(node.id);
        if (scope == null) {
            throw new IllegalStateException("Node doesn't own a scope");
        }

        // Check if the parent scope (=scope of the parent node) didn't change
        Node parent = getParent(node);
        Scope enclosingScope = findEnclosingScope(parent);

        if (scope.parent == enclosingScope) {
            return;
        }

        // Remove the scope from old parent scope's children
        if (scope.parent != null) {
            scope.parent.children.remove(scope);
        }

        // Update the enclosing scope
        scope.parent = enclosingScope;

        // Add it to children of the enclosing scope
        enclosingScope.children.add(scope);
    }

    /** Removes a definition from the resolver.
     * 
     * @param node The definition node to remove.
     * @param orphans A set to collect uses that lost their definition.
     */
    public void removeDefinition(Node node, Set<Node> orphans) {
        if (!this.definitions.containsKey(node.id)) {
            throw new IllegalStateException("Removing a definition that was never defined");
        }

        ScopedDefn defn = this.definitions.remove(node.id);
        this.diagnosticsDirty.add(node);
        this.delta.removedDefinitions.add(defn);

        String name = defn.name;
        Scope scope = defn.scope;

        // Check if the definition being removed is the winner
        if (defn == scope.lookupLocal(name)) {
            scope.remove(name);
            List<ScopedDefn> duplicateDefns = scope.getDuplicates(name);

            if (!duplicateDefns.isEmpty()) {
                // Promote the duplicate with the lowest offset
                ScopedDefn winDefn = duplicateDefns.stream()
                        .min(Comparator.comparingInt(d -> offsetIn(d.defNode, scope)))
                        .orElseThrow();

                scope.removeDuplicate(name, winDefn);
                scope.insertNew(winDefn);

                // Move over uses to the new winner
                moveOverUses(defn, winDefn);

                // No longer duplicate
                this.diagnosticsDirty.add(winDefn.defNode);
            } else {
                // The only definition of this name was deleted
                // -> all its uses are now dangling
                for (Node use : defn.uses) {
                    orphans.add(use);
                    this.references.remove(use.id);
                }
            }

        } else {
            // It was a duplicate
            scope.removeDuplicate(name, defn);
        }
    }

    /**
     * Removes a use from the resolver.
     * 
     * @param node The use node to remove.
     */
    public void removeUse(Node node) {
        this.diagnosticsDirty.add(node);
        this.delta.removedUses.add(node);

        // Remove from definition's uses (if it was bound to one)
        ScopedDefn defn = this.references.remove(node.id);
        if (defn != null) {
            defn.uses.remove(node);
            this.diagnosticsDirty.add(defn.defNode);
        }

        // Remove from uses of its scope
        Scope scope = getEnclosingScope(node);
        scope.removeUseByName(node);
        removeEnclosingScope(node);
    }

    /**
     * Removes a scope from the resolver.
     * 
     * @param node The node whose scope to remove.
     */
    public void removeScope(Node node) {
        Scope scope = this.scopes.get(node.id);

        if (scope == null) {
            throw new IllegalStateException("Node doesn't own a scope");
        }

        if (scope.parent != null) {
            scope.parent.children.remove(scope);
        }

        this.scopes.remove(node.id);
    }

    /**
     * Rebinds all eligible references to name in the subtree to the given definition.
     * 
     * @param scope The scope to start the search from.
     * @param defn The definition to bind to.
     */
    public void adoptDefinition(Scope scope, ScopedDefn defn) {
        String name = defn.name;

        // Do DFS on the tree of scopes
        ArrayDeque<Scope> stack = new ArrayDeque<>();
        stack.push(scope);

        while (!stack.isEmpty()) {
            Scope sc = stack.pop();
            Set<Node> nameUses = sc.getUsesByName(name);

            for (Node use: nameUses) {
                ScopedDefn oldDefn = references.get(use.id);
                // Check if already bound to this definition
                if (oldDefn == defn) continue;

                // Check if we can overwrite the definition
                if (oldDefn == null || oldDefn.scope.isAncestorOf(scope)) {
                    if (oldDefn != null) {
                        oldDefn.uses.remove(use);
                        this.diagnosticsDirty.add(oldDefn.defNode);
                    }

                    // Connect to new definition
                    this.references.put(use.id, defn);
                    defn.uses.add(use);

                    this.diagnosticsDirty.add(use);
                    this.delta.reboundUses.add(use);
                }
            }

            for (Scope child: sc.children) {
                // Prune scopes that redefine this name
                if (child.definesName(name)) continue;

                stack.push(child);
            }
        }
    }

    /**
     * Collects all nodes in the subtrees rooted at the given roots, skipping any nodes whose id is in skip.
     * 
     * @param roots The roots of the subtrees to collect.
     * @param skip The set of node IDs to skip.
     * @return The list of collected nodes.
     */
    private static List<Node> collectSubtrees(List<Node> roots, IntSet skip) {
        List<Node> collected = new ArrayList<>();

        for (Node root : roots) {
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (skip != null && skip.contains(node.id)) {
                    continue;
                }

                collected.add(node);
                stack.addAll(node.getChildren());
            }
        }

        return collected;
    }

    /**
     * Returns the offset of a node within a scope by summing the widths
     * of all left siblings on the path to the new scope root.
     *
     * @throws IllegalStateException if the node is outside the provided scope
     */
    private int offsetIn(Node node, Scope scope) {
        Node current = node;
        int offset = 0;

        // Compare by id (nodes might have been copied
        while (current.id != scope.node.id) {
            ParentLink link = parentLink.get(current.id);

            // Check if we reached root
            if (link == null) {
                throw new IllegalStateException("Node does not belong to the scope");
            }

            // Sum all left children (add only for non-first nodes)
            if (link.index() > 0) {
                IntList prefixWidths = link.parent().getPrefixWidths();
                int prefixWidth = prefixWidths.getInt(link.index() - 1);

                offset += prefixWidth;
            }

            current = link.parent();
        }

        return offset;
    }

    /**
     * Climb up the tree to try and find a parent that owns a scope.
     * @param node The node to start finding from.
     * @return The found scope (or global).
     */
    public Scope findEnclosingScope(Node node) {
        Node current = node;

        while (current != null) {
            Scope scope = this.scopes.get(current.id);
            if (scope != null) {
                return scope;
            }

            current = this.parentLink.get(current.id).parent();
        }

        return this.globalScope;
    }

    /**
     * Recompute diagnostics for the given node.
     */
    private void refreshDiags(Node node) {
        // Remove old diagnostics (if any)
        this.diagnostics.remove(node.id);
        NameDiagnostic diagnostic = null;

        // Check if it's a definition
        ScopedDefn defn = this.definitions.get(node.id);
        if (defn != null) {
            // Check if it's a duplicate (!= the winner)
            if (defn.scope.lookupLocal(defn.name) != defn) {
                diagnostic = new NameDiagnostic(NameDiagnostic.Kind.REDEFINITION, node, defn.name);
            } else if (defn.uses.isEmpty() && shouldReportUnused(defn)) {
                diagnostic = new NameDiagnostic(NameDiagnostic.Kind.UNUSED, node, defn.name);
            }
        }
        // Check if it has no definition
         else if (this.referenceScope.containsKey(node.id) && !this.references.containsKey(node.id)) {
             diagnostic = new NameDiagnostic(NameDiagnostic.Kind.UNDEFINED, node, node.value);
        }

         if (diagnostic != null) {
             this.diagnostics.put(node.id, diagnostic);
         }
    }

    private boolean shouldReportUnused(ScopedDefn defn) {
        // Check if top level main function
        if (defn.kind == ScopedDefn.Kind.FUN && defn.name.equals("main") && defn.scope == this.globalScope) {
            return false;
        }

        // Check if parameter of external function
        if (defn.kind == ScopedDefn.Kind.PARAM && defn.scope.externalFunction) {
            return false;
        }

        return true;
    }

    private static boolean isExternalFunction(Node funNode) {
        return TypeNav.firstChild(funNode, Symbol.FUN_BODY).isEpsilon();
    }

     public Node getParent(Node node) {
        if (!this.parentLink.containsKey(node.id)) {
            throw new IllegalStateException("Node " + node.id + " has no recorded parent");
        }

        return this.parentLink.get(node.id).parent();
    }

    /** Whether a parent link is recorded for this node (false in broken/orphaned tree regions). */
    public boolean hasParent(Node node) {
        return this.parentLink.containsKey(node.id);
    }

    private ScopedDefn.Kind findDefinitionKind(Node node) {
        // Look at the parent to determine the kind of the definition
        Node parent = this.getParent(node);

        return switch (parent.symbol) {
            case Symbol.TYPE_DEF -> ScopedDefn.Kind.TYPE;
            case Symbol.VAR_DEF ->  ScopedDefn.Kind.VAR;
            case Symbol.FUN_DEF ->  ScopedDefn.Kind.FUN;
            case Symbol.PARAM ->    ScopedDefn.Kind.PARAM;
            default -> throw new IllegalStateException("Trying to treat " + parent + " as a definition");
        };
    }

    private void moveOverUses(ScopedDefn oldDefn, ScopedDefn newDefn) {
        // Move over uses
        newDefn.uses.addAll(oldDefn.uses);

        // Point all references to the new definition
        for (Node use : oldDefn.uses) {
            this.references.put(use.id, newDefn);
            this.delta.reboundUses.add(use);
        }

        // Clear old uses
        oldDefn.uses.clear();

        // Use count changed
        this.diagnosticsDirty.add(oldDefn.defNode);
        this.diagnosticsDirty.add(newDefn.defNode);
    }

    private NodeRole classifyNode(Node node) {
        if (node.symbol == Symbol.ID && node.kind == Node.Kind.NORMAL) {
            // Classify based on parent
            Node parent = Objects.requireNonNull(getParent(node));

            return switch (parent.symbol) {
                case Symbol.TYPE_DEF, Symbol.FUN_DEF, Symbol.VAR_DEF, Symbol.PARAM ->  NodeRole.DEF;
                case Symbol.TYPE, Symbol.PRIMARY_EXPR ->  NodeRole.REF;
                default -> NodeRole.SKIP;
            };
        }

        if (isScopeNode(node)) {
            return NodeRole.SCOPE;
        } else {
            return NodeRole.SKIP;
        }
    }

    private static boolean isScopeNode(Node node) {
        return node.symbol == Symbol.FUN_DEF || node.symbol == Symbol.PROGRAM || (node.symbol == Symbol.PRIMARY_EXPR && !node.getChildren().isEmpty() && node.getChildren().getFirst().symbol == Symbol.LET);
    }

    private Scope getEnclosingScope(Node node) {
        Scope scope = this.referenceScope.get(node.id);

        if (scope == null) {
            throw new IllegalStateException("Node has no enclosing scope");
        }

        return scope;
    }

    private void setEnclosingScope(Node node, Scope scope) {
        if (this.referenceScope.containsKey(node.id)) {
            throw new IllegalStateException("Use is already registered");
        }

        this.referenceScope.put(node.id, scope);
    }

    private void removeEnclosingScope(Node node) {
        if (this.referenceScope.remove(node.id) == null) {
            throw new IllegalStateException("Node does not own a scope");
        }
    }

}
