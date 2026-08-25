package prev26lsp.util;

import it.unimi.dsi.fastutil.ints.IntList;

public class IntSearch {

    private IntSearch() {}

    /**
     * Finds the index of the first element greater than {@code key}.
     */
    public static int upperBound(IntList arr, int key) {
        int low = 0;
        int high = arr.size();

        while (low < high) {
            int middle = (low + high) >>> 1; // Shift because unsigned

            if (arr.getInt(middle) <= key) {
                // Search to the right
                // (middle is smaller or equal, look at next element)
                low = middle + 1;
            } else {
                // Search to the left
                high = middle;
            }
        }

        return low;
    }

}
