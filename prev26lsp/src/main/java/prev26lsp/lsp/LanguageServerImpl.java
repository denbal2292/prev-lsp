package prev26lsp.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import prev26lsp.diagnostics.DiagnosticsPublisher;
import prev26lsp.logging.Logger;
import prev26lsp.semantics.SemanticTokenizer;

public class LanguageServerImpl implements LanguageServer, LanguageClientAware {

    private final TextDocumentServiceImpl textDocumentService;
    private final WorkspaceServiceImpl workspaceService;

    public LanguageServerImpl() {
        this.textDocumentService = new TextDocumentServiceImpl();
        this.workspaceService = new WorkspaceServiceImpl();
    }

    @Override
    public void connect(LanguageClient client) {
        Logger.setClient(client);
        DiagnosticsPublisher.setClient(client);
        Logger.info("Connected to client");
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();

        // declare capabilities here
        TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
        syncOptions.setOpenClose(true);
        syncOptions.setChange(TextDocumentSyncKind.Incremental);
        // syncOptions.setSave(true);
        caps.setTextDocumentSync(syncOptions);

        caps.setRenameProvider(new RenameOptions(true)); // We support prepareRename
        caps.setDefinitionProvider(true);
        caps.setReferencesProvider(true);
        caps.setDocumentHighlightProvider(true);
        caps.setHoverProvider(true);
        caps.setDocumentSymbolProvider(true);

        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(false); // We won't provide any additional info
        completionOptions.setTriggerCharacters(List.of("."));
        caps.setCompletionProvider(completionOptions);

        SignatureHelpOptions signatureOptions = new SignatureHelpOptions();
        signatureOptions.setTriggerCharacters(List.of("("));
        signatureOptions.setRetriggerCharacters(List.of(","));
        caps.setSignatureHelpProvider(signatureOptions);

        SemanticTokensLegend tokensLegend = new SemanticTokensLegend(SemanticTokenizer.TOKEN_TYPES, SemanticTokenizer.TOKEN_MODIFIERS);
        SemanticTokensWithRegistrationOptions semanticTokensOptions = new SemanticTokensWithRegistrationOptions();
        semanticTokensOptions.setLegend(tokensLegend);
        semanticTokensOptions.setFull(true);
        caps.setSemanticTokensProvider(semanticTokensOptions);

        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this.textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this.workspaceService;
    }

}