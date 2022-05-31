package org.hswebframework.reactor.excel.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.reactor.excel.ExcelOption;

import java.nio.charset.Charset;

@AllArgsConstructor
@Getter
public class CharsetOption implements ExcelOption {


    private final Charset charset;
}
