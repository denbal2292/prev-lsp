package prev26lsp.model;

public class DocumentEdit {

    private final Range range;
    private final String newText;

    public DocumentEdit(Range range, String newText) {
        this.range = range;
        this.newText = newText;
    }

    public DocumentEdit(org.eclipse.lsp4j.TextDocumentContentChangeEvent lspChange) {
        this.range = new Range(lspChange.getRange());
        this.newText = lspChange.getText();
    }

    public Position getStart() {
        return this.range.getStart();
    }

    public Position getEnd() {
        return this.range.getEnd();
    }

    public Range getRange() {
        return range;
    }

    public String getNewText() {
        return newText;
    }

}
