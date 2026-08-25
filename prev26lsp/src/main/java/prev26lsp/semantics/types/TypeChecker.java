package prev26lsp.semantics.types;

import prev26lsp.model.Diagnostic;
import prev26lsp.parser.Node;
import prev26lsp.parser.ParseDelta;
import prev26lsp.parser.Symbol;
import prev26lsp.semantics.names.NameDelta;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.Scope;
import prev26lsp.semantics.names.ScopedDefn;

import java.util.*;

public class TypeChecker {

    private final TypeStore store = new TypeStore();
    private NameResolver names;

    private DefinitionTypeChecker defnChecker;
    private ExpressionTypeChecker exprChecker;

    private Node root;
    private final Map<Integer, List<TypeDiagnostic>> bodyDiagnostics = new HashMap<>();

    public void computeFull(Node root, NameResolver names) {
        this.root = root;
        this.names = names;
        store.clear();

        this.defnChecker = new DefinitionTypeChecker(names, store);
        this.exprChecker = new ExpressionTypeChecker(names, store);
        List<ScopedDefn> scopedDefns = names.definitions();

        // 1. Check all typedefs twice (so we can check if the types pointed to are valid)
        resolveKind(scopedDefns, ScopedDefn.Kind.TYPE);
        resolveKind(scopedDefns, ScopedDefn.Kind.TYPE);

        // 2. Check for cycles
        store.clearCycleDiagnostics();
        CycleDetector.detect(scopedDefns, store);

        // 3. Check all other definitions
        resolveKind(scopedDefns, ScopedDefn.Kind.PARAM);
        resolveKind(scopedDefns, ScopedDefn.Kind.VAR);
        resolveKind(scopedDefns, ScopedDefn.Kind.FUN);

        // 4. Check expressions (= function bodies)
        bodyDiagnostics.clear();
        for (ScopedDefn defn : scopedDefns) {
            if (defn.kind == ScopedDefn.Kind.FUN) {
                checkFunctionBody(defn);
            }
        }
    }

    public void recompute(Node root, NameResolver names, ParseDelta parseDelta, NameDelta nameDelta) {
        this.root = root;
        this.names = names;

        if (parseDelta.isEmpty()) return;
        carryOverStoredTypes(nameDelta);
        removeStaleState(nameDelta);

        // Collect all definitions that need to be rechecked
        Set<ScopedDefn> dirtyDefns = collectDirtyDefinitions(parseDelta, nameDelta);

        // Check which of them have different types
        Set<ScopedDefn> changedDefns = resolveDirtyDefinitions(dirtyDefns);

        // Propagate changed definitions to all dependent definitions, and recheck them in the right order
        propagateChangedDefinitions(changedDefns);

        // Recheck cycles for every dirty typedef, not only the changed ones: a rebuilt typedef
        // can compare equal to its predecessor and still need its cycle diagnostic refreshed.
        Set<ScopedDefn> cycleCandidates = new HashSet<>(dirtyDefns);
        cycleCandidates.addAll(changedDefns);
        CycleDetector.detect(cycleCandidates, store);

        // Check which functions need to be rechecked
        Set<ScopedDefn> dirtyBodies = evictAffectedExpressions(parseDelta, nameDelta, changedDefns);
        
        // Retype function bodies. Clean statements are just cache reads.
        for (ScopedDefn defn : dirtyBodies) {
            checkFunctionBody(defn);
        }
    }

    public Optional<Type.RecType> recordType(Node postfixExpr, Node dotPrime) {
        ExpressionTypeChecker.Attrs attrs = exprChecker.chainTypeBefore(postfixExpr, dotPrime);

        if (attrs.type().actualType() instanceof Type.RecType recType) {
            return Optional.of(recType);
        }

        return Optional.empty();
    }

