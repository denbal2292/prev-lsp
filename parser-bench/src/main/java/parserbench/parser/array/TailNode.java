package parserbench.parser.array;

import parserbench.parser.ParseTable;
import parserbench.parser.Symbol;

import java.util.List;

/** A flattened grammar tail whose old children can be attached in one addAll. */
final class TailNode extends Node {

    final List<Node> tail;
    /** Kept so a mismatched tail can re-anchor its remainder instead of expanding wholesale. */
    final ParseTable.FlatListInfo info;

    TailNode(Symbol tailSymbol, List<Node> tail, int width, ParseTable.FlatListInfo info) {
        super(tailSymbol, tail, width);
        this.tail = tail;
        this.info = info;
    }
}
