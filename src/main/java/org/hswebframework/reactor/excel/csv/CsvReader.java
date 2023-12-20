package org.hswebframework.reactor.excel.csv;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import lombok.SneakyThrows;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.input.BOMInputStream;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import reactor.core.publisher.Flux;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class CsvReader implements ExcelReader {

    @Override
    public String[] getSupportFormat() {
        return new String[]{"csv"};
    }

    private final static Charset DEFAULT_GB_CHARSET;

    static {
        Charset temp = null;
        String[] charsets = new String[]{"GB18030", "GBK", "GB2312"};
        for (String charset : charsets) {
            try {
                if (Charset.isSupported(charset)) {
                    temp = Charset.forName(charset);
                }
            } catch (Throwable ignore) {
            }
            if (temp != null) {
                break;
            }
        }
        DEFAULT_GB_CHARSET = temp;
    }

    private InputStream transformInputStream(InputStream stream) {
        if (stream instanceof BufferedInputStream) {
            return stream;
        }
        return new BufferedInputStream(stream);
    }

    @Override
    @SneakyThrows
    public Flux<CsvCell> read(InputStream inputStream, ExcelOption... options) {

        return Flux.create(sink -> {

            InputStream buffered = transformInputStream(inputStream);
            try (CSVParser parser = CSVFormat.EXCEL.parse(new InputStreamReader(
                    buffered,
                    detectCharset(buffered, options)))) {

                int rowIndex = 0;
                for (CSVRecord record : parser) {
                    if (sink.isCancelled()) {
                        break;
                    }
                    int last = record.size() - 1;
                    for (int i = 0; i < last; i++) {
                        sink.next(new CsvCell(rowIndex, i, getText(record.get(i)), false));
                    }
                    sink.next(new CsvCell(rowIndex, last, getText(record.get(last)), true));
                    rowIndex++;
                }
                sink.complete();
            } catch (Throwable err) {
                sink.error(err);
            }
        });
    }

    private String getText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        switch (first) {
            case '\uFEFF':
            case '\uFFFE':
                return text.substring(1);
        }

        return text;
    }

    @SneakyThrows
    protected Charset detectCharset(InputStream inputStream, ExcelOption... options) {
        try {
            for (ExcelOption option : options) {
                if (option.isWrapFor(CharsetOption.class)) {
                    return option.unwrap(CharsetOption.class).getCharset();
                }
            }
            CharsetDetector detector = new CharsetDetector();
            detector.setText(inputStream);

            CharsetMatch match = detector.detect();
            if (match != null) {
                Charset charset = Charset.forName(match.getName());
                //识别为了ISO_8859_1 ? 尝试转为GB18030
                if (!StandardCharsets.UTF_8.equals(charset)) {
                    return DEFAULT_GB_CHARSET;
                }
            }
        } catch (Throwable ignore) {

        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public boolean isSupportMultiSheet() {
        return false;
    }
}
