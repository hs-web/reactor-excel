# 基于Reactor的excel,csv导入导出

[![Build Status](https://travis-ci.com/hs-web/reactor-excel.svg?branch=master)](https://travis-ci.com/hs-web/reactor-excel)
[![codecov](https://codecov.io/gh/hs-web/reactor-excel/branch/master/graph/badge.svg)](https://codecov.io/gh/hs-web/reactor-excel)

```java
ReactorExcel
        .writeFor("csv")
        .justWrite()
        .sheet(spec->{
            spec.header("id","ID")
                .header("name","name")
                .rows(datas)
        })
        .write(new FileOutputStream("./target/test.csv"))
        .as(StepVerifier::create)
        .expectComplete()
        .verify();

```

```java

ReactorExcel
        .readToMap(inputStream,"csv")
        .as(StepVerifier::create)
        .subscribe(map->System.out.println(map));

```

多sheet写出

```java
 ReactorExcel
        .xlsxWriter()
        .sheet(sheet->{
             sheet.name("S1")
                  .header("id","ID")
                  .header("name","姓名")
                  .rows(dataFlux);
        })
        .sheet(sheet->{
             sheet.cell(0,0,"Name")
                  .cell(1,0,"Age")
                  .cell(0,1,"Test")
                  .cell(1,1,1)
                  .option(sheet_->{//自定义sheet操作
                     sheet_.addMergedRegion(CellRangeAddress.valueOf("A3:B3"));
                     sheet_.addMergedRegion(CellRangeAddress.valueOf("C1:C3"));
                    });
        })
        .write(new FileOutputStream("./target/test.xlsx"))
        .subscribe();
```

## 响应式导出

响应式导出由 `ExcelWriter` 统一管理字节编码和上游数据请求：

- CSV 直接把 `WritableCell` 增量编码到有界 `ByteBuf`，再按 demand 零拷贝切分为固定大小的输出块；核心路径不使用 `byte[]` 中间缓冲，也不通过竞争锁等待信号。
- XLSX/POI 保留阻塞写出特性，通过有界 OutputStream 桥接限制最终序列化的在途数据。
- 现有 `Flux<byte[]>` 和 `OutputStream` 入口保持兼容；`Flux<byte[]>` 只在末端执行复制和 `ByteBuf` 释放。

`ByteBuf` 在成功发送给下游后转移所有权；未交付的缓冲区会在取消或错误时由写出器释放。

CSV 背压以 `WritableCell` 为上游最小单元：下游没有 demand 时不会继续请求 cell，同一时刻最多编码一个 cell。为防止单个异常大 cell 绕过数据集级背压，响应式 CSV 默认限制单个 cell 的编码结果为 16 MiB，可通过 `MaxEncodedCellBytesOption.of(bytes)` 调整。每个发出的 `ByteBuf` 仍不超过调用方配置的 `bufferSize`。

CSV 响应式路径不会等待竞争锁，也不会自动切换 scheduler；cell 转换和编码在上游发送信号的线程同步执行。因此 `WritableCell.valueAsText()` 必须保持非阻塞；若自定义 cell 转换可能阻塞或需要隔离 Netty event loop，应由调用方在数据源上设置合适的 Reactor 调度边界。

### 压力测试

压力测试不进入默认 `mvn test`，需要显式运行：

```bash
mvn -Dtest=ReactiveExportStress test
```

默认场景包含 256 MiB `StreamUtils` 逻辑输出、10 万行 CSV 慢消费、上游异常、下游取消、32 MiB 异常 cell 和高频 request/cancel 竞态。测试同时断言：

- 阻塞 writer 的生产进度最多领先一个 64 KiB 分块，JVM 已用堆增长不超过 96 MiB。
- CSV 全链路每次只请求一行，在途 `ByteBuf` 不超过配置的 256 字节，并在完成、异常或取消后释放。
- 超过单 cell 编码上限时立即失败并释放内部 `ByteBuf`；并发 request/cancel 不死锁、不泄漏。
- 上游错误保持为 `onError`，下游取消会传播到数据源，不能转换为正常完成。

可通过 `reactor.excel.stress.streamMiB`、`reactor.excel.stress.csvRows`、`reactor.excel.stress.oversizedCellMiB`、`reactor.excel.stress.raceIterations`、`reactor.excel.stress.maxHeapGrowthMiB` 和 `reactor.excel.stress.timeoutSeconds` 系统属性调整数据量、内存门限和超时。内存门限用于适配不同 JVM 环境，不影响请求批次、在途字节和资源释放断言。
