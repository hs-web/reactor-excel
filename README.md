# 基于Reactor的excel,csv导入导出

[![Build Status](https://travis-ci.com/hs-web/reactor-excel.svg?branch=master)](https://travis-ci.com/hs-web/reactor-excel)
[![codecov](https://codecov.io/gh/hs-web/reactor-excel/branch/master/graph/badge.svg)](https://codecov.io/gh/hs-web/reactor-excel)


```java
ReactorExcel
        .writer("csv")
        .header("id", "ID")
        .header("name", "name")
        .write(Flux.range(0, 1000)
                .map(i -> new HashMap<String, Object>() {{
                    put("id", i);
                    put("name", "test" + i);
                }}), new FileOutputStream("./target/test.csv"))
        .as(StepVerifier::create)
        .expectComplete()
        .verify();

```

```java

 ReactorExcel
        .mapReader("csv")
        .read(inputStream)
        .as(StepVerifier::create)
        .subscribe(map->System.out.println(map));

```