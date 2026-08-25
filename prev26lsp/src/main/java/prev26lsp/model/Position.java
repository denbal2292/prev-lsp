package prev26lsp.model;

public class Position implements Comparable<Position> {

    private final int line;
    private final int character;

    public Position(int line, int character) {
        this.line = line;
        this.character = character;
    }

    public Position(org.eclipse.lsp4j.Position lspPosition) {
        this.line = lspPosition.getLine();
        this.character = lspPosition.getCharacter();
    }

    public int getLine() {
        return line;
    }

    public int getCharacter() {
        return character;
    }

    public org.eclipse.lsp4j.Position toLspPosition() {
        return new org.eclipse.lsp4j.Position(this.line, this.character);
    }

    @Override
    public int compareTo(Position other) {
        if (this.line != other.line) {
            return Integer.compare(this.line, other.line);
        }
        return Integer.compare(this.character, other.character);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Position)) {
            return false;
        }
        Position other = (Position) obj;
        return this.line == other.line && this.character == other.character;
    }

    @Override
    public int hashCode() {
        return 31 * line + character;
    }

    @Override
    public String toString() {
        return "Position(" + line + ", " + character + ")";
    }

}
