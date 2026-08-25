package prev26lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import prev26lsp.lsp.LanguageServerImpl;

public class ServerLauncher {

    static void main() {
        LanguageServerImpl server = new LanguageServerImpl();

        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());

        launcher.startListening();
    }

}
