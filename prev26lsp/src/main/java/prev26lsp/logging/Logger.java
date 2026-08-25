package prev26lsp.logging;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;

public class Logger {

    private static LanguageClient client;

    public static void setClient(LanguageClient client) {
        Logger.client = client;
    }

    private static void logMessage(MessageType type, String message) {
        if (client != null) {
            client.logMessage(new MessageParams(type, message));
        }
    }

    public static void info(String message) {
        logMessage(MessageType.Info, message);
    }

    public static void warning(String message) {
        logMessage(MessageType.Warning, message);
    }

    public static void error(String message) {
        logMessage(MessageType.Error, message);
    }

}
