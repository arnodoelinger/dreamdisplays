@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import java.io.File

/** Generates the .proto schema text for all registered packets plus the envelope. */
fun generateProtoSchema(): String = ProtoBufSchemaGenerator.generateSchemaText(
    listOf(Envelope.serializer().descriptor) + PacketRegistry.schemaDescriptors,
    packageName = "dreamdisplays.v2",
)

/** Entry point for the `:protocol:generateProto` task: writes the schema to args[0]. */
fun main(args: Array<String>) {
    val target = File(args.single())
    target.parentFile.mkdirs()
    target.writeText(generateProtoSchema())
    println("Wrote ${target.absolutePath}.")
}
