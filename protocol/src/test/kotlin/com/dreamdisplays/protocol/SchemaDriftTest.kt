package com.dreamdisplays.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaDriftTest {
    /** The committed .proto artifact must match the schema derived from the packet classes. */
    @Test fun committedSchemaIsUpToDate() {
        val committed = File("src/main/proto/dreamdisplays.proto")
        assertEquals(
            generateProtoSchema(),
            if (committed.exists()) committed.readText() else "",
            "dreamdisplays.proto is out of date; regenerate with ./gradlew :protocol:generateProto",
        )
    }
}