    public Optional<Type.FunType> funType(Node postfixExpr, Node callPrime) {
        ExpressionTypeChecker.Attrs attrs = exprChecker.chainTypeBefore(postfixExpr, callPrime);

        if (attrs.type().actualType() instanceof Type.FunType funType) {
            return Optional.of(funType);
        }

        return Optional.empty();
    }

    public Type typeOf(ScopedDefn defn) {
        return store.defType(defn);
    }

    /**
     * A definition rebuilt by the parser gets a new node, so its stored type would be lost and
     * every recheck would look like a change. Match removed and added definitions by name, scope
     * and kind, and seed the new one with the old result. An unmatched addition stays UNKNOWN.
     */
    private void carryOverStoredTypes(NameDelta nameDelta) {
        if (nameDelta.addedDefinitions.isEmpty() || nameDelta.removedDefinitions.isEmpty()) return;

        record Key(String name, Scope scope, ScopedDefn.Kind kind) {}
        Map<Key, ScopedDefn> removed = new HashMap<>();
        for (ScopedDefn defn : nameDelta.removedDefinitions) {
            removed.putIfAbsent(new Key(defn.name, defn.scope, defn.kind), defn);
        }

        for (ScopedDefn defn : nameDelta.addedDefinitions) {
            ScopedDefn old = removed.get(new Key(defn.name, defn.scope, defn.kind));
            if (old != null) {
                store.putDefType(defn, store.defType(old));
            }
        }
    }

    private void removeStaleState(NameDelta nameDelta) {
        for (Node node : nameDelta.removedNodes) {
            store.removeNode(node.id);
        }
        for (ScopedDefn defn : nameDelta.removedDefinitions) {
            store.removeNode(defn.defNode.id);
            bodyDiagnostics.remove(defn.defNode.id);
        }
    }

    private Set<ScopedDefn> collectDirtyDefinitions(ParseDelta parseDelta, NameDelta nameDelta) {
        Set<ScopedDefn> dirtyDefns = new HashSet<>(nameDelta.addedDefinitions);

        for (Node node : parseDelta.spine()) {
            // If we touched the definition (but not the id it is attached to), we need to recheck it.
            definitionAt(node).ifPresent(dirtyDefns::add);
        }

        for (Node use : nameDelta.reboundUses) {
            // Recheck all definitions with the type that contains this use
            declaredTypeOwner(use).ifPresent(dirtyDefns::add);
        }

        return dirtyDefns;
    }

    /* Resolve all dirty definitions and return the set of those that have changed. */
    private Set<ScopedDefn> resolveDirtyDefinitions(Set<ScopedDefn> dirtyDefns) {
        // 1. Resolve all typedefs
        List<ScopedDefn> dirtyTypes = dirtyDefns.stream().filter(s -> s.kind == ScopedDefn.Kind.TYPE).toList();

        // Snapshot the previous results, then give each typedef a fresh name cell.
        Map<ScopedDefn, Type> storedTypes = new HashMap<>();
        for (ScopedDefn dirtyType : dirtyTypes) {
            storedTypes.put(dirtyType, store.defType(dirtyType));
            store.freshNameType(dirtyType.defNode);
        }

        // Resolve typedefs twice (so forward references within the dirty set are filled in)
        dirtyTypes.forEach(defnChecker::resolve);

        // In the second pass, check if the resolved type is structurally different from the previous one.
        // If so, mark it as changed so that dependent definitions can be rechecked.
        Set<ScopedDefn> changedDefns = new HashSet<>();
        for (ScopedDefn dirtyType : dirtyTypes) {
            Type resolved = defnChecker.resolve(dirtyType);

            if (!Type.sameShape(resolved, storedTypes.get(dirtyType))) {
                changedDefns.add(dirtyType);
            }
        }

        // 2. Resolve all other definitions, in order of kind (PARAM, VAR, FUN).
        for (ScopedDefn.Kind kind : List.of(ScopedDefn.Kind.PARAM, ScopedDefn.Kind.VAR, ScopedDefn.Kind.FUN)) {
            for (ScopedDefn dirtyDefn : dirtyDefns) {
                if (dirtyDefn.kind != kind) continue;

                if (resolveAndCheckChanged(dirtyDefn)) {
                    changedDefns.add(dirtyDefn);
                }
            }
        }

        return changedDefns;
    }

    private Set<ScopedDefn> evictAffectedExpressions(ParseDelta parseDelta, NameDelta nameDelta, Set<ScopedDefn> changedDefns) {
        Set<ScopedDefn> dirtyBodies = new HashSet<>();

        // Recheck all functions that were added
        for (ScopedDefn defn : nameDelta.addedDefinitions) {
            if (defn.kind == ScopedDefn.Kind.FUN) {
                dirtyBodies.add(defn);
            }
        }

        // Evict all expressions on the spine and their enclosing functions, so they will be rechecked.
        for (Node node : parseDelta.spine()) {
            if (node.symbol == Symbol.EXPR) {
                store.evict(node.id);
            } else if (node.symbol == Symbol.FUN_DEF) {
                definitionAt(node).ifPresent(dirtyBodies::add);
            }
        }

        // Evict all expressions enclosing the rebound uses and their enclosing functions, so they will be rechecked.
        Set<Integer> evictVisited = new HashSet<>();
        for (Node use: nameDelta.reboundUses) {
            evictEnclosingExpressions(use, evictVisited, dirtyBodies);
        }

        // Do the same for all changed definitions
        for (ScopedDefn defn: changedDefns) {
            if (defn.kind == ScopedDefn.Kind.FUN) {
                dirtyBodies.add(defn);
            }

            for (Node use: defn.uses) {
                evictEnclosingExpressions(use, evictVisited, dirtyBodies);
            }
        }

        return dirtyBodies;
    }

    // Check if the touched node contains a definition (bound to an id)
    private Optional<ScopedDefn> definitionAt(Node node) {
        return switch (node.symbol) {
            case TYPE_DEF, VAR_DEF, PARAM, FUN_DEF -> {
                Node idNode = TypeNav.firstChild(node, Symbol.ID);
                yield names.definitionForIdentifier(idNode);
            }

            default -> Optional.empty();
        };
    }

    // The definition whose declared type contains this use (e.g. var x: T for T)
    private Optional<ScopedDefn> declaredTypeOwner(Node use) {
        Node current = use;

        // Check if this use is part of a definition's declared type (and not part of an expression)
        while (current.id != root.id) {
            switch (current.symbol) {
                case EXPR -> {
                    // The function is a part of an expression
                    return Optional.empty();
                }
                case TYPE_DEF, VAR_DEF, PARAM, FUN_DEF -> {
                    return definitionAt(current);
                }

                default -> current = names.getParent(current);
            }
        }

        return Optional.empty();
    }

    // Check if a definition's stored type changed (structural comparison across generations)
    private boolean resolveAndCheckChanged(ScopedDefn defn) {
        Type oldType = store.defType(defn);

        // Reresolve
        defnChecker.resolve(defn);
        Type newType = store.defType(defn);

        // Check shape equality
        return !Type.sameShape(newType, oldType);
    }

    private void propagateChangedDefinitions(Set<ScopedDefn> changedDefns) {
        ArrayDeque<ScopedDefn> queue = new ArrayDeque<>(changedDefns);

        // Propagate changes to all dependent definitions
        while (!queue.isEmpty()) {
            ScopedDefn defn = queue.removeFirst();

            // A changed named type affects every definition whose declared type uses it
            if (defn.kind == ScopedDefn.Kind.TYPE) {
                for (Node use : defn.uses) {
                    declaredTypeOwner(use).ifPresent(dependent -> {
                        // Add to queue if it wasnt added before
                        if (changedDefns.add(dependent)) {
                            queue.addLast(dependent);
                        }
                    });
                }
            }

            // A changed parameter also changes the type of the enclosing function.
            if (defn.kind == ScopedDefn.Kind.PARAM) {
                enclosingFunction(defn.defNode).ifPresent(fun -> {
                    if (changedDefns.add(fun)) {
                        queue.addLast(fun);
                    }
                });
            }
        }

        resolveKind(changedDefns, ScopedDefn.Kind.TYPE);
        resolveKind(changedDefns, ScopedDefn.Kind.PARAM);
        resolveKind(changedDefns, ScopedDefn.Kind.VAR);
        resolveKind(changedDefns, ScopedDefn.Kind.FUN);
    }

