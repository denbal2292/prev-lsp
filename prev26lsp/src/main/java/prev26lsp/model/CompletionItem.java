package prev26lsp.model;

import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class CompletionItem {

    public enum Kind {
        FUNCTION,
        FIELD,
        VARIABLE,
        KEYWORD,
        TYPE
    }

    private final String label;
    private final String detail;
    private final Kind kind;
    private final Range insertRange;
    private final Range replaceRange;

    public CompletionItem(String label, String detail, Kind kind, Range insertRange, Range replaceRange) {
        this.label = label;
        this.detail = detail;
        this.kind = kind;
        this.insertRange = insertRange;
        this.replaceRange = replaceRange;
    }

    public org.eclipse.lsp4j.CompletionItem toLspCompletionItem() {
        org.eclipse.lsp4j.CompletionItem item = new org.eclipse.lsp4j.CompletionItem();

        item.setLabel(label);
        item.setDetail(detail);
        item.setTextEdit(Either.forRight(
            new InsertReplaceEdit(label, insertRange.toLspRange(), replaceRange.toLspRange()) // Keep identifier beginning, replace its end
        ));
        item.setKind(toLspCompletionItemKind(kind));

        return item;
    }

    private static CompletionItemKind toLspCompletionItemKind(Kind kind) {
        return switch (kind) {
            case FUNCTION -> CompletionItemKind.Function;
            case FIELD -> CompletionItemKind.Field;
            case VARIABLE -> CompletionItemKind.Variable;
            case KEYWORD -> CompletionItemKind.Keyword;
            case TYPE -> CompletionItemKind.Class; // Just map to class here
        };
    }

}
