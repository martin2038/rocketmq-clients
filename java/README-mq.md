# Java 客户端



AOT版本

## 概述

对netty/protoBuf进行了适配，去掉所有的shade，便于AOT
* 去掉了 `netty-shade`
* 去掉了 `grpc-shade`
* 去掉了`logback-classic`依赖
* 关闭了`opentelemetry`,去除了`opentelemetry`依赖

1. 支持java 21 / GraalVM AOT
2. 只传递`org.apache.rocketmq:rocketmq-client-apis`和`guava`依赖
3. `netty`和`grpc-protobuf` 变成 `runtime`依赖
4. `slf4j-api`,`commons-lang3`和`future-converter-java8-guava`变成`runtime`依赖



## 快速开始

https://central.sonatype.com/artifact/tech.krpc/rocketmq-client-aot-graalvm

```xml
<!-- For Apache Maven -->
<dependency>
    <groupId>tech.krpc</groupId>
    <artifactId>rocketmq-client-aot-graalvm</artifactId>
    <version>5.0.0-beta</version>
</dependency>
```

```kotlin
//  Gradle
implementation("tech.krpc:rocketmq-client-aot-graalvm:5.0.0-beta")
```

### AOT 编译

```log
========================================================================================================================
GraalVM Native Image: Generating 'xxxx-1.0.0-runner' (executable)...
========================================================================================================================
For detailed information and explanations on the build output, visit:
https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/BuildOutput.md
------------------------------------------------------------------------------------------------------------------------
[ Target_com_google_protobuf_CodedOutputStream ]
[ Target_com_google_protobuf_UnsafeUtil ]
[ Target_com_google_protobuf_Utf8 ]
[ Rocketmq Client For GraalvmBuild ] ManagedChannelProvider : io.grpc.netty.NettyChannelProvider@400d912a
[ Rocketmq Client For GraalvmBuild ] NameResolverRegistry   : io.grpc.NameResolverRegistry@781a9412
[ Rocketmq Client For GraalvmBuild ] LoadBalancerRegistry   : io.grpc.LoadBalancerRegistry@e8fadb0
[1/8] Initializing...                               
```
在`[1/8] Initializing`之前会打印上述信息。
