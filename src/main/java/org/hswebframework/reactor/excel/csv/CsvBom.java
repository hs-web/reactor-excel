package org.hswebframework.reactor.excel.csv;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Selects the byte-order mark for CSV charsets whose encoder does not emit one itself.
 *
 * <p>{@code UTF-16} is intentionally absent because its encoder writes the BOM with the first
 * encoded character. Non-Unicode charsets receive no prefix.</p>
 */
final class CsvBom {

    private static final byte[] EMPTY = new byte[0];

    private static final byte[] UTF_8 = {
        (byte) 0xef, (byte) 0xbb, (byte) 0xbf
    };

    private static final byte[] UTF_16_BE = {
        (byte) 0xfe, (byte) 0xff
    };

    private static final byte[] UTF_16_LE = {
        (byte) 0xff, (byte) 0xfe
    };

    private static final byte[] UTF_32_BE = {
        0, 0, (byte) 0xfe, (byte) 0xff
    };

    private static final byte[] UTF_32_LE = {
        (byte) 0xff, (byte) 0xfe, 0, 0
    };

    private CsvBom() {
    }

    static byte[] bytes(Charset charset) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return UTF_8;
        }
        String name = charset.name();
        if ("UTF-16BE".equalsIgnoreCase(name)) {
            return UTF_16_BE;
        }
        if ("UTF-16LE".equalsIgnoreCase(name)) {
            return UTF_16_LE;
        }
        if ("UTF-32BE".equalsIgnoreCase(name)) {
            return UTF_32_BE;
        }
        if ("UTF-32LE".equalsIgnoreCase(name)) {
            return UTF_32_LE;
        }
        return EMPTY;
    }
}
