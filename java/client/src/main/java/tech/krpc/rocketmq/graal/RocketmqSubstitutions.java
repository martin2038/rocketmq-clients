/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2024 All Rights Reserved.
 */
package tech.krpc.rocketmq.graal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;


@TargetClass(className = "com.google.protobuf.Utf8")
final class Target_com_google_protobuf_Utf8 {

    static {
        System.out.println("[ Target_com_google_protobuf_Utf8 ]");
    }

    @Substitute
    static int encode(CharSequence in, byte[] out, int offset, int length) {
        var str = in.toString();
        var buf = StandardCharsets.UTF_8.encode(str);
        var remi = buf.remaining();
        //if (length > remi) {
        //    System.out.println("Target_com_google_protobuf_Utf8 :" + in + " , length :" + length + " > remaining: " + remi);
        //}
        buf.get(out, offset, remi);
        //System.out.println(str + " -> "+ Arrays.toString(out));
        return offset + remi;
    }
    //
    //@Substitute
    //static int encodedLength(CharSequence sequence) {
    //    var str = sequence.toString();
    //    var ul =  str.getBytes(StandardCharsets.UTF_8).length;
    //    System.out.println("encodedLength : "+ ul +" -> "+ str);
    //    return ul   ;
    //}
}


@TargetClass(className = "com.google.protobuf.CodedOutputStream")
final class Target_com_google_protobuf_CodedOutputStream {

    static {
        System.out.println("[ Target_com_google_protobuf_CodedOutputStream ]");
    }

    @Alias
    @RecomputeFieldValue(kind = Kind.FromAlias)
    static boolean HAS_UNSAFE_ARRAY_OPERATIONS = false;
}

@TargetClass(className = "com.google.protobuf.UnsafeUtil")
final class Target_com_google_protobuf_UnsafeUtil {

    static {
        System.out.println("[ Target_com_google_protobuf_UnsafeUtil ]");
    }

    @Substitute
    static boolean hasUnsafeArrayOperations() {
        return false;
    }
}

/**
 * RecomputeFieldValue.FieldOffset automatic substitution failed. com.google.protobuf.UnsafeUtil
 *
 * https://github.com/quarkusio/quarkus/blob/main/extensions/netty/runtime/src/main/java/io/quarkus/netty/runtime/graal/NettySubstitutions.java
 */
class RocketmqSubstitutions {
}