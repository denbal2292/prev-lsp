package prev26lsp.model;

import org.eclipse.lsp4j.SymbolKind;

import java.util.ArrayList;
import java.util.List;

public class DocumentSymbol {

    public enum Kind {
        TYPE,
        VAR,
        FUN
    }

    private final String name;
    private final String detail;
    private final Kind kind;
    private final Range range;
    private final Range selectionRange;
    private final List<DocumentSymbol> children = new ArrayList<>();

    public DocumentSymbol(String name, String detail, Kind kind, Range range, Range selectionRange) {
        this.name = name;
        this.detail = detail;
        this.kind = kind;
        this.range = range;
        this.selectionRange = selectionRange;
    }

    public List<DocumentSymbol> getChildren() {
        return children;
    }

    public org.eclipse.lsp4j.DocumentSymbol toLspDocumentSymbol() {
        org.eclipse.lsp4j.DocumentSymbol symbol = new org.eclipse.lsp4j.DocumentSymbol();

        symbol.setName(name);
        symbol.setDetail(detail);
        symbol.setKind(toLspSymbolKind(kind));
        symbol.setRange(range.toLspRange());
        symbol.setSelectionRange(selectionRange.toLspRange());

        List<org.eclipse.lsp4j.DocumentSymbol> lspChildren = new ArrayList<>(children.size());
        for (DocumentSymbol child : children) {
            lspChildren.add(child.toLspDocumentSymbol());
        }
        symbol.setChildren(lspChildren);

        return symbol;
    }

    private static SymbolKind toLspSymbolKind(Kind kind) {
        return switch (kind) {
            case TYPE -> SymbolKind.Class;
            case VAR -> SymbolKind.Variable;
            case FUN -> SymbolKind.Function;
        };
    }

}
