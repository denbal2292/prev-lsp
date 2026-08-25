package prev26lsp.document;

import prev26lsp.model.DocumentEdit;

/**
 * The text of one open document: its raw content, plus the line/offset mapping
 * the language server needs to answer requests in LSP coordinates.
 */
public interface DocumentBuffer extends LineIndex {

    String getFullText();

    String read(int offset, int length);

    @Override
    void applyEdit(DocumentEdit edit);

}