    private Optional<ScopedDefn> enclosingFunction(Node node) {
        Node current = node;

        while (current.id != root.id) {
            if (current.symbol == Symbol.FUN_DEF) {
                return definitionAt(current);
            }

            current = names.getParent(current);
        }

        return Optional.empty();
    }

    /**
     * Evicts all enclosing expressions of the given node.
     * 
     * @param node The node whose enclosing expressions should be evicted.
     * @param visited A set of node ids that have already been visited to avoid cycles.
     * @param dirtyBodies A set of function definitions that have been marked as dirty and need to be rechecked.
     */
    private void evictEnclosingExpressions(Node node, Set<Integer> visited, Set<ScopedDefn> dirtyBodies) {
        Node current = node;

        while (current.id != root.id) {
            if (!visited.add(current.id)) break;

            if (current.symbol == Symbol.EXPR) {
                store.evict(current.id);
            } else if (current.symbol == Symbol.FUN_DEF) {
                definitionAt(current).ifPresent(dirtyBodies::add);
            }
            current = names.getParent(current);
        }
    }

    private void resolveKind(Collection<ScopedDefn> definitions, ScopedDefn.Kind kind) {
        for (ScopedDefn defn : definitions) {
            if (defn.kind == kind) {
                defnChecker.resolve(defn);
            }
        }
    }

    private void checkFunctionBody(ScopedDefn defn) {
        Node owner = names.getParent(defn.defNode);
        if (owner.isTainted()) {
            bodyDiagnostics.remove(defn.defNode.id);
            return;
        }

        Node funBody = TypeNav.firstChild(owner, Symbol.FUN_BODY);
        if (funBody.isEpsilon()) {
            // Skip external functions
            bodyDiagnostics.remove(defn.defNode.id);
            return;
        }

        List<TypeDiagnostic> currentDiagnostics = new ArrayList<>();
        Node body = TypeNav.firstChild(funBody, Symbol.EXPRS);
        ExpressionTypeChecker.Attrs bodyAttrs = exprChecker.checkBody(body, currentDiagnostics);

        // Body vs. return type check
        Type bodyType = bodyAttrs.type();
        if (store.defType(defn) instanceof Type.FunType funType && !funType.returnType().isEquivalentTo(bodyType)) {
            currentDiagnostics.add(new TypeDiagnostic(body.getChildren().getLast(), "Function returns " + bodyType + ", expected " + funType.returnType()));
        }

        if (currentDiagnostics.isEmpty()) {
            bodyDiagnostics.remove(defn.defNode.id);
        } else {
            bodyDiagnostics.put(defn.defNode.id, currentDiagnostics);
        }
    }

    public List<Diagnostic> collectDiagnostics() {
        List<TypeDiagnostic> typeDiagnostics = store.diagnostics();
        for (List<TypeDiagnostic> funDiags : bodyDiagnostics.values()) {
            typeDiagnostics.addAll(funDiags);
        }

        List<Diagnostic> out = new ArrayList<>(typeDiagnostics.size());

        for (TypeDiagnostic d : typeDiagnostics) {
            Node node = d.node();
            int offset = names.absoluteOffsetOf(node) + node.contentStart();
            int length = Math.max(node.contentWidth(), 1);

            out.add(new Diagnostic("prev26type", d.message(), offset, length, Diagnostic.Severity.ERROR, false));
        }
        return out;
    }

}
