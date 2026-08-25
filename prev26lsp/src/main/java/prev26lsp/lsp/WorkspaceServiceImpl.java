package prev26lsp.lsp;

import org.eclipse.lsp4j.services.WorkspaceService;

import prev26lsp.logging.Logger;

import org.eclipse.lsp4j.*;

public class WorkspaceServiceImpl implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        Logger.info("Configuration changed: " + params.getSettings().toString());
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        Logger.info("Watched files changed: " + params.getChanges().toString());
    }
}
