package org.hswebframework.reactor.excel.spec;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hswebframework.reactor.excel.CellDataType;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CellData {
    private CellDataType type;
    private Object data;
}
