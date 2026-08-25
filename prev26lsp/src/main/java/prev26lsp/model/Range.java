package prev26lsp.model;

public class Range {

    private final Position start;
    private final Position end;

    public Range(Position start, Position end) {
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("Start position must be before end position");
        }

        this.start = start;
        this.end = end;
    }

    public Range(org.eclipse.lsp4j.Range lspRange) {
        this.start = new Position(lspRange.getStart());
        this.end = new Position(lspRange.getEnd());
    }

    public org.eclipse.lsp4j.Range toLspRange() {
        return new org.eclipse.lsp4j.Range(start.toLspPosition(), end.toLspPosition());
    }

    public Position getStart() {
        return start;
    }

    public Position getEnd() {
        return end;
    }

    public boolean contains(Position position) {
        return start.compareTo(position) <= 0 && end.compareTo(position) >= 0;
    }

}
