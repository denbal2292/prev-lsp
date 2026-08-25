package prev26lsp.document;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import prev26lsp.diagnostics.DiagnosticsPublisher;
import prev26lsp.lexer.Lexer;
import prev26lsp.lexer.Token;
import prev26lsp.model.CompletionItem;
import prev26lsp.model.Diagnostic;
import prev26lsp.model.DocumentSymbol;
import prev26lsp.model.DocumentEdit;
import prev26lsp.model.Position;
import prev26lsp.model.Range;
import prev26lsp.model.SignatureInformation;
import prev26lsp.parser.ErrorMessages;
import prev26lsp.parser.IncHelpers;
import prev26lsp.parser.Node;
import prev26lsp.parser.Parser;
import prev26lsp.parser.Symbol;
import prev26lsp.parser.Node.Kind;
import prev26lsp.semantics.SemanticTokenizer;
import prev26lsp.semantics.names.NameDelta;
import prev26lsp.semantics.names.NameResolver;
import prev26lsp.semantics.names.Scope;
import prev26lsp.semantics.names.ScopedDefn;
import prev26lsp.semantics.types.Type;
import prev26lsp.semantics.types.TypeChecker;
import prev26lsp.semantics.types.TypeNav;

import static prev26lsp.parser.IncHelpers.findPreviousTerminal;

public class DocumentSession {

    private final String documentId;
    private final DocumentBuffer documentBuffer;

    private final ScheduledExecutorService scheduler;
    public final static long debounceDelayMs = 100;

    /**
     * Upper bound on the diagnostics sent in one publish. A file that is not PREV at all produces
     * roughly one syntax error per token, and the notification carrying them is JSON: at ~176 bytes
     * per diagnostic, a few megabytes of such a file serialize past the 512 MB cap V8 puts on a
     * single string, which the client cannot decode at all — it drops the connection instead of
     * showing the errors. Nobody reads the thousandth squiggle anyway, so cut there and say how
     * many were left out.
     */
    private final static int maxPublishedDiagnostics = 1000;

    private Node parseTree;
    private int parsedDocumentLength;
    private final NameResolver nameResolver;
    private final TypeChecker typeChecker;
    private final SemanticTokenizer semanticTokenizer;

    private int unchangedStartLen;
    private int unchangedEndLen;

    private ScheduledFuture<?> pendingDiagnosticsTask;

    // Completed whenever parseTree matches the document; replaced with a fresh
    // incomplete future on the clean -> dirty transition. Guarded by `this`.
    private CompletableFuture<Void> treeClean = CompletableFuture.completedFuture(null);

