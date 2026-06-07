/**
 * Minimal, deterministic processor for the `Stonecutter`-style version directives used in this
 * project's shared Kotlin source.
 *
 * Conditions use the Stonecutter shape already present in source:
 *
 * `//? if >=26 {`
 * `active branch`
 * `//?} else`
 * `/*fallback branch*/`
 *
 * `Stonecutter` cannot process these comments here because the plugin is only applied to the root
 * project while the code lives in subprojects; this helper performs the equivalent transform into
 * a generated source directory that the Kotlin source set points at. The checked-in branch is the
 * true branch; the else branch is stored in a block comment.
 */
fun chisel(lines: List<String>, minecraftVersion: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("//? if")) {
            out.appendLine(line)
            val keepIfBranch = evaluateCondition(line, minecraftVersion)
            i++
            val ifBranch = mutableListOf<String>()
            var nestedDepth = 0
            while (i < lines.size) {
                val branchLine = lines[i]
                val trimmedBranchLine = branchLine.trimStart()
                if (trimmedBranchLine.startsWith("//? if")) {
                    nestedDepth++
                    ifBranch.add(branchLine)
                    i++
                    continue
                }
                if (trimmedBranchLine.startsWith("//?}")) {
                    if (nestedDepth == 0) break
                    nestedDepth--
                    ifBranch.add(branchLine)
                    i++
                    continue
                }
                ifBranch.add(branchLine)
                i++
            }
            if (keepIfBranch) {
                out.append(chisel(ifBranch, minecraftVersion))
            } else {
                ifBranch.forEach { out.appendLine("//$$ $it") }
            }
            var hasElse = false
            if (i < lines.size) {
                hasElse = lines[i].trimStart().startsWith("//?} else")
                out.appendLine(lines[i])
                i++
            }
            if (hasElse && i < lines.size) {
                var j = i
                while (j < lines.size && !lines[j].contains("*/")) j++
                val block = lines.subList(i, minOf(j + 1, lines.size)).toMutableList()
                if (keepIfBranch) {
                    block.forEach { out.appendLine(it) }
                } else {
                    if (block.isNotEmpty()) {
                        val firstIdx = block[0].indexOf("/*")
                        if (firstIdx >= 0) {
                            block[0] = block[0].removeRange(firstIdx, firstIdx + 2)
                        }
                        val lastLine = block[block.size - 1]
                        val lastIdx = lastLine.lastIndexOf("*/")
                        if (lastIdx >= 0) {
                            val currentLastLine = block[block.size - 1]
                            val currentLastIdx = currentLastLine.lastIndexOf("*/")
                            block[block.size - 1] = currentLastLine.removeRange(currentLastIdx, currentLastIdx + 2)
                        }
                        out.append(chisel(block, minecraftVersion))
                    }
                }
                i = j + 1
            }
        } else {
            out.appendLine(line)
            i++
        }
    }
    return out.toString()
}

private fun evaluateCondition(marker: String, minecraftVersion: String): Boolean {
    val condition = marker.substringAfter("//? if", "").substringBefore("{").trim()
    val operator = listOf(">=", "<=", ">", "<", "==").firstOrNull { condition.startsWith(it) }
        ?: error("Unsupported chisel condition '$condition'.")
    val target = condition.removePrefix(operator).trim()
    val comparison = compareVersions(minecraftVersion, target)
    return when (operator) {
        ">=" -> comparison >= 0
        "<=" -> comparison <= 0
        ">" -> comparison > 0
        "<" -> comparison < 0
        "==" -> comparison == 0
        else -> error("Unsupported chisel operator '$operator'.")
    }
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (idx in 0 until size) {
        val l = leftParts.getOrElse(idx) { 0 }
        val r = rightParts.getOrElse(idx) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

private fun versionParts(version: String): List<Int> =
    Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
