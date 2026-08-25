package prev26lsp.parser;

import java.util.List;

/**
 * The incremental parse contract: the new spine (id-preserving copies of the
 * old path from the change to the root), the subtrees dropped from the old
 * tree, and the id watermark separating newly created nodes
 * ({@code id >= createdIdFloor}) from reused ones.
 */
public record ParseDelta(List<Node> spine, List<Node> dropped, int createdIdFloor) {

    public boolean isEmpty() {
        return spine.isEmpty() && dropped.isEmpty();
    }

}
