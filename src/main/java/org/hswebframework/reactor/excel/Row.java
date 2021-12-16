package org.hswebframework.reactor.excel;

import java.util.List;

public interface Row {

    int getRowIndex();

    Cell getCell(int column);

    List<Cell> columns();

    boolean isEnd();
}
