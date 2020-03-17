package org.hswebframework.reactor.excel;

import java.util.List;
import java.util.Optional;

public interface Row {

    int getRowIndex();

    Cell getCell(int column);

    List<Cell> columns();

    boolean isEnd();
}
