package parserbench.parser.treap;

import parserbench.parser.ParseTable;
import parserbench.parser.Symbol;

final class TailNode extends Node {

    final TreapChildren tail;
    /** Store what can restart the tail. */
    final ParseTable.FlatListInfo info;

    TailNode(Symbol tailSymbol, TreapChildren tail, ParseTable.FlatListInfo info) {
        super(tailSymbol, Kind.NORMAL, tail);
        this.tail = tail;
        this.info = info;
    }
}
