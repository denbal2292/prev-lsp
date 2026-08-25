package prev26lsp.document;

import prev26lsp.model.DocumentEdit;
import prev26lsp.model.Position;
import prev26lsp.model.Range;

public interface LineIndex {

    /**
     * Converts a Position (line and character) to an offset in the document text.
     *
     * @param position the Position to convert
     * @return the character offset corresponding to the given position
     */
    int convertToOffset(Position position);

    /**
     * Converts a character offset to a Position (line and character) in the
     * document.
     *
     * @param offset the character offset to convert
     * @return the Position corresponding to the given character offset
     */
    Position convertToPosition(int offset);

    /**
     * Converts start and end offsets into a @code{Range} within the document.
     *
     * @param start zero-based start character offset
     * @param length number of characters in the range
     * @return the corresponding range
     */
    default Range convertToRange(int start, int length) {
        return new Range(convertToPosition(start), convertToPosition(start + length));
    }

    /**
     * Finds the offset of the first character of the line containing {@code offset}.
     */
    default int lineStartOffset(int offset) {
        int line = convertToPosition(offset).getLine();
        return convertToOffset(new Position(line, 0));
    }

    /**
     * Offset of the first character of the line *after* the one containing
     * {@code offset} or the document length in case of the last line.
     */
    default int nextLineStartOffset(int offset) {
        int line = convertToPosition(offset).getLine();

        if (line + 1 >= getLineCount()) {
            return getDocumentLength();
        }

        return convertToOffset(new Position(line + 1, 0));
    }

    /**
     * Updates the LineIndex based on a DocumentEdit. This method should be called
     * whenever a DocumentEdit is applied to the document, so that the LineIndex can
     * maintain accurate mappings between positions and offsets.
     *
     * @param edit the DocumentEdit to apply
     */
    void applyEdit(DocumentEdit edit);

    /**
     * Returns the total length of the document in characters.
     *
     * @return the length of the document in characters
     */
    int getDocumentLength();

    /**
     * Returns the total number of lines in the document.
     *
     * @return the number of lines in the document
     */
    int getLineCount();

}
