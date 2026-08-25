package prev26lsp.diagnostics;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;

public class DiagnosticsPublisher {

    private static LanguageClient client;

    public static void setClient(LanguageClient client) {
        DiagnosticsPublisher.client = client;
    }

    public static void publishDiagnostics(String documentUri, List<Diagnostic> diagnostics) {
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(documentUri, diagnostics));
        }
    }

}