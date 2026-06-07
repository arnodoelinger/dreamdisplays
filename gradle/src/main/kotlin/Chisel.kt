/**
 * Minimal, deterministic processor for the `Stonecutter`-style version directives used in this
 * project's shared Kotlin source.
 *
 * For 26.x targets the checked-in source is already valid Kotlin, so no transform is needed.
 * For legacy (1.21.x) targets we comment out the if-branch and uncomment the else-branch.
 *
 * `Stonecutter` cannot process these comments here because the plugin is only applied to the root
 * project while the code lives in subprojects; this helper performs the equivalent transform into
 * a generated source directory that the Kotlin source set points at.
 */
fun chiselToLegacy(lines: List<String>): String {
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("//? if")) {
            // Keep the marker line (it is a harmless // comment).
            out.appendLine(line)
            i++
            // Collect the if-branch up to the "//?} else" marker and comment it out.
            val ifBranch = mutableListOf<String>()
            while (i < lines.size && !lines[i].trimStart().startsWith("//?}")) {
                ifBranch.add(lines[i])
                i++
            }
            out.appendLine("/*")
            ifBranch.forEach { out.appendLine(it) }
            out.appendLine("*/")
            // Keep the "//?} else" marker line.
            if (i < lines.size) {
                out.appendLine(lines[i])
                i++
            }
            // The else-branch is a single /* ... */ block; uncomment it.
            if (i < lines.size) {
                var j = i
                while (j < lines.size && !lines[j].contains("*/")) j++
                val block = lines.subList(i, minOf(j + 1, lines.size)).toMutableList()
                if (block.isNotEmpty()) {
                    val firstIdx = block[0].indexOf("/*")
                    if (firstIdx >= 0) {
                        block[0] = block[0].removeRange(firstIdx, firstIdx + 2)
                    }
                    val lastLine = block[block.size - 1]
                    val lastIdx = lastLine.lastIndexOf("*/")
                    if (lastIdx >= 0) {
                        block[block.size - 1] = lastLine.removeRange(lastIdx, lastIdx + 2)
                    }
                    block.forEach { out.appendLine(it) }
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
