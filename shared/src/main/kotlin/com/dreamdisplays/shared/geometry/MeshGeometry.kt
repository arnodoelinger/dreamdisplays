package com.dreamdisplays.shared.geometry

import com.dreamdisplays.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val EPS = 1.0e-4
private const val EPS_F = 1.0e-4f
private const val S = 0
private const val T = 1
private const val N = 2
private const val DRAPE_GRID = 0.25
private const val DRAPE_GRID_COARSE = 0.5
private const val DRAPE_MAX_FINE_CELLS = 24_000
private const val DRAPE_ANCHOR = 1.0e-4
private const val DRAPE_MAX_ITERATIONS = 4_000
private const val DRAPE_TOLERANCE = 1.0e-10
private val TANGENTS = arrayOf(intArrayOf(T, N), intArrayOf(S, N), intArrayOf(S, T))

/**
 * One box of a block outline, in that block's local coordinates (a full cube is `0..1` on every axis).
 * Stairs and slabs are a union of these boxes.
 */
data class ShapeBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    /** Shifts this box by a block offset so it shares a coordinate space with the rest of the selection. */
    fun translated(dx: Int, dy: Int, dz: Int): ShapeBox = ShapeBox(
        minX + dx, minY + dy, minZ + dz,
        maxX + dx, maxY + dy, maxZ + dz,
    )
}

/** A block inside the display volume and the boxes that make up its outline. */
data class ShapedBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val boxes: List<ShapeBox>,
)

/**
 * One corner of a screen quad, positions relative to the display's minimum corner.
 *
 * [ox], [oy], [oz] is where the corner moves per unit of lift off the blocks. On a fold it is the sum of both
 * faces' normals, so lifted faces still meet on the edge instead of sinking into each other and eating the picture.
 */
data class ConformVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val u: Float,
    val v: Float,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val oz: Float = 0f,
)

/**
 * A screen quad hugging one face of the display surface. [nx], [ny], [nz] point outward; the vertices
 * are wound so that normal faces the viewer of that face.
 */
data class ConformQuad(
    val v0: ConformVertex,
    val v1: ConformVertex,
    val v2: ConformVertex,
    val v3: ConformVertex,
    val nx: Float,
    val ny: Float,
    val nz: Float,
) {
    /** The four corners in winding order. */
    val vertices: List<ConformVertex> get() = [v0, v1, v2, v3]

    /** Returns a copy with `u` and `v` replaced on every corner. */
    fun mapUv(transform: (u: Float, v: Float) -> Pair<Float, Float>): ConformQuad {
        fun ConformVertex.mapped(): ConformVertex {
            val (nu, nv) = transform(u, v)
            return copy(u = nu, v = nv)
        }
        return copy(v0 = v0.mapped(), v1 = v1.mapped(), v2 = v2.mapped(), v3 = v3.mapped())
    }
}

/**
 * How many blocks the volume extends on each world axis. Width runs across the screen, height runs
 * along it, and depth runs into the blocks, away from the clicked face.
 */
fun blockSpans(width: Int, height: Int, depth: Int, facing: DisplayFacing): Triple<Int, Int, Int> = when (facing) {
    DisplayFacing.NORTH, DisplayFacing.SOUTH -> Triple(width, height, depth)
    DisplayFacing.EAST, DisplayFacing.WEST -> Triple(depth, height, width)
    DisplayFacing.UP, DisplayFacing.DOWN -> Triple(width, depth, height)
}

/**
 * Builds the quads of a screen wrapped onto [blocks]. Coordinates are relative to the minimum corner of the display.
 *
 * Every face the viewer can see from the front gets picture: fronts, risers, treads, side walls of a step,
 * the ceiling of a pocket. Faces on the rim of the volume are the screen's edge and stay bare, same as a flat screen.
 */
