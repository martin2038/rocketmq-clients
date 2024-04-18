# Java 客户端

[![Codecov-java][codecov-java-image]][codecov-url] [![Maven Central][maven-image]][maven-url]

[English](README.md) | 简体中文 

AOT版本

## 概述

对netty/protoBuf进行了适配，去掉所有的shade，便于AOT
* 去掉了 `netty-shade`
* 去掉了 `grpc-shade`
* 去掉了`logback-classic`依赖
* 关闭了`opentelemetry`,去除了`opentelemetry`依赖

1. 支持java 21 / GraalVM AOT

## 快速开始

```xml
<!-- For Apache Maven -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client-java</artifactId>
    <version>${rocketmq.version}</version>
</dependency>
```

```kotlin
//  Gradle
implementation("org.apache.rocketmq:rocketmq-client-java:${rocketmq.version}")
```

