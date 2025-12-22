package org.hswebframework.reactor.excel.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.csv.CSVFormat;
import org.hswebframework.reactor.excel.ExcelOption;

@Getter
@AllArgsConstructor(staticName = "of")
public class FormatOption implements ExcelOption {

    private final CSVFormat format;
}