fun buildConformingMesh(
    blocks: List<ShapedBlock>,
    width: Int,
    height: Int,
    depth: Int,
    facing: DisplayFacing,
    quarterTurns: Int = 0,
): List<ConformQuad> {
    if (width <= 0 || height <= 0 || depth <= 0 || blocks.isEmpty()) return []
    val frame = ScreenFrame(facing, width, height, depth)
    val boxes = blocks.flatMap { block -> block.boxes.map { frame.box(it.translated(block.x, block.y, block.z)) } }

    val faces = boxes
        .flatMap(::facesOf)
        .filter { !frame.isRimOrBack(it) }
        .flatMap { visiblePart(it, boxes) }
    if (faces.isEmpty()) return []

    val turns = Math.floorMod(quarterTurns, 4)
    // Nothing but surfaces facing the viewer (a flat wall, a lone slab): the plain projection is exact
    if (faces.all { it.axis == N }) return projected(faces, frame, turns)
    return drape(faces, frame, turns)
}

/**
 * Cuts [quad] to the video-UV rectangle and returns the pieces. UVs stay in video space; callers
 * that sample a different texture remap them. Pieces with more than four corners are fanned.
 */
fun clipToUvRect(quad: ConformQuad, minU: Float, minV: Float, maxU: Float, maxV: Float): List<ConformQuad> {
    if (maxU - minU <= EPS_F || maxV - minV <= EPS_F) return []
    var poly = quad.vertices
    poly = clipHalf(poly, { it.u >= minU }) { a, b -> lerp(a, b, (minU - a.u) / (b.u - a.u)) }
    poly = clipHalf(poly, { it.u <= maxU }) { a, b -> lerp(a, b, (maxU - a.u) / (b.u - a.u)) }
    poly = clipHalf(poly, { it.v >= minV }) { a, b -> lerp(a, b, (minV - a.v) / (b.v - a.v)) }
    poly = clipHalf(poly, { it.v <= maxV }) { a, b -> lerp(a, b, (maxV - a.v) / (b.v - a.v)) }
    if (poly.size < 3) return []
    if (poly.size == 4) return [quad.copy(v0 = poly[0], v1 = poly[1], v2 = poly[2], v3 = poly[3])]
    return (1 until poly.size - 1).map { i ->
        quad.copy(v0 = poly[0], v1 = poly[i], v2 = poly[i + 1], v3 = poly[i + 1])
    }
}

private class Span(val lo: DoubleArray, val hi: DoubleArray) {
    fun with(axis: Int, from: Double, to: Double): Span {
        val nextLo = lo.copyOf()
        val nextHi = hi.copyOf()
        nextLo[axis] = from
        nextHi[axis] = to
        return Span(nextLo, nextHi)
    }
}

private class Face(val axis: Int, val sign: Int, val span: Span) {
    val plane: Double get() = span.lo[axis]
    val tangents: IntArray get() = TANGENTS[axis]

    fun with(tangent: Int, from: Double, to: Double): Face = Face(axis, sign, span.with(tangent, from, to))
}

private class ScreenFrame(val facing: DisplayFacing, val width: Int, val height: Int, val depth: Int) {
    fun toScreen(x: Double, y: Double, z: Double): DoubleArray = when (facing) {
        DisplayFacing.NORTH -> doubleArrayOf(width - x, y, z)
        DisplayFacing.SOUTH -> doubleArrayOf(x, y, depth - z)
        DisplayFacing.EAST -> doubleArrayOf(width - z, y, depth - x)
        DisplayFacing.WEST -> doubleArrayOf(z, y, x)
        DisplayFacing.UP -> doubleArrayOf(x, height - z, depth - y)
        DisplayFacing.DOWN -> doubleArrayOf(x, z, y)
    }

    fun toWorld(p: DoubleArray): DoubleArray {
        val s = p[S]
        val t = p[T]
        val n = p[N]
        return when (facing) {
            DisplayFacing.NORTH -> doubleArrayOf(width - s, t, n)
            DisplayFacing.SOUTH -> doubleArrayOf(s, t, depth - n)
            DisplayFacing.EAST -> doubleArrayOf(depth - n, t, width - s)
            DisplayFacing.WEST -> doubleArrayOf(n, t, s)
            DisplayFacing.UP -> doubleArrayOf(s, depth - n, height - t)
            DisplayFacing.DOWN -> doubleArrayOf(s, n, t)
        }
    }

    fun normal(axis: Int, sign: Int): DoubleArray {
        val tip = DoubleArray(3).also { it[axis] = sign.toDouble() }
        val origin = toWorld(DoubleArray(3))
        val end = toWorld(tip)
        return DoubleArray(3) { end[it] - origin[it] }
    }

    fun box(world: ShapeBox): Span {
        val a = toScreen(world.minX, world.minY, world.minZ)
        val b = toScreen(world.maxX, world.maxY, world.maxZ)
        return Span(DoubleArray(3) { min(a[it], b[it]) }, DoubleArray(3) { max(a[it], b[it]) })
    }

    fun isRimOrBack(face: Face): Boolean = when (face.axis) {
        N -> face.sign > 0
        S -> face.plane <= EPS || face.plane >= width - EPS
        else -> face.plane <= EPS || face.plane >= height - EPS
    }
}

private fun facesOf(box: Span): List<Face> {
    val out = ArrayList<Face>(6)
    for (axis in 0..2) {
        val (a, b) = TANGENTS[axis].let { it[0] to it[1] }
        if (box.hi[a] - box.lo[a] <= EPS || box.hi[b] - box.lo[b] <= EPS) continue
        out += Face(axis, -1, box.with(axis, box.lo[axis], box.lo[axis]))
        out += Face(axis, +1, box.with(axis, box.hi[axis], box.hi[axis]))
    }
    return out
}

private fun visiblePart(face: Face, boxes: List<Span>): List<Face> {
    var parts = [face]
    for (box in boxes) {
        val cut = shadowOf(box, face) ?: continue
        parts = parts.flatMap { subtract(it, cut) }
        if (parts.isEmpty()) break
    }
    return parts
}

private fun shadowOf(box: Span, face: Face): Span? {
    val plane = face.plane
    val inTheWay = if (face.axis == N) {
        box.lo[N] < plane - EPS
    } else if (face.sign > 0) {
        box.lo[face.axis] <= plane + EPS && box.hi[face.axis] > plane + EPS
    } else {
        box.hi[face.axis] >= plane - EPS && box.lo[face.axis] < plane - EPS
    }
    if (!inTheWay) return null

    // Along the depth, the box hides everything behind its front
    return if (face.axis == N) box else box.with(N, box.lo[N], Double.POSITIVE_INFINITY)
}

private fun subtract(face: Face, cut: Span): List<Face> {
    val (a, b) = face.tangents.let { it[0] to it[1] }
    val span = face.span
    val a0 = max(span.lo[a], cut.lo[a])
    val a1 = min(span.hi[a], cut.hi[a])
    val b0 = max(span.lo[b], cut.lo[b])
    val b1 = min(span.hi[b], cut.hi[b])
    if (a1 - a0 <= EPS || b1 - b0 <= EPS) return [face]
    val out = ArrayList<Face>(4)
    fun piece(fromA: Double, toA: Double, fromB: Double, toB: Double) {
        if (toA - fromA > EPS && toB - fromB > EPS) out += face.with(a, fromA, toA).with(b, fromB, toB)
    }
    piece(span.lo[a], span.hi[a], span.lo[b], b0)
    piece(span.lo[a], span.hi[a], b1, span.hi[b])
    piece(span.lo[a], a0, b0, b1)
    piece(a1, span.hi[a], b0, b1)
    return out
}

private fun projected(faces: List<Face>, frame: ScreenFrame, quarterTurns: Int): List<ConformQuad> = faces.map { face ->
    val (a, b) = face.tangents.let { it[0] to it[1] }
    val normal = frame.normal(face.axis, face.sign)
    val vertices = cornersOf(face, a, b).map { p ->
        val world = frame.toWorld(p)
        val (u, v) = textureUv(p[S] / frame.width, p[T] / frame.height, quarterTurns)
        ConformVertex(
            world[0].toFloat(), world[1].toFloat(), world[2].toFloat(), u, v,
            normal[0].toFloat(), normal[1].toFloat(), normal[2].toFloat(),
        )
    }
    wound(vertices, normal)
}

private fun cornersOf(face: Face, a: Int, b: Int): List<DoubleArray> = [
    face.span.lo[a] to face.span.lo[b],
    face.span.hi[a] to face.span.lo[b],
    face.span.hi[a] to face.span.hi[b],
    face.span.lo[a] to face.span.hi[b],
].map { (pa, pb) -> DoubleArray(3).also { it[face.axis] = face.plane; it[a] = pa; it[b] = pb } }

private fun wound(vertices: List<ConformVertex>, normal: DoubleArray): ConformQuad {
    val w = if (facesAlong(vertices, normal)) vertices else vertices.asReversed()
    return ConformQuad(w[0], w[1], w[2], w[3], normal[0].toFloat(), normal[1].toFloat(), normal[2].toFloat())
}

private fun drape(faces: List<Face>, frame: ScreenFrame, quarterTurns: Int): List<ConformQuad> {
    val area = faces.sumOf { f -> f.tangents.let { (f.span.hi[it[0]] - f.span.lo[it[0]]) * (f.span.hi[it[1]] - f.span.lo[it[1]]) } }
    val grid = if (area / (DRAPE_GRID * DRAPE_GRID) > DRAPE_MAX_FINE_CELLS) DRAPE_GRID_COARSE else DRAPE_GRID

    val positions = ArrayList<DoubleArray>()
    val index = HashMap<Long, Int>()
    fun vertex(p: DoubleArray): Int {
        val key = (Math.round(p[S] * 64) shl 42) or (Math.round(p[T] * 64) shl 21) or Math.round(p[N] * 64)
        return index.getOrPut(key) { positions.add(p.copyOf()); positions.size - 1 }
    }

    class Cell(val face: Face, val corners: IntArray)
    val cells = ArrayList<Cell>()
    val masks = ArrayList<Int>()
    for (face in faces) {
        val (a, b) = face.tangents.let { it[0] to it[1] }
        val ta = ticks(face.span.lo[a], face.span.hi[a], grid)
        val tb = ticks(face.span.lo[b], face.span.hi[b], grid)
        for (i in 0 until ta.size - 1) for (j in 0 until tb.size - 1) {
            val corners = intArrayOf(
                vertex(point(face, a, ta[i], b, tb[j])),
                vertex(point(face, a, ta[i + 1], b, tb[j])),
                vertex(point(face, a, ta[i + 1], b, tb[j + 1])),
                vertex(point(face, a, ta[i], b, tb[j + 1])),
            )
            cells += Cell(face, corners)
        }
    }
    val bit = { face: Face -> 1 shl (face.axis * 2 + (face.sign + 1) / 2) }
    repeat(positions.size) { masks += 0 }
    for (cell in cells) for (c in cell.corners) masks[c] = masks[c] or bit(cell.face)

    val edges = HashMap<Long, DoubleArray>()
    for (cell in cells) for (k in 0..3) {
        val from = cell.corners[k]
        val to = cell.corners[(k + 1) % 4]
        val key = if (from < to) (from.toLong() shl 32) or to.toLong() else (to.toLong() shl 32) or from.toLong()
        val (lo, hi) = if (from < to) from to to else to to from
        val pa = positions[lo]
        val pb = positions[hi]
        val e = edges.getOrPut(key) { doubleArrayOf(lo.toDouble(), hi.toDouble(), 0.0, 0.0, 0.0) }
        e[2] += want(cell.face, S, pa, pb)
        e[3] += want(cell.face, T, pa, pb)
        e[4] += 1.0
    }
    for (e in edges.values) {
        e[2] /= e[4]
        e[3] /= e[4]
    }
    val back = frame.depth.toDouble()
    val border = BooleanArray(positions.size)
    for (e in edges.values) if (e[4] < 1.5) {
        for (end in 0..1) {
            val i = e[end].toInt()
            if (abs(positions[i][N] - back) <= EPS) border[i] = true
        }
    }

    val u = solve(positions, edges.values, S, frame.width.toDouble(), border)
    val v = solve(positions, edges.values, T, frame.height.toDouble(), border)

    return cells.map { cell ->
        val face = cell.face
        val normal = frame.normal(face.axis, face.sign)
        val vertices = cell.corners.map { c ->
            val world = frame.toWorld(positions[c])
            val lift = normal.copyOf()
            for (axis in 0..2) for (sign in intArrayOf(-1, 1)) {
                if (axis == face.axis || masks[c] and (1 shl (axis * 2 + (sign + 1) / 2)) == 0) continue
                val n = frame.normal(axis, sign)
                for (i in 0..2) lift[i] += n[i]
            }
            val (tu, tv) = textureUv(
                (u[c] / frame.width).coerceIn(0.0, 1.0), (v[c] / frame.height).coerceIn(0.0, 1.0), quarterTurns,
            )
            ConformVertex(
                world[0].toFloat(), world[1].toFloat(), world[2].toFloat(), tu, tv,
                lift[0].toFloat(), lift[1].toFloat(), lift[2].toFloat(),
            )
        }
        wound(vertices, normal)
    }
}

private fun ticks(from: Double, to: Double, grid: Double): DoubleArray {
    val out = ArrayList<Double>()
    out += from
    var line = (floor(from / grid + EPS) + 1) * grid
    while (line < to - EPS) {
        out += line
        line += grid
    }
    out += to
    return out.toDoubleArray()
}

private fun point(face: Face, a: Int, pa: Double, b: Int, pb: Double): DoubleArray =
    DoubleArray(3).also { it[face.axis] = face.plane; it[a] = pa; it[b] = pb }

private fun want(face: Face, axis: Int, pa: DoubleArray, pb: DoubleArray): Double =
    if (face.axis == axis) face.sign * (pb[N] - pa[N]) else pb[axis] - pa[axis]

private fun solve(
    positions: List<DoubleArray>,
    edges: Collection<DoubleArray>,
    axis: Int,
    extent: Double,
    border: BooleanArray,
): DoubleArray {
    val n = positions.size
    val fixed = BooleanArray(n)
    val pinned = DoubleArray(n)
    for (i in 0 until n) {
        val p = positions[i][axis]
        if (abs(p) <= EPS) { fixed[i] = true; pinned[i] = 0.0 }
        else if (abs(p - extent) <= EPS) { fixed[i] = true; pinned[i] = extent }
    }
    var x = relax(positions, edges, axis, fixed, pinned)
    repeat(3) {
        var overran = false
        for (i in 0 until n) {
            if (fixed[i] || !border[i]) continue
            if (x[i] > extent + EPS) { fixed[i] = true; pinned[i] = extent; overran = true }
            else if (x[i] < -EPS) { fixed[i] = true; pinned[i] = 0.0; overran = true }
        }
        if (!overran) return x
        x = relax(positions, edges, axis, fixed, pinned)
    }
    return x
}

private fun relax(
    positions: List<DoubleArray>,
    edges: Collection<DoubleArray>,
    axis: Int,
    fixed: BooleanArray,
    pinned: DoubleArray,
): DoubleArray {
    val n = positions.size
    val want = if (axis == S) 2 else 3
    val x = DoubleArray(n) { if (fixed[it]) pinned[it] else positions[it][axis] }

    val neighbors = Array(n) { ArrayList<Int>(4) }
    val rhs = DoubleArray(n) { DRAPE_ANCHOR * positions[it][axis] }
    for (e in edges) {
        val a = e[0].toInt()
        val b = e[1].toInt()
        neighbors[a] += b
        neighbors[b] += a
        rhs[a] -= e[want]
        rhs[b] += e[want]
    }
    val diag = DoubleArray(n) { neighbors[it].size + DRAPE_ANCHOR }
    for (i in 0 until n) {
        if (fixed[i]) continue
        for (j in neighbors[i]) if (fixed[j]) rhs[i] += x[j]
    }

    fun apply(p: DoubleArray, out: DoubleArray) {
        for (i in 0 until n) {
            if (fixed[i]) { out[i] = 0.0; continue }
            var acc = diag[i] * p[i]
            for (j in neighbors[i]) if (!fixed[j]) acc -= p[j]
            out[i] = acc
        }
    }

    val r = DoubleArray(n)
    val ax = DoubleArray(n)
    apply(x, ax)
    for (i in 0 until n) r[i] = if (fixed[i]) 0.0 else rhs[i] - ax[i]
    val z = DoubleArray(n) { r[it] / diag[it] }
    val p = z.copyOf()
    val ap = DoubleArray(n)
    var rz = (0 until n).sumOf { r[it] * z[it] }
    val stop = DRAPE_TOLERANCE * (0 until n).sumOf { if (fixed[it]) 0.0 else rhs[it] * rhs[it] }.coerceAtLeast(1.0)
    for (iteration in 0 until DRAPE_MAX_ITERATIONS) {
        if ((0 until n).sumOf { r[it] * r[it] } <= stop) break
        apply(p, ap)
        val pap = (0 until n).sumOf { p[it] * ap[it] }
        if (pap <= 0.0) break
        val alpha = rz / pap
        for (i in 0 until n) {
            x[i] += alpha * p[i]
            r[i] -= alpha * ap[i]
            z[i] = if (fixed[i]) 0.0 else r[i] / diag[i]
        }
        val next = (0 until n).sumOf { r[it] * z[it] }
        val beta = next / rz
        rz = next
        for (i in 0 until n) p[i] = z[i] + beta * p[i]
    }
    return x
}

private fun facesAlong(v: List<ConformVertex>, normal: DoubleArray): Boolean {
    val e1x = v[1].x - v[0].x
    val e1y = v[1].y - v[0].y
    val e1z = v[1].z - v[0].z
    val e2x = v[2].x - v[0].x
    val e2y = v[2].y - v[0].y
    val e2z = v[2].z - v[0].z
    val cx = e1y * e2z - e1z * e2y
    val cy = e1z * e2x - e1x * e2z
    val cz = e1x * e2y - e1y * e2x
    return cx * normal[0] + cy * normal[1] + cz * normal[2] > 0.0
}

private fun textureUv(s: Double, t: Double, quarterTurns: Int): Pair<Float, Float> {
    val (u, v) = when (quarterTurns) {
        1 -> 1.0 - t to 1.0 - s
        2 -> 1.0 - s to t
        3 -> t to s
        else -> s to 1.0 - t
    }
    return u.toFloat() to v.toFloat()
}

private fun clipHalf(
    poly: List<ConformVertex>,
    inside: (ConformVertex) -> Boolean,
    cross: (ConformVertex, ConformVertex) -> ConformVertex?,
): List<ConformVertex> {
    if (poly.isEmpty()) return []
    val out = ArrayList<ConformVertex>(poly.size + 1)
    var prev = poly.last()
    var prevIn = inside(prev)
    for (cur in poly) {
        val curIn = inside(cur)
        if (curIn != prevIn) cross(prev, cur)?.let(out::add)
        if (curIn) out += cur
        prev = cur
        prevIn = curIn
    }
    return out
}

private fun lerp(a: ConformVertex, b: ConformVertex, t: Float): ConformVertex? {
    if (t.isNaN() || t.isInfinite()) return null
    val k = t.coerceIn(0f, 1f)
    return ConformVertex(
        a.x + (b.x - a.x) * k,
        a.y + (b.y - a.y) * k,
        a.z + (b.z - a.z) * k,
        a.u + (b.u - a.u) * k,
        a.v + (b.v - a.v) * k,
        a.ox + (b.ox - a.ox) * k,
        a.oy + (b.oy - a.oy) * k,
        a.oz + (b.oz - a.oz) * k,
    )
}
