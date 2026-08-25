package prev26lsp.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;

import prev26lsp.document.DocumentSession;
import prev26lsp.document.DocumentSessionRegistry;
import prev26lsp.document.AvlLineTreeDocumentBuffer;
import prev26lsp.logging.Logger;
import prev26lsp.model.DocumentEdit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Documents are synced by sending the full content on open.
 * After that only incremental updates to the document are
 * sent.
 */
public class TextDocumentServiceImpl implements TextDocumentService {

    private final DocumentSessionRegistry documentSessionRegistry;

    public TextDocumentServiceImpl() {
        this.documentSessionRegistry = new DocumentSessionRegistry(AvlLineTreeDocumentBuffer::new);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem openedDocument = params.getTextDocument();
        String initialText = openedDocument.getText();
        String documentUri = openedDocument.getUri();

        Logger.info("Opened: " + documentUri);

        // The initial analysis of a very large document can exhaust the heap. Without this the
        // Error escapes into lsp4j's listening thread, which then dies without a word: the JVM
        // has no non-daemon threads left, exits 0, and the client reports only that the
        // connection went away. Report it and leave the document unregistered instead.
        try {
            this.documentSessionRegistry.registerSession(documentUri, initialText);
        } catch (Throwable failure) {
            Logger.error(String.format("Failed to open %s (%d characters): %s",
                    documentUri, initialText.length(), failure));
        }
    }

    // https://microsoft.github.io/prev26lsp-protocol/specifications/lsp/3.17/specification/#textDocument_didChange
    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        VersionedTextDocumentIdentifier changedDocument = params.getTextDocument();
        String documentUri = changedDocument.getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);

        Logger.info("Changed: " + documentUri);
        long startTime = System.nanoTime();

        // Apply the `TextDocumentContentChangeEvent`s in a single notification in the
        // order you receive them.
        for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
            DocumentEdit edit = new DocumentEdit(change);
            session.applyEdit(edit);
        }

        long endTime = System.nanoTime();
        Logger.info(
                String.format("Took %.3f ms to apply %d edits\n---------", (double) (endTime - startTime) / 1_000_000,
                        params.getContentChanges().size()));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        TextDocumentIdentifier closedDocument = params.getTextDocument();
        String documentUri = closedDocument.getUri();

        Logger.info("Closed: " + documentUri);
        this.documentSessionRegistry.unregisterSession(documentUri);
    }

    // TODO: Maybe force any pending reparse here
    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) {
        TextDocumentIdentifier closedDocument = params.getTextDocument();
        String documentUri = closedDocument.getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        Optional<prev26lsp.model.Range> renameRange = session.getRenameRange(new prev26lsp.model.Position(params.getPosition()));

        if (renameRange.isEmpty()) {
            throw cannotRename();
        }

        return CompletableFuture.completedFuture(Either3.forFirst(renameRange.get().toLspRange()));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        String documentUri = params.getTextDocument().getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        List<prev26lsp.model.Range> ranges = session.getOccurrences(new prev26lsp.model.Position(params.getPosition()));

        if (ranges.isEmpty()) {
            throw cannotRename();
        }

        String newName = params.getNewName();
        List<TextEdit> edits = new ArrayList<>(ranges.size());
        for (prev26lsp.model.Range range : ranges) {
            edits.add(new TextEdit(range.toLspRange(), newName));
        }

        WorkspaceEdit edit = new WorkspaceEdit(Map.of(documentUri, edits));
        return CompletableFuture.completedFuture(edit);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String documentUri = params.getTextDocument().getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        Optional<prev26lsp.model.Range> defRange = session.getDefinitionRange(new prev26lsp.model.Position(params.getPosition()));

        if (defRange.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }

        Location location = new Location(documentUri, defRange.get().toLspRange());
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String documentUri = params.getTextDocument().getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        List<prev26lsp.model.Range> ranges = session.getOccurrences(new prev26lsp.model.Position(params.getPosition()));

        List<Location> locations = new ArrayList<>(ranges.size());
        for (prev26lsp.model.Range range : ranges) {
            locations.add(new Location(documentUri, range.toLspRange()));
        }

        return CompletableFuture.completedFuture(locations);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        String documentUri = params.getTextDocument().getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        List<prev26lsp.model.Range> ranges = session.getOccurrences(new prev26lsp.model.Position(params.getPosition()));

        List<DocumentHighlight> highlights = new ArrayList<>(ranges.size());
        for (prev26lsp.model.Range range : ranges) {
            // TODO: Distinguish between reads and writes
            highlights.add(new DocumentHighlight(range.toLspRange(), DocumentHighlightKind.Text));
        }

        return CompletableFuture.completedFuture(highlights);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String documentUri = params.getTextDocument().getUri();

        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);
        Optional<String> hoverText = session.getHover(new prev26lsp.model.Position(params.getPosition()));

        if (hoverText.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Hover hover = new Hover(new MarkupContent(MarkupKind.MARKDOWN, hoverText.get()));
        return CompletableFuture.completedFuture(hover);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String documentUri = params.getTextDocument().getUri();
        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);

        return session.getVisibleNamesWhenClean(new prev26lsp.model.Position(params.getPosition()))
                .thenApply(items -> {
                    List<CompletionItem> completionItems = new ArrayList<>(items.size());

                    for (prev26lsp.model.CompletionItem completionItem : items) {
                        completionItems.add(completionItem.toLspCompletionItem());
                    }

                    return Either.forLeft(completionItems);
                });
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        String documentUri = params.getTextDocument().getUri();
        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);

        return session.getSignatureHelpWhenClean(new prev26lsp.model.Position(params.getPosition()))
            .thenApply(help -> help.map(prev26lsp.model.SignatureInformation::toLspSignatureHelp).orElse(null));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        String documentUri = params.getTextDocument().getUri();
        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);

        return session.getDocumentSymbolsWhenClean().thenApply(symbols -> {
            List<Either<SymbolInformation, DocumentSymbol>> out = new ArrayList<>(symbols.size());

            for (prev26lsp.model.DocumentSymbol symbol : symbols) {
                out.add(Either.forRight(symbol.toLspDocumentSymbol()));
            }

            return out;
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String documentUri = params.getTextDocument().getUri();
        DocumentSession session = this.documentSessionRegistry.getSession(documentUri);

        return session.getSemanticTokensWhenClean().thenApply(SemanticTokens::new);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        Logger.info("Saved: " + params.getTextDocument().getUri());
    }

    private static ResponseErrorException cannotRename() {
        return new ResponseErrorException(
                new ResponseError(
                        ResponseErrorCode.InvalidParams,
                        "This element cannot be renamed",
                        null
                )
        );
    }

}