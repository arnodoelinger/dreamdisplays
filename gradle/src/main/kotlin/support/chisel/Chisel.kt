package support.chisel

fun chiselSource(lines: List<String>, minecraftVersion: String): String =
    Chisel(lines, minecraftVersion).run()

private class Chisel(
    private val lines: List<String>,
    private val minecraftVersion: String,
) {
    private val out = StringBuilder()
    private var i = 0

    fun run(): String {
        while (i < lines.size) emitNext(stopAt = lines.size)
        return out.toString()
    }

    // Copy / transform lines until stop, resolving "//? if" along the way
    private fun emitNext(stopAt: Int) {
        val line = lines[i]
        if (isIfMarker(line)) emitIf(stopAt) else {
            out.appendLine(line)
            i++
        }
    }

    private fun emitIf(stopAt: Int) {
        val header = lines[i++]
        out.appendLine(header)
        val keepIf = evaluateCondition(header, minecraftVersion)

        val bodyStart = i
        skipIfBody(stopAt)
        val bodyEnd = i // Exclusive; [i] is the matching "//?}"

        val closer = lines.getOrNull(i)
        val hasElse = closer?.trimStart()?.startsWith("//?} else") == true
        if (closer != null) {
            out.appendLine(closer)
            i++
        }

        if (keepIf) {
            val resume = i
            i = bodyStart
            while (i < bodyEnd) emitNext(stopAt = bodyEnd)
            i = resume
        } else {
            for (k in bodyStart until bodyEnd) out.appendLine("//$$ ${lines[k]}")
        }

        if (hasElse) emitElse(keepIf)
    }

    private fun skipIfBody(stopAt: Int) {
        while (i < stopAt) {
            val trimmed = lines[i].trimStart()
            when {
                isIfMarker(lines[i]) -> skipWholeDirective(stopAt)
                trimmed.startsWith("//?}") -> return
                else -> i++
            }
        }
        error("Unclosed \"//? if\" (no matching '//?}').")
    }

    private fun skipWholeDirective(stopAt: Int) {
        i++
        skipIfBody(stopAt)
        val closer = lines.getOrNull(i) ?: return
        val hasElse = closer.trimStart().startsWith("//?} else")
        i++
        if (hasElse) skipCommentedElse()
    }

    private fun emitElse(keepIf: Boolean) {
        val block = takeCommentedElse()
        if (keepIf) {
            block.forEach { out.appendLine(it) }
            return
        }
        out.append(chiselSource(uncommentBlock(block), minecraftVersion))
    }

    private fun skipCommentedElse() {
        takeCommentedElse()
    }

    private fun takeCommentedElse(): List<String> {
        check(i < lines.size) { "Expected a /* else-branch */ after \"//?} else\"." }
        val start = i
        while (i < lines.size && !lines[i].contains("*/")) i++
        check(i < lines.size) { "Unclosed /* else-branch */ starting at line ${start + 1}." }
        val block = lines.subList(start, i + 1).toList()
        i++
        return block
    }
}

private fun isIfMarker(line: String): Boolean =
    line.trimStart().startsWith("//? if")

private fun uncommentBlock(block: List<String>): List<String> {
    if (block.isEmpty()) return block
    val copy = block.toMutableList()
    val first = copy.first().indexOf("/*")
    if (first >= 0) copy[0] = copy[0].removeRange(first, first + 2)
    val lastIdx = copy.last().lastIndexOf("*/")
    if (lastIdx >= 0) copy[copy.lastIndex] = copy.last().removeRange(lastIdx, lastIdx + 2)
    return copy
}

private val PREDICATE = Regex("""(>=|<=|==|>|<)\s*([^\s&]+)""")

private fun evaluateCondition(marker: String, minecraftVersion: String): Boolean {
    val condition = marker.substringAfter("//? if").substringBefore("{").trim()
    val predicates = PREDICATE.findAll(condition).toList()
    require(predicates.isNotEmpty()) { "Unsupported chisel condition \"$condition\"." }
    return predicates.all { match ->
        val cmp = compareVersions(minecraftVersion, match.groupValues[2])
        when (match.groupValues[1]) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            "<" -> cmp < 0
            "==" -> cmp == 0
            else -> error("unreachable")
        }
    }
}

private fun compareVersions(left: String, right: String): Int {
    val l = versionParts(left)
    val r = versionParts(right)
    val size = maxOf(l.size, r.size)
    for (idx in 0 until size) {
        val a = l.getOrElse(idx) { 0 }
        val b = r.getOrElse(idx) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun versionParts(version: String): List<Int> =
    Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