    public DocumentSession(String documentId, String initialText, DocumentBufferFactory documentBufferFactory) {
        this.documentId = documentId;
        this.documentBuffer = documentBufferFactory.createDocumentBuffer(initialText);
        this.nameResolver = new NameResolver();
        this.typeChecker = new TypeChecker();
        this.semanticTokenizer = new SemanticTokenizer();

        this.unchangedStartLen = Integer.MAX_VALUE;
        this.unchangedEndLen = Integer.MAX_VALUE;

        this.parseTree = new Node(Symbol.START_SYMBOL, Node.Kind.NORMAL);
        this.parsedDocumentLength = 0;
        reparse();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void applyEdit(DocumentEdit edit) {
        int lengthPreEdit = documentBuffer.getDocumentLength();
        int editStart = documentBuffer.convertToOffset(edit.getStart());
        int editEnd = documentBuffer.convertToOffset(edit.getEnd());

        this.unchangedStartLen = Math.min(this.unchangedStartLen, editStart); // from start
        this.unchangedEndLen = Math.min(this.unchangedEndLen, lengthPreEdit - editEnd); // from end

        documentBuffer.applyEdit(edit);

        if (treeClean.isDone()) {
            treeClean = new CompletableFuture<>();
        }

        if (pendingDiagnosticsTask != null) {
            pendingDiagnosticsTask.cancel(false);
        }

        pendingDiagnosticsTask = scheduler.schedule(this::reparse, debounceDelayMs, TimeUnit.MILLISECONDS);
    }

    public Optional<Range> getDefinitionRange(Position position) {
        Optional<IncHelpers.NodeLocation> target = identifierTarget(position);
        if (target.isEmpty()) {
            return Optional.empty();
        }

        IncHelpers.NodeLocation loc = target.get();
        Node node = loc.node();
        ScopedDefn defn = nameResolver.definitionForIdentifier(node).orElseThrow();
        Node defnNode = defn.defNode;

        int defNodeStart = (node.id == defnNode.id)
                ? (loc.nodeStart())
                : (nameResolver.absoluteOffsetOf(defnNode));

        int start = defNodeStart + defnNode.leadingWidth;
        return Optional.of(documentBuffer.convertToRange(start, defnNode.value.length()));
    }

    public Optional<Range> getRenameRange(Position position) {
        return identifierTarget(position).map(loc -> {
            Node node = loc.node();
            int start = loc.nodeStart() + node.leadingWidth;
            return documentBuffer.convertToRange(start, node.value.length());
        });
    }

    public List<Range> getOccurrences(Position position) {
        Optional<IncHelpers.NodeLocation> target = identifierTarget(position);
        if (target.isEmpty()) {
            return List.of();
        }

        IncHelpers.NodeLocation loc = target.get();
        Node node = loc.node();
        ScopedDefn defn = nameResolver.definitionForIdentifier(node).orElseThrow();

        // The definition's start is free from the cursor location when the cursor sits on
        // the definition itself; otherwise it costs a single climb to the global scope.
        int defNodeStart = (node.id == defn.defNode.id)
                ? loc.nodeStart()
                : nameResolver.absoluteOffsetOf(defn.defNode);

        List<NameResolver.Span> spans = nameResolver.renameSpans(defn, defNodeStart);
        List<Range> ranges = new ArrayList<>(spans.size());
        for (NameResolver.Span span : spans) {
            ranges.add(documentBuffer.convertToRange(span.offset(), span.length()));
        }

        return ranges;
    }

    public CompletableFuture<List<CompletionItem>> getVisibleNamesWhenClean(Position position) {
        return whenCleanGate().thenApply(_ -> getVisibleNames(position));
    }

    public CompletableFuture<Optional<SignatureInformation>> getSignatureHelpWhenClean(Position position) {
        return whenCleanGate().thenApply(_ -> getSignatureHelp(position));
    }

    private CompletableFuture<Void> whenCleanGate() {
        CompletableFuture<Void> gate;
        synchronized (this) {
            gate = treeClean.copy();
        }

        return gate.completeOnTimeout(null, 4 * debounceDelayMs, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<List<Integer>> getSemanticTokensWhenClean() {
        return whenCleanGate().thenApply(_ -> semanticTokenizer.lspDataSnapshot(documentBuffer));
    }

    public CompletableFuture<List<DocumentSymbol>> getDocumentSymbolsWhenClean() {
        return whenCleanGate().thenApply(_ -> getDocumentSymbols());
    }

    public synchronized List<DocumentSymbol> getDocumentSymbols() {
        List<DocumentSymbol> topLevel = new ArrayList<>();
        record Frame(Node node, int startOffset, List<DocumentSymbol> siblings) {
        }

        // DFS preorder; definitions found inside a definition's subtree become its children
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(parseTree, 0, topLevel));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Node node = frame.node();
            List<DocumentSymbol> siblings = frame.siblings();

            DocumentSymbol symbol = definitionSymbol(node, frame.startOffset());
            if (symbol != null) {
                siblings.add(symbol);
                siblings = symbol.getChildren();
            }

            int childEnd = frame.startOffset() + node.getWidth();

            for (Node child : node.getChildren().reversed()) {
                int childStart = childEnd - child.getWidth();

                if (!child.isTerminal()) {
                    stack.push(new Frame(child, childStart, siblings));
                }

                childEnd = childStart;
            }
        }

        return topLevel;
    }

    private DocumentSymbol definitionSymbol(Node node, int nodeStart) {
        DocumentSymbol.Kind kind = switch (node.symbol) {
            case Symbol.TYPE_DEF -> DocumentSymbol.Kind.TYPE;
            case Symbol.VAR_DEF -> DocumentSymbol.Kind.VAR;
            case Symbol.FUN_DEF -> DocumentSymbol.Kind.FUN;
            default -> null;
        };

        if (kind == null) {
            return null;
        }

        Node idNode = null;
        int idStart = nodeStart;

        for (Node child : node.getChildren()) {
            if (child.symbol == Symbol.ID) {
                idNode = child;
                break;
            }
            idStart += child.getWidth();
        }

        // A definition without a usable name (still being typed) has no symbol
        if (idNode == null || idNode.kind != Kind.NORMAL || idNode.value == null) {
            return null;
        }

        Range range = documentBuffer.convertToRange(nodeStart + node.contentStart(), node.contentWidth());
        Range selectionRange = documentBuffer.convertToRange(idStart + idNode.leadingWidth, idNode.value.length());
        String detail = nameResolver.definitionForIdentifier(idNode).map(this::getSignature).orElse(null);

        return new DocumentSymbol(idNode.value, detail, kind, range, selectionRange);
    }

    public synchronized List<CompletionItem> getVisibleNames(Position position) {
        int cursorOffset = documentBuffer.convertToOffset(position);
        int searchOffset = Math.min(cursorOffset, this.parseTree.getWidth()) - 1;

        if (this.parseTree.getWidth() == 0 || searchOffset < 0) {
            return List.of();
        }

        IncHelpers.NodeLocation loc = IncHelpers.findTerminalAtOffset(this.parseTree, searchOffset);
        Node terminal = loc.node();

        int offsetInside = cursorOffset - loc.nodeStart() - terminal.leadingWidth;
        String prefix = "";
        boolean replace = false;

        if (terminal.kind == Kind.NORMAL && offsetInside > 0 && isWordLike(terminal) && offsetInside <= terminal.value.length()) {
            prefix = terminal.value.substring(0, offsetInside);
            replace = true;
        }

        List<CompletionItem> visibleNames = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int line = position.getLine();
        int cursorChar = position.getCharacter();
        int idStartChar = cursorChar - offsetInside;

        Range insertRange, replaceRange;

        if (replace) {
            insertRange = new Range(new Position(line, idStartChar), new Position(line, cursorChar));
            replaceRange = new Range(new Position(line, idStartChar), new Position(line, idStartChar + terminal.value.length()));
        } else {
            insertRange = new Range(position, position);
            replaceRange = insertRange;
        }

        Optional<List<CompletionItem>> fieldCompletions = fieldCompletions(terminal, prefix, insertRange, replaceRange);
        if (fieldCompletions.isPresent()) {
            return fieldCompletions.get();
        }

        Scope scope = nameResolver.findEnclosingScope(terminal);
        while (scope != null) {
            for (ScopedDefn defn : scope.localDefinitions()) {
                if (seen.add(defn.name) && defn.name.startsWith(prefix)) {

                    CompletionItem.Kind kind = switch (defn.kind) {
                        case FUN -> CompletionItem.Kind.FUNCTION;
                        case TYPE -> CompletionItem.Kind.TYPE;
                        case VAR, PARAM -> CompletionItem.Kind.VARIABLE;
                    };

                    visibleNames.add(new CompletionItem(defn.name, getSignature(defn), kind, insertRange, replaceRange));
                }
            }

            scope = scope.parent;
        }

        return visibleNames;
    }

    private Optional<List<CompletionItem>> fieldCompletions(Node terminal, String prefix, Range insertRange, Range replaceRange) {
        // "record.|" or "record.fi|eld": the DOT and the ID are siblings under a DOT prime
        if ((terminal.symbol != Symbol.DOT && terminal.symbol != Symbol.ID) || terminal.kind != Node.Kind.NORMAL) {
            return Optional.empty();
        }

        Node dotPrime = nameResolver.getParent(terminal);
        if (!isPrimeStartingWith(dotPrime, Symbol.DOT)) {
            return Optional.empty();
        }

        Node postfixExpr = enclosingPostfixExpr(terminal);
        if (postfixExpr == null) {
            return Optional.empty();
        }

        // Type of the receiver (chain before the DOT). A dot context with no known
        // record type offers nothing, rather than falling back to visible names.
        Optional<Type.RecType> recType = typeChecker.recordType(postfixExpr, dotPrime);
        if (recType.isEmpty()) {
            return Optional.of(List.of());
        }

        List<String> names = recType.get().compNames();
        List<Type> types = recType.get().compTypes();
        List<CompletionItem> fields = new ArrayList<>(names.size());

        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).startsWith(prefix)) {
                fields.add(new CompletionItem(names.get(i), names.get(i) + ": " + types.get(i), CompletionItem.Kind.FIELD, insertRange, replaceRange));
            }
        }

        return Optional.of(fields);
    }

    public Optional<SignatureInformation> getSignatureHelp(Position position) {
        int cursorOffset = documentBuffer.convertToOffset(position);
        int searchOffset = Math.min(cursorOffset, this.parseTree.getWidth()) - 1;

        if (this.parseTree.getWidth() == 0 || searchOffset < 0) {
            return Optional.empty();
        }

        IncHelpers.NodeLocation loc = IncHelpers.findTerminalAtOffset(this.parseTree, searchOffset);
        Node terminal = loc.node();

        // e.g. in `f(g(1)|, x)` the cursor belongs to f, not the closed call to g
        Node callPrime = enclosingCall(terminal, cursorOffset);
        if (callPrime == null) {
            return Optional.empty();
        }

        Node postfixExpr = enclosingPostfixExpr(callPrime);
        if (postfixExpr == null) {
            return Optional.empty();
        }

        Optional<Type.FunType> maybeFun = typeChecker.funType(postfixExpr, callPrime);
        if (maybeFun.isEmpty()) {
            return Optional.empty();
        }
        Type.FunType funType = maybeFun.get();

        int activeParameter = activeParameter(callPrime, cursorOffset);

        // Named callee: use parameter names; otherwise fall back to bare types.
        List<String> params = namedCalleeParams(postfixExpr, funType).orElseGet(() -> funType.paramTypes().stream().map(Type::toString).toList());
        String name = namedCallee(postfixExpr).map(defn -> " " + defn.name).orElse("");
        String label = "fun" + name + "(" + String.join(", ", params) + "): " + funType.returnType();

        return Optional.of(new SignatureInformation(label, params, activeParameter));
    }

    /** Innermost call prime whose parens contain the cursor (skips already-closed nested calls). */
    private Node enclosingCall(Node node, int cursorOffset) {
        while (node.id != parseTree.id && nameResolver.hasParent(node)) {
            node = nameResolver.getParent(node);
            if (isPrimeStartingWith(node, Symbol.LPAREN) && callContainsCursor(node, cursorOffset)) {
                return node;
            }
        }
        return null;
    }

    /** Active parameter = this call's own commas (not nested ones) before the cursor. */
    private int activeParameter(Node callPrime, int cursorOffset) {
        Node eexprs = TypeNav.firstChild(callPrime, Symbol.EEXPRS);
        if (eexprs.isEpsilon()) {
            return 0;
        }

        int active = 0;
        for (Node child : TypeNav.firstChild(eexprs, Symbol.EXPRS).getChildren()) {
            if (child.symbol == Symbol.COMMA && nameResolver.absoluteOffsetOf(child) + child.leadingWidth < cursorOffset) {
                active++;
            }
        }
        return active;
    }

    /** The FUN definition when the callee is a plain identifier. */
    private Optional<ScopedDefn> namedCallee(Node postfixExpr) {
        List<Node> primaryChildren = TypeNav.firstChild(postfixExpr, Symbol.PRIMARY_EXPR).getChildren();
        if (primaryChildren.isEmpty() || primaryChildren.getFirst().symbol != Symbol.ID) {
            return Optional.empty();
        }
        return nameResolver.definitionForIdentifier(primaryChildren.getFirst())
                .filter(defn -> defn.kind == ScopedDefn.Kind.FUN);
    }

    /** Parameter labels with names, when the callee is a named function with matching arity. */
    private Optional<List<String>> namedCalleeParams(Node postfixExpr, Type.FunType funType) {
        return namedCallee(postfixExpr)
                .filter(defn -> paramNames(defn).size() == funType.paramTypes().size())
                .map(defn -> paramLabels(defn, funType));
    }

    /** A postfix_expr' whose first child is the given operator (DOT for field access, LPAREN for a call). */
    private static boolean isPrimeStartingWith(Node node, Symbol op) {
        return node.symbol == Symbol.POSTFIX_EXPR_PRIME
                && !node.getChildren().isEmpty()
                && node.getChildren().getFirst().symbol == op;
    }

    /** Climb from a node inside a postfix chain to the enclosing POSTFIX_EXPR, or null if none. */
    private Node enclosingPostfixExpr(Node node) {
        while (node.symbol != Symbol.POSTFIX_EXPR) {
            if (node.id == parseTree.id || !nameResolver.hasParent(node)) {
                return null;
            }
            node = nameResolver.getParent(node);
        }
        return node;
    }

    /** Is the cursor between this call's parens (treating a missing RPAREN as still open)? */
    private boolean callContainsCursor(Node callPrime, int cursorOffset) {
        Node lparen = callPrime.getChildren().getFirst();
        int lparenChar = nameResolver.absoluteOffsetOf(lparen) + lparen.leadingWidth;

        if (cursorOffset <= lparenChar) {
            return false;
        }

        for (Node child : callPrime.getChildren()) {
            if (child.symbol == Symbol.RPAREN && child.kind == Kind.NORMAL) {
                return cursorOffset <= nameResolver.absoluteOffsetOf(child) + child.leadingWidth;
            }
        }

        return true;
    }

    public Optional<String> getHover(Position position) {
        Optional<IncHelpers.NodeLocation> target = identifierTarget(position);
        if (target.isEmpty()) {
            return Optional.empty();
        }

        Node node = target.get().node();
        ScopedDefn defn = nameResolver.definitionForIdentifier(node).orElseThrow();
        String signature = getSignature(defn);

        return Optional.of("```prev26\n" + signature + "\n```");
    }

    private String getSignature(ScopedDefn defn) {
        Type type = typeChecker.typeOf(defn);

        return switch (defn.kind) {
            case TYPE -> "typ " + defn.name + " = " + type.toString();
            case VAR -> "var " + defn.name + ": " + type.toString();
            case FUN -> {
                if (type instanceof Type.FunType funType) {
                    yield "fun " + defn.name + "(" + String.join(", ", paramLabels(defn, funType)) + "): " + funType.returnType().toString();
                }

                // Don't output type on unknown
                yield "fun " + defn.name;
            }
            case PARAM -> defn.name + ": " + type.toString();
        };
    }

    private List<String> paramLabels(ScopedDefn funDefn, Type.FunType funType) {
        List<Type> paramTypes = funType.paramTypes();
        List<String> paramNames = paramNames(funDefn);

        List<String> labels = new ArrayList<>();

        for (int i = 0; i < paramTypes.size(); i++) {
            labels.add(paramNames.get(i) + ": " + paramTypes.get(i).toString());
        }

        return labels;
    }

    private List<String> paramNames(ScopedDefn funDefn) {
        Node funNode = nameResolver.getParent(funDefn.defNode);
        Node eparams = TypeNav.firstChild(funNode, Symbol.EPARAMS);

        if (eparams.isEpsilon())
            return List.of();
        Node paramsNode = TypeNav.firstChild(eparams, Symbol.PARAMS);

        List<Node> params = TypeNav.getChildren(paramsNode, Symbol.PARAM);
        List<String> names = new ArrayList<>(params.size());

        for (Node paramNode : params) {
            names.add(TypeNav.firstChild(paramNode, Symbol.ID).value);
        }

        return names;
    }

    /**
     * Resolve the identifier occurrence under the cursor that is a valid rename
     * target,
     * returning its parse-tree location (node + raw start offset), or empty if
     * there is none.
     */
    private Optional<IncHelpers.NodeLocation> identifierTarget(Position position) {
        int cursorOffset = documentBuffer.convertToOffset(position);

        if (cursorOffset > this.parseTree.getWidth() || this.parseTree.getWidth() == 0) {
            return Optional.empty();
        }

        int lookupOffset = cursorOffset;
        if (lookupOffset == this.parseTree.getWidth()) {
            lookupOffset -= 1;
        }

        IncHelpers.NodeLocation loc = IncHelpers.findTerminalAtOffset(this.parseTree, lookupOffset);
        if (isRenameTarget(loc.node(), loc.nodeStart(), cursorOffset)) {
            return Optional.of(loc);
        }

        // Retry one character to the left in "abc|(" case
        IncHelpers.NodeLocation prev = findPreviousTerminal(loc);
        if (prev != null && isRenameTarget(prev.node(), prev.nodeStart(), cursorOffset)) {
            return Optional.of(prev);
        }

        return Optional.empty();
    }

    private boolean isRenameTarget(Node node, int nodeStart, int cursorOffset) {
        // Only identifiers can be renamed
        if (node.symbol != Symbol.ID || node.kind != Node.Kind.NORMAL) {
            return false;
        }

        int start = nodeStart + node.leadingWidth;
        int end = start + node.value.length();

        if (cursorOffset < start || cursorOffset > end) {
            return false;
        }

        // It must be a valid definition/reference
        return nameResolver.definitionForIdentifier(node).isPresent();
    }

    private void reparse() {
        try {
            doReparse();
        } finally {
            CompletableFuture<Void> gate;
            synchronized (this) {
                gate = treeClean;
            }
            // Complete outside the lock so continuations don't run under it
            gate.complete(null);
        }
    }

    private synchronized void doReparse() {
        int oldDocumentLength = this.parsedDocumentLength;
        int newDocumentLength = documentBuffer.getDocumentLength();
        int documentDeltaLength = newDocumentLength - oldDocumentLength;

        // Clamp
        unchangedStartLen = Math.min(unchangedStartLen, Math.min(oldDocumentLength, newDocumentLength));
        unchangedEndLen = Math.min(unchangedEndLen, Math.min(oldDocumentLength, newDocumentLength));

        Node newRoot;

        if (parseTree.hasNoChildren()) {
            String fullText = documentBuffer.getFullText();
            List<Node> nodes = lex(fullText);

            newRoot = Parser.parse(nodes);
            nameResolver.resolveFull(newRoot);
            typeChecker.computeFull(newRoot, nameResolver);
            semanticTokenizer.rebuildFull(newRoot, nameResolver);
        } else {
            // In old tree coordinates
            int changeStartOld = unchangedStartLen;
            int changeEndNew = newDocumentLength - unchangedEndLen;

            int editLineStartOld = documentBuffer.lineStartOffset(changeStartOld); // identical in both tree coordinates
            int nextLineStartOld = documentBuffer.nextLineStartOffset(changeEndNew) - documentDeltaLength; // Convert back to old

            IncHelpers.ReparsePlan plan = IncHelpers.planReparse(parseTree, oldDocumentLength, documentDeltaLength,
                    editLineStartOld, nextLineStartOld);
            String changedPart = documentBuffer.read(plan.lexStart(), plan.lexEnd() - plan.lexStart());

            // Logger.info("Changed part: " + changedPart);
            List<Node> changeStr = lex(changedPart);

            Parser.ParseResult result = Parser.incrementalParseWithDelta(parseTree, plan.leftKeep(), plan.rightCut(), changeStr);
            newRoot = result.root();

            NameDelta nameDelta = nameResolver.reanalyze(result.root(), result.delta());
            typeChecker.recompute(result.root(), nameResolver, result.delta(), nameDelta);
            semanticTokenizer.applyUpdate(nameResolver, nameDelta, plan.lexStart(), plan.lexEnd(), documentDeltaLength);
        }

        // Update the root
        parseTree = newRoot;
        parsedDocumentLength = newDocumentLength;
        unchangedStartLen = Integer.MAX_VALUE;
        unchangedEndLen = Integer.MAX_VALUE;

        CollectedDiagnostics syntax = collectDiagnostics(parseTree);
        List<Diagnostic> diagnostics = syntax.diagnostics();

        int suppressed = syntax.suppressed()
                + addCapped(diagnostics, nameResolver.collectDiagnostics())
                + addCapped(diagnostics, typeChecker.collectDiagnostics());

        // Logger.info("PARSE TREE\n" + parseTree.printTree());
        List<org.eclipse.lsp4j.Diagnostic> lspDiags = new ArrayList<>(diagnostics.size() + 1);

        for (Diagnostic diag : diagnostics) {
            lspDiags.add(diag.toLspDiagnostic(documentBuffer));
        }

        if (suppressed > 0) {
            lspDiags.add(new Diagnostic("prev26", suppressed + " more problems are not shown.",
                    0, 0, Diagnostic.Severity.INFORMATION, false).toLspDiagnostic(documentBuffer));
        }

        DiagnosticsPublisher.publishDiagnostics(documentId, lspDiags);
    }

    private static List<Node> lex(String text) {
        List<Node> nodes = new ArrayList<>();
        Lexer lexer = new Lexer(new StringReader(text));

        while (true) {
            Token token = lexer.nextToken();
            if (token.type == Symbol.EOF) {
                break;
            }
            nodes.add(Node.fromToken(token));
        }

        return nodes;
    }

    /** The diagnostics that fit under {@link #maxPublishedDiagnostics}, and how many did not. */
    private record CollectedDiagnostics(List<Diagnostic> diagnostics, int suppressed) {
    }

    /**
     * Appends as many of {@code from} as there is room for, and returns how many were left out.
     * The messages themselves are already built by the time we get here, so this only bounds what
     * goes on the wire.
     */
    private static int addCapped(List<Diagnostic> into, List<Diagnostic> from) {
        int room = Math.max(maxPublishedDiagnostics - into.size(), 0);
        if (from.size() <= room) {
            into.addAll(from);
            return 0;
        }

        into.addAll(from.subList(0, room));
        return from.size() - room;
    }

    private CollectedDiagnostics collectDiagnostics(Node root) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int suppressed = 0;
        int documentLength = documentBuffer.getDocumentLength();
        record Frame(Node node, int startOffset) {
        }

        // DFS pruned on !isTainted
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, 0));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Node node = frame.node();
            int nodeStart = frame.startOffset();

            if (node.isErrorNode() && diagnostics.size() >= maxPublishedDiagnostics) {
                // Past the cap, keep walking to get an honest count but stop paying for the
                // message strings: a file that is not PREV at all has one error node per token.
                suppressed++;
            } else if (node.isErrorNode()) {
                // Highlight only the node's content, not its surrounding trivia.
                int errorOffset = nodeStart + node.contentStart();
                int width = node.contentWidth();
                // A missing token is zero-width: widen to 1 so the squiggle is visible,
                // but keep the range inside the document. A missing token at EOF would
                // otherwise point one past the end; squiggle the last character instead.
                if (width == 0) {
                    if (errorOffset < documentLength) {
                        width = 1;
                    } else if (errorOffset > 0) {
                        errorOffset -= 1;
                        width = 1;
                    }
                }

                diagnostics.add(new Diagnostic("prev26syn", errorMessage(node), errorOffset, width,
                        Diagnostic.Severity.ERROR, false));
            }

            // End of last child = start of parent + width of parent
            int childEnd = nodeStart + node.getWidth();

            for (Node child : node.getChildren().reversed()) {
                // Start of child = end - width
                int childStart = childEnd - child.getWidth();

                if (child.isTainted()) {
                    stack.push(new Frame(child, childStart));
                }

                // Start of 2nd child is end of 1st one
                childEnd = childStart;
            }
        }

        return new CollectedDiagnostics(diagnostics, suppressed);
    }

    /** Human-readable message for a syntax-error node. */
    private static String errorMessage(Node node) {
        if (node.lexError != null) {
            return node.lexError;
        }

        return switch (node.kind) {
            // Parser expected this symbol but the input didn't have it.
            case ERR_MISSING -> "Expected " + ErrorMessages.describeSymbol(node.symbol);

            // An actual token the parser couldn't use here. When we know which
            // non-terminal rejected it, also say what would have been legal.
            case ERR_UNEXPECTED -> {
                String found = ErrorMessages.describeFound(node.symbol, node.value);
                yield (node.errorContext != null)
                        ? "Unexpected " + found + ", expected " + ErrorMessages.describeContext(node.errorContext)
                        : "Unexpected " + found;
            }

            case NORMAL -> throw new IllegalStateException("Normal nodes shouldn't have an error");
        };
    }

    public void close() {
        scheduler.shutdownNow();
    }

    private static boolean isWordLike(Node terminal) {
        return terminal.value != null && !terminal.value.isEmpty() && Character.isLetter(terminal.value.charAt(0));
    }

}
