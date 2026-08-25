package prev26lsp.document;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DocumentSessionRegistry {

    private final Map<String, DocumentSession> sessions;
    private final DocumentBufferFactory documentBufferFactory;

    public DocumentSessionRegistry(DocumentBufferFactory documentBufferFactory) {
        this.sessions = new ConcurrentHashMap<>();
        this.documentBufferFactory = documentBufferFactory;
    }

    public DocumentSession getSession(String documentId) {
        return sessions.get(documentId);
    }

    public void registerSession(String documentId, String initialText) {
        DocumentSession session = new DocumentSession(documentId, initialText, documentBufferFactory);
        sessions.put(documentId, session);
    }

    public void unregisterSession(String documentId) {
        DocumentSession session = sessions.get(documentId);
        if (session != null) {
            session.close();
        }

        sessions.remove(documentId);
    }

}