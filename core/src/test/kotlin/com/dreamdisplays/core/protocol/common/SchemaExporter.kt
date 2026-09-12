@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import java.io.File

fun generateProtoSchema(): String = ProtoBufSchemaGenerator.generateSchemaText(
    listOf(Envelope.serializer().descriptor) + PacketRegistry.schemaDescriptors,
    packageName = "dreamdisplays.v2",
)

fun normalizeProtoSchema(text: String): String = text.lineSequence()
    .map { it.substringBefore("//").trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

fun main(args: Array<String>) {
    val target = File(args.single())
    target.parentFile.mkdirs()
    target.writeText(generateProtoSchema())
    println("Wrote ${target.absolutePath}.")
}
