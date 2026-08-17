package org.hswebframework.reactor.excel.csv;

import org.hswebframework.reactor.excel.ExcelOption;

/**
 * Limits the encoded size of one cell in the reactive CSV writer.
 *
 * <p>Reactive Streams demand bounds the number of source cells, but it cannot bound the size of
 * one cell. This option caps that remaining per-cell memory boundary. It does not affect the
 * {@code OutputStream} CSV writer.</p>
 *
 * @since 1.0.7
 */
public final class MaxEncodedCellBytesOption implements ExcelOption {

    public static final int DEFAULT_MAX_ENCODED_CELL_BYTES = 16 * 1024 * 1024;

    private final int maxEncodedCellBytes;

    private MaxEncodedCellBytesOption(int maxEncodedCellBytes) {
        if (maxEncodedCellBytes <= 0) {
            throw new IllegalArgumentException("maxEncodedCellBytes must be greater than zero");
        }
        this.maxEncodedCellBytes = maxEncodedCellBytes;
    }

    /**
     * Create a per-cell encoded byte limit for the reactive CSV path.
     *
     * @param maxEncodedCellBytes maximum encoded bytes retained for one source cell
     * @return size-limit option
     */
    public static MaxEncodedCellBytesOption of(int maxEncodedCellBytes) {
        return new MaxEncodedCellBytesOption(maxEncodedCellBytes);
    }

    /**
     * @return maximum encoded bytes retained for one source cell
     */
    public int getMaxEncodedCellBytes() {
        return maxEncodedCellBytes;
    }
}
