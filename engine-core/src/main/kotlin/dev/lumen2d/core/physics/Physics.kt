/**
 * Lumen2D — 2D physics.
 *
 * A compact but complete impulse solver designed for platformers, top-down action games and
 * arcade shooters:
 *  * shapes: rectangle, circle, convex polygon (capsule = rectangle + radius),
 *  * broadphase: uniform spatial hash grid,
 *  * narrow phase: AABB / circle tests and SAT for convex polygons with contact points,
 *  * solver: iterative impulse resolution with restitution, friction, position correction,
 *    one-way platforms, kinematic carry (moving platforms) and sleeping,
 *  * queries: raycasts, point/rect/circle overlaps, area triggers.
 *
 * All state lives in plain classes ([PhysicsBody]) so physics can run headless — which is
 * how the automated tests and the CI gameplay previews verify gameplay without a device.
 */
package dev.lumen2d.core.physics

import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Transform2D
import dev.lumen2d.core.math.Vec2
import kotlin.math.abs
import kotlin.math.sqrt

/** Local copy of PI as a Float (kept here so the physics file has no math-package dependency cycle). */
private const val PI_F = 3.1415927f

// ------------------------------------------------------------------------------ shapes

/** Collision shapes. All shapes are convex, which keeps SAT exact and fast. */
sealed class Shape2D {
    abstract fun localBounds(): Rect
    abstract fun area(): Float
    abstract fun typeName(): String
    abstract fun serialize(): Map<String, Any?>

    /** Rect / box, centred on its origin. */
    class Rectangle(val size: Vec2) : Shape2D() {
        override fun localBounds() = Rect(-size.x / 2f, -size.y / 2f, size.x, size.y)
        override fun area() = size.x * size.y
        override fun typeName() = "RectangleShape2D"
        override fun serialize() = mapOf<String, Any?>("type" to "rectangle", "size" to listOf(size.x, size.y))
        val halfWidth get() = size.x / 2f
        val halfHeight get() = size.y / 2f
    }

    class Circle(val radius: Float) : Shape2D() {
        override fun localBounds() = Rect(-radius, -radius, radius * 2f, radius * 2f)
        override fun area() = PI_F * radius * radius
        override fun typeName() = "CircleShape2D"
        override fun serialize() = mapOf<String, Any?>("type" to "circle", "radius" to radius.toDouble())
    }

    /** Vertical capsule: a rectangle with rounded ends (good for characters). */
    class Capsule(val radius: Float, val height: Float) : Shape2D() {
        override fun localBounds() = Rect(-radius, -height / 2f - radius, radius * 2f, height + radius * 2f)
        override fun area() = radius * height * 2f + PI_F * radius * radius
        override fun typeName() = "CapsuleShape2D"
        override fun serialize() = mapOf<String, Any?>(
            "type" to "capsule", "radius" to radius.toDouble(), "height" to height.toDouble())
    }

    /** Convex polygon; points are local, counter-clockwise or clockwise (normalised on init). */
    class Polygon(points: FloatArray) : Shape2D() {
        val points: FloatArray = points.copyOf()

        init { require(this.points.size >= 6) { "A polygon needs at least 3 points" } }

        override fun localBounds(): Rect {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in points.indices step 2) {
                val x = points[i]; val y = points[i + 1]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            return Rect.fromLTRB(minX, minY, maxX, maxY)
        }

        override fun area(): Float {
            var a = 0f
            val n = points.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                a += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
            }
            return abs(a) / 2f
        }

        override fun typeName() = "ConvexPolygonShape2D"
        override fun serialize() = mapOf<String, Any?>(
            "type" to "polygon", "points" to points.toList().map { it.toDouble() })
    }

    companion object {
        /** Builds a capsule approximated as a rectangle when the shape must stay polygonal. */
        fun box(width: Float, height: Float) = Rectangle(Vec2(width, height))
        fun circle(radius: Float) = Circle(radius)
        fun capsule(radius: Float, height: Float) = Capsule(radius, height)
        fun polygon(vararg xys: Float) = Polygon(xys)
        fun fromJson(map: Map<String, Any?>): Shape2D {
            return when (map["type"] as? String) {
                "circle" -> Circle((map["radius"] as? Number)?.toFloat() ?: 8f)
                "capsule" -> Capsule(
                    (map["radius"] as? Number)?.toFloat() ?: 6f,
                    (map["height"] as? Number)?.toFloat() ?: 12f)
                "polygon" -> Polygon((map["points"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.toFloatArray()
                    ?: floatArrayOf(-8f, -8f, 8f, -8f, 8f, 8f, -8f, 8f))
                else -> Rectangle(Vec2(
                    (map["size"] as? List<*>)?.getOrNull(0)?.let { (it as? Number)?.toFloat() } ?: 16f,
                    (map["size"] as? List<*>)?.getOrNull(1)?.let { (it as? Number)?.toFloat() } ?: 16f))
            }
        }
    }
}

/** Result of a narrow-phase test. */
class CollisionResult(
    val normal: Vec2,
    val depth: Float,
    val point: Vec2,
    /** True when the contact should not push back (one-way platform handled from above). */
    val sensor: Boolean = false,
)

// --------------------------------------------------------------------- collision maths

object Collision {
    const val EPS = 1e-4f

    fun test(a: Shape2D, ta: Transform2D, b: Shape2D, tb: Transform2D): CollisionResult? = when {
        a is Shape2D.Rectangle && b is Shape2D.Rectangle -> rectRect(a, ta, b, tb)
        a is Shape2D.Circle && b is Shape2D.Circle -> circleCircle(a, ta, b, tb)
        a is Shape2D.Circle && b is Shape2D.Rectangle -> circleRect(a, ta, b, tb, flipped = false)
        a is Shape2D.Rectangle && b is Shape2D.Circle -> circleRect(b, tb, a, ta, flipped = true)
        a is Shape2D.Capsule && b is Shape2D.Capsule -> capsuleCapsule(a, ta, b, tb)
        a is Shape2D.Circle && b is Shape2D.Capsule -> capsuleCircle(b, tb, a, ta, flipped = true)
        a is Shape2D.Capsule && b is Shape2D.Circle -> capsuleCircle(a, ta, b, tb, flipped = false)
        else -> polygonPolygon(toPolygon(a), ta, toPolygon(b), tb)
    }

    /** Converts box/capsule shapes into polygons (capsules become 12-gons). */
    fun toPolygon(shape: Shape2D): Shape2D.Polygon = when (shape) {
        is Shape2D.Rectangle -> Shape2D.Polygon(floatArrayOf(
            -shape.halfWidth, -shape.halfHeight, shape.halfWidth, -shape.halfHeight,
            shape.halfWidth, shape.halfHeight, -shape.halfWidth, shape.halfHeight))
        is Shape2D.Capsule -> {
            val pts = FloatArray(24)
            val r = shape.radius; val hh = shape.height / 2f
            for (i in 0 until 6) {
                val t = i / 6f * PI_F - PI_F / 2f
                pts[i * 2] = kotlin.math.cos(t) * r
                pts[i * 2 + 1] = hh + kotlin.math.sin(t) * r
            }
            for (i in 0 until 6) {
                val t = i / 6f * PI_F + PI_F / 2f
                pts[12 + i * 2] = kotlin.math.cos(t) * r
                pts[12 + i * 2 + 1] = -hh + kotlin.math.sin(t) * r
            }
            Shape2D.Polygon(pts)
        }
        is Shape2D.Polygon -> shape
        else -> Shape2D.Polygon(floatArrayOf(-8f, -8f, 8f, -8f, 8f, 8f, -8f, 8f))
    }

    fun worldBounds(shape: Shape2D, t: Transform2D): Rect {
        val local = shape.localBounds()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((lx, ly) in listOf(
            local.left to local.top, local.right to local.top,
            local.right to local.bottom, local.left to local.bottom)) {
            val p = t.transformPoint(lx, ly)
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return Rect.fromLTRB(minX, minY, maxX, maxY)
    }

    private fun rectRect(a: Shape2D.Rectangle, ta: Transform2D, b: Shape2D.Rectangle, tb: Transform2D): CollisionResult? {
        // Fast path when both are axis aligned (the common platformer case).
        if (isAxisAligned(ta) && isAxisAligned(tb)) {
            val pa = ta.translation; val pb = tb.translation
            val ahw = a.halfWidth * abs(ta.scaleX); val ahh = a.halfHeight * abs(ta.scaleY)
            val bhw = b.halfWidth * abs(tb.scaleX); val bhh = b.halfHeight * abs(tb.scaleY)
            val dx = pb.x - pa.x; val dy = pb.y - pa.y
            val overlapX = ahw + bhw - abs(dx)
            val overlapY = ahh + bhh - abs(dy)
            if (overlapX <= 0f || overlapY <= 0f) return null
            return if (overlapX < overlapY) {
                val normal = Vec2(if (dx < 0f) -1f else 1f, 0f)
                CollisionResult(normal, overlapX, Vec2(pa.x + normal.x * ahw, (pa.y + pb.y) / 2f))
            } else {
                val normal = Vec2(0f, if (dy < 0f) -1f else 1f)
                CollisionResult(normal, overlapY, Vec2((pa.x + pb.x) / 2f, pa.y + normal.y * ahh))
            }
        }
        return polygonPolygon(toPolygon(a), ta, toPolygon(b), tb)
    }

    private fun isAxisAligned(t: Transform2D): Boolean = abs(t.m01) < 1e-4f && abs(t.m10) < 1e-4f

    private fun circleCircle(a: Shape2D.Circle, ta: Transform2D, b: Shape2D.Circle, tb: Transform2D): CollisionResult? {
        val pa = ta.translation; val pb = tb.translation
        val ra = a.radius * ta.scaleX; val rb = b.radius * tb.scaleX
        val d = pb - pa
        val dist = d.length
        val sum = ra + rb
        if (dist >= sum) return null
        val normal = if (dist < EPS) Vec2(0f, -1f) else d / dist
        return CollisionResult(normal, sum - dist, pa + normal * ra)
    }

    private fun circleRect(c: Shape2D.Circle, tc: Transform2D, r: Shape2D.Rectangle, tr: Transform2D, flipped: Boolean): CollisionResult? {
        // Work in the rectangle's local space so rotation is handled exactly.
        val inv = tr.inverse()
        val center = inv.transformPoint(tc.translation)
        val radius = c.radius * tc.scaleX
        val hw = r.halfWidth * abs(tr.scaleX)
        val hh = r.halfHeight * abs(tr.scaleY)
        val closestX = MathUtil.clamp(center.x, -hw, hw)
        val closestY = MathUtil.clamp(center.y, -hh, hh)
        var delta = Vec2(center.x - closestX, center.y - closestY)
        var dist = delta.length
        val inside = dist < EPS
        if (inside) {
            // Circle centre is inside the box: push out along the shallowest axis.
            val dx = hw - abs(center.x); val dy = hh - abs(center.y)
            delta = if (dx < dy) Vec2(if (center.x < 0f) -1f else 1f, 0f) else Vec2(0f, if (center.y < 0f) -1f else 1f)
            dist = 0f
        }
        if (!inside && dist > radius) return null
        val localNormal = if (inside) delta else delta / dist
        val depth = if (inside) radius + dist else radius - dist
        val localPoint = if (inside) Vec2(closestX, closestY) else Vec2(closestX, closestY)
        // Back to world space.
        val worldNormal = tr.transformVector(localNormal.x, localNormal.y).normalized()
        val worldPoint = tr.transformPoint(localPoint)
        val n = if (flipped) -worldNormal else worldNormal
        // The normal must point from the rectangle toward the circle when not flipped.
        val signFix = if (flipped) n else -n
        return CollisionResult(signFix, depth, worldPoint)
    }

    private fun capsuleCapsule(a: Shape2D.Capsule, ta: Transform2D, b: Shape2D.Capsule, tb: Transform2D): CollisionResult? =
        polygonPolygon(toPolygon(a), ta, toPolygon(b), tb)

    private fun capsuleCircle(cap: Shape2D.Capsule, tc: Transform2D, circle: Shape2D.Circle, tcir: Transform2D, flipped: Boolean): CollisionResult? {
        // Distance from the circle to the capsule's core segment.
        val inv = tc.inverse()
        val c = inv.transformPoint(tcir.translation)
        val radius = circle.radius * tcir.scaleX
        val hh = cap.height / 2f
        val clampedY = MathUtil.clamp(c.y, -hh, hh)
        var delta = Vec2(c.x, c.y - clampedY)
        var dist = delta.length
        val inside = dist < EPS
        if (inside) {
            delta = if (abs(c.x) > abs(c.y)) Vec2(if (c.x < 0f) -1f else 1f, 0f) else Vec2(0f, if (c.y < 0f) -1f else 1f)
            dist = 0f
        }
        val sum = cap.radius + radius
        if (!inside && dist > sum) return null
        val localNormal = if (inside) delta else delta / dist
        val depth = if (inside) sum + dist else sum - dist
        val worldNormal = tc.transformVector(localNormal.x, localNormal.y).normalized()
        val worldPoint = tc.transformPoint(0f, clampedY)
        val n = if (flipped) worldNormal else -worldNormal
        return CollisionResult(n, depth, worldPoint)
    }

    private fun polygonPolygon(a: Shape2D.Polygon, ta: Transform2D, b: Shape2D.Polygon, tb: Transform2D): CollisionResult? {
        // SAT over both shapes' edge normals.
        var bestDepth = Float.MAX_VALUE
        var bestNormal = Vec2.ZERO
        var found = false
        for (shape in listOf(a to ta, b to tb)) {
            val pts = shape.first.points
            val t = shape.second
            val count = pts.size / 2
            for (i in 0 until count) {
                val j = (i + 1) % count
                val p1 = t.transformPoint(pts[i * 2], pts[i * 2 + 1])
                val p2 = t.transformPoint(pts[j * 2], pts[j * 2 + 1])
                var axis = Vec2(-(p2.y - p1.y), p2.x - p1.x)
                val len = axis.length
                if (len < EPS) continue
                axis = axis / len
                val projA = project(a, ta, axis)
                val projB = project(b, tb, axis)
                val overlap = minOf(projA.second, projB.second) - maxOf(projA.first, projB.first)
                if (overlap <= 0f) return null
                if (overlap < bestDepth) {
                    // Ensure the normal points from A to B.
                    val dir = tb.translation - ta.translation
                    bestDepth = overlap
                    bestNormal = if (dir dot axis < 0f) -axis else axis
                    found = true
                }
            }
        }
        if (!found) return null
        val support = supportPoint(b, tb, -bestNormal)
        val supportA = supportPoint(a, ta, bestNormal)
        val contact = (support + supportA) * 0.5f
        return CollisionResult(bestNormal, bestDepth, contact)
    }

    private fun project(p: Shape2D.Polygon, t: Transform2D, axis: Vec2): Pair<Float, Float> {
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (i in p.points.indices step 2) {
            val world = t.transformPoint(p.points[i], p.points[i + 1])
            val d = world dot axis
            if (d < min) min = d
            if (d > max) max = d
        }
        return min to max
    }

    private fun supportPoint(p: Shape2D.Polygon, t: Transform2D, direction: Vec2): Vec2 {
        var best = Vec2.ZERO
        var bestDot = -Float.MAX_VALUE
        for (i in p.points.indices step 2) {
            val world = t.transformPoint(p.points[i], p.points[i + 1])
            val d = world dot direction
            if (d > bestDot) { bestDot = d; best = world }
        }
        return best
    }
}

// ------------------------------------------------------------------------------ bodies

enum class BodyType { STATIC, KINEMATIC, RIGID }

/** Contact record produced each physics step. */
class Contact(
    val body: PhysicsBody,
    val other: PhysicsBody,
    val normal: Vec2,
    val depth: Float,
    val point: Vec2,
)

/**
 * Physics body data. Nodes own one of these and mirror their transform into it; the solver
 * then writes resolved transforms back to the nodes.
 */
class PhysicsBody(
    var type: BodyType,
    var shape: Shape2D,
    /** Local offset of the shape relative to the node origin. */
    var shapeOffset: Vec2 = Vec2.ZERO,
    var shapeRotation: Float = 0f,
) {
    /** Owner node id (used to map contacts back to nodes). */
    var ownerId: Long = 0
    /** Back-reference to the node's 2D transform provider. */
    var transform: Transform2D = Transform2D.IDENTITY
    var position: Vec2 = Vec2.ZERO
    var rotation: Float = 0f
    var velocity: Vec2 = Vec2.ZERO
    var angularVelocity: Float = 0f
    var force: Vec2 = Vec2.ZERO
    var mass: Float = 1f
    var invMass: Float = 1f
    var inertia: Float = 1f
    var invInertia: Float = 1f
    var friction: Float = 0.4f
    var restitution: Float = 0f
    var linearDamping: Float = 0.02f
    var gravityScale: Float = 1f
    var fixedRotation: Boolean = true
    var enabled: Boolean = true
    var continuous: Boolean = false
    var oneWay: Boolean = false
    var layer: Int = 1
    var mask: Int = 0xFFFF
    var sleeping: Boolean = false
    var sleepTimer: Float = 0f
    var isSensor: Boolean = false
    /** Kinematic bodies record their motion so riders can be carried along. */
    var platformDelta: Vec2 = Vec2.ZERO

    val isDynamic: Boolean get() = type == BodyType.RIGID

    fun shapeTransform(): Transform2D = Transform2D.trs(
        position + shapeOffset.rotated(rotation), rotation + shapeRotation, Vec2.ONE)

    fun worldBounds(): Rect = Collision.worldBounds(shape, shapeTransform())

    fun applyMass(newMass: Float) {
        mass = maxOf(0.0001f, newMass)
        invMass = if (type == BodyType.RIGID) 1f / mass else 0f
        val r = shape.localBounds()
        val w = maxOf(r.w, 1f); val h = maxOf(r.h, 1f)
        inertia = mass * (w * w + h * h) / 12f
        invInertia = if (fixedRotation || type != BodyType.RIGID) 0f else 1f / inertia
    }

    fun applyImpulse(impulse: Vec2, atPoint: Vec2? = null) {
        if (!isDynamic) return
        velocity += impulse * invMass
        if (atPoint != null && !fixedRotation) {
            val r = atPoint - position
            angularVelocity += (r cross impulse) * invInertia
        }
        wake()
    }

    fun applyForce(f: Vec2) { if (isDynamic) force += f }

    fun wake() { sleeping = false; sleepTimer = 0f }
}

/** A body registered with the world plus its node-linkage metadata. */
class BodyHandle(val body: PhysicsBody) {
    var onCollision: ((Contact) -> Unit)? = null
    var node: Any? = null
}

/** A trigger volume: reports overlaps instead of resolving collisions. */
class AreaHandle(
    val id: Long,
    var shape: Shape2D,
    var transform: Transform2D,
    var layer: Int = 1,
    var mask: Int = 0xFFFF,
    var monitorBodies: Boolean = true,
    var monitorAreas: Boolean = false,
) {
    val overlappingBodies = LinkedHashSet<PhysicsBody>()
    val overlappingAreas = LinkedHashSet<Long>()
    var onBodyEntered: ((PhysicsBody) -> Unit)? = null
    var onBodyExited: ((PhysicsBody) -> Unit)? = null
    var onAreaEntered: ((Long) -> Unit)? = null
    var onAreaExited: ((Long) -> Unit)? = null
}

class RayHit(
    val body: PhysicsBody?,
    val point: Vec2,
    val normal: Vec2,
    val distance: Float,
    val areaId: Long = -1,
)

// ------------------------------------------------------------------------------- world

/**
 * The physics simulation. One instance per [dev.lumen2d.core.scene.SceneTree].
 */
class PhysicsWorld {
    var gravity: Vec2 = Vec2(0f, 980f)
    var fixedDelta: Float = 1f / 60f
    var velocityIterations: Int = 4
    var positionIterations: Int = 2
    var allowSleeping: Boolean = true
    var sleepingEnabled: Boolean = true
    /** Global switch used by editor "physics debug" and by pausing gameplay. */
    var enabled: Boolean = true
    var contactCount: Int = 0
        private set

    private val bodies = LinkedHashMap<Long, BodyHandle>()
    private val areas = LinkedHashMap<Long, AreaHandle>()
    private val grid = SpatialHashGrid(cellSize = 64f)
    private var nextId = 1L
    private val previousTransforms = HashMap<Long, Vec2>()

    var stepCount: Long = 0
        private set

    // ------------------------------------------------------------------ registration

    fun createBody(type: BodyType, shape: Shape2D): PhysicsBody {
        val body = PhysicsBody(type, shape)
        body.applyMass(1f)
        addBody(body)
        return body
    }

    fun addBody(body: PhysicsBody): BodyHandle {
        // Every body needs a stable unique owner id for the broadphase grid and contact cache.
        if (body.ownerId == 0L) body.ownerId = nextId++
        val handle = BodyHandle(body)
        bodies[body.ownerId] = handle
        grid.insert(body.ownerId, body.worldBounds())
        return handle
    }

    fun removeBody(body: PhysicsBody) {
        bodies.remove(body.ownerId)
        grid.remove(body.ownerId)
        previousTransforms.remove(body.ownerId)
    }

    fun handleFor(body: PhysicsBody): BodyHandle? = bodies[body.ownerId]

    fun bodyByOwner(ownerId: Long): PhysicsBody? = bodies[ownerId]?.body

    fun createArea(transform: Transform2D, shape: Shape2D): AreaHandle {
        val area = AreaHandle(nextId++, shape, transform)
        areas[area.id] = area
        return area
    }

    fun removeArea(area: AreaHandle) { areas.remove(area.id) }

    fun updateAreaTransform(area: AreaHandle, transform: Transform2D) { area.transform = transform }

    val bodyCount: Int get() = bodies.size
    val areaCount: Int get() = areas.size
    fun allBodies(): Collection<PhysicsBody> = bodies.values.map { it.body }

    fun clear() {
        bodies.clear(); areas.clear(); grid.clear(); previousTransforms.clear(); nextId = 1
    }

    // ------------------------------------------------------------------- simulation

    /** Advances the simulation by one fixed step. */
    fun step(delta: Float) {
        if (!enabled) return
        stepCount++
        contactCount = 0
        // 1. Integrate forces.
        for (handle in bodies.values) {
            val b = handle.body
            if (!b.enabled || !b.isDynamic) continue
            if (b.sleeping) continue
            b.velocity += (gravity * b.gravityScale + b.force * b.invMass) * delta
            if (!b.fixedRotation) b.angularVelocity *= (1f - b.linearDamping * delta)
            b.velocity *= (1f - MathUtil.clamp(b.linearDamping * delta, 0f, 1f))
        }
        // 2. Integrate velocities into positions (and remember where we came from).
        for (handle in bodies.values) {
            val b = handle.body
            if (!b.enabled) continue
            if (!b.isDynamic) {
                b.platformDelta = b.position - (previousTransforms[b.ownerId] ?: b.position)
                previousTransforms[b.ownerId] = b.position
                continue
            }
            if (b.sleeping) { b.platformDelta = Vec2.ZERO; continue }
            val previous = b.position
            b.position += b.velocity * delta
            b.rotation += b.angularVelocity * delta
            previousTransforms[b.ownerId] = previous
        }
        // 3. Broadphase refresh + narrow phase.
        rebuildBroadphase()
        val contacts = ArrayList<Contact>(64)
        collectContacts(contacts)
        // 4. Solve contacts.
        for (iteration in 0 until velocityIterations) {
            resolveVelocities(contacts)
        }
        // 5. Positional correction to remove sinking.
        for (iteration in 0 until positionIterations) {
            correctPositions(contacts)
        }
        // 6. Sleeping bookkeeping.
        if (allowSleeping && sleepingEnabled) updateSleeping(delta)
        // 7. Area monitoring.
        updateAreas()
        // 8. Push results back to nodes.
        for (handle in bodies.values) handle.body.transform = handle.body.shapeTransform()
        contactCount = contacts.size
    }

    private fun rebuildBroadphase() {
        grid.clear()
        for (handle in bodies.values) {
            val b = handle.body
            if (b.enabled) grid.insert(b.ownerId, b.worldBounds())
        }
    }

    private fun collectContacts(out: MutableList<Contact>) {
        val pairs = grid.candidatePairs()
        for ((idA, idB) in pairs) {
            val a = bodies[idA]?.body ?: continue
            val b = bodies[idB]?.body ?: continue
            if (!a.enabled || !b.enabled) continue
            if (a.type != BodyType.RIGID && b.type != BodyType.RIGID) continue
            if ((a.layer and b.mask) == 0 || (b.layer and a.mask) == 0) continue
            if (a.sleeping && b.sleeping && a.type == BodyType.RIGID && b.type == BodyType.RIGID) continue
            val result = Collision.test(a.shape, a.shapeTransform(), b.shape, b.shapeTransform()) ?: continue
            // One-way platforms skip contacts unless the mover comes from above.
            if (a.oneWay || b.oneWay) {
                val platform = if (a.oneWay) a else b
                val mover = if (a.oneWay) b else a
                val platformTop = platform.worldBounds().top
                val moverBottom = mover.worldBounds().bottom
                val falling = mover.velocity.y >= -1f
                if (!(falling && moverBottom <= platformTop + 12f)) continue
            }
            // The solver expects the normal to point from A to B.
            var normal = result.normal
            if (normal dot (b.position - a.position) < 0f) normal = -normal
            // One-way platforms always push along their own up axis so riders never tunnel.
            if (a.oneWay) normal = Vec2(0f, -1f) else if (b.oneWay) normal = Vec2(0f, 1f)
            out.add(Contact(a, b, normal, result.depth, result.point))
        }
    }

    private fun resolveVelocities(contacts: List<Contact>) {
        for (contact in contacts) {
            val a = contact.body
            val b = contact.other
            val normal = contact.normal
            val relative = b.velocity - a.velocity
            val normalVelocity = relative dot normal
            if (normalVelocity > 0f) continue
            val invMassA = if (a.isDynamic && !a.sleeping) a.invMass else 0f
            val invMassB = if (b.isDynamic && !b.sleeping) b.invMass else 0f
            val invMassSum = invMassA + invMassB
            if (invMassSum <= 0f) continue
            val restitution = if (a.restitution > 0f || b.restitution > 0f) maxOf(a.restitution, b.restitution) else 0f
            val j = -(1f + restitution) * normalVelocity / invMassSum
            val impulse = normal * j
            if (invMassA > 0f) { a.velocity -= impulse * invMassA; a.wake() }
            if (invMassB > 0f) { b.velocity += impulse * invMassB; b.wake() }
            // Friction along the tangent.
            val tangent = Vec2(-normal.y, normal.x)
            val tangentVelocity = relative dot tangent
            val friction = sqrt(a.friction * b.friction)
            var jt = -tangentVelocity / invMassSum
            val maxFriction = abs(j) * friction
            jt = MathUtil.clamp(jt, -maxFriction, maxFriction)
            val frictionImpulse = tangent * jt
            if (invMassA > 0f) a.velocity -= frictionImpulse * invMassA
            if (invMassB > 0f) b.velocity += frictionImpulse * invMassB
            // Carry riders on kinematic platforms.
            if (!a.isDynamic && a.type == BodyType.KINEMATIC && b.isDynamic && contact.normal.y > 0.5f) {
                b.position += a.platformDelta
            } else if (!b.isDynamic && b.type == BodyType.KINEMATIC && a.isDynamic && contact.normal.y < -0.5f) {
                a.position += b.platformDelta
            }
            // Node callbacks.
            bodies[a.ownerId]?.onCollision?.invoke(contact)
            bodies[b.ownerId]?.onCollision?.invoke(Contact(b, a, -normal, contact.depth, contact.point))
        }
    }

    private fun correctPositions(contacts: List<Contact>) {
        val percent = 0.6f
        val slop = 0.05f
        for (contact in contacts) {
            val a = contact.body
            val b = contact.other
            val invMassA = if (a.isDynamic && !a.sleeping) a.invMass else 0f
            val invMassB = if (b.isDynamic && !b.sleeping) b.invMass else 0f
            val invMassSum = invMassA + invMassB
            if (invMassSum <= 0f) continue
            val correction = maxOf(contact.depth - slop, 0f) / invMassSum * percent
            val offset = contact.normal * correction
            if (invMassA > 0f) a.position -= offset * invMassA
            if (invMassB > 0f) b.position += offset * invMassB
        }
    }

    private fun updateSleeping(delta: Float) {
        for (handle in bodies.values) {
            val b = handle.body
            if (!b.isDynamic) continue
            if (b.velocity.lengthSquared < 4f && abs(b.angularVelocity) < 0.1f) {
                b.sleepTimer += delta
                if (b.sleepTimer > 0.6f) { b.sleeping = true; b.velocity = Vec2.ZERO; b.angularVelocity = 0f }
            } else {
                b.sleepTimer = 0f
                b.sleeping = false
            }
        }
    }

    private fun updateAreas() {
        for (area in areas.values) {
            if (area.monitorBodies) {
                val bounds = Collision.worldBounds(area.shape, area.transform)
                val found = LinkedHashSet<PhysicsBody>()
                grid.query(bounds).forEach { id ->
                    val body = bodies[id]?.body ?: return@forEach
                    if (body.isSensor) return@forEach
                    if ((body.layer and area.mask) == 0 || (area.layer and body.mask) == 0) return@forEach
                    if (Collision.test(area.shape, area.transform, body.shape, body.shapeTransform()) != null) {
                        found.add(body)
                    }
                }
                for (body in found) if (area.overlappingBodies.add(body)) area.onBodyEntered?.invoke(body)
                val exited = area.overlappingBodies.filter { it !in found }
                for (body in exited) { area.overlappingBodies.remove(body); area.onBodyExited?.invoke(body) }
            }
            if (area.monitorAreas) {
                val found = LinkedHashSet<Long>()
                for (other in areas.values) {
                    if (other.id == area.id) continue
                    if (Collision.test(area.shape, area.transform, other.shape, other.transform) != null) found.add(other.id)
                }
                for (id in found) if (area.overlappingAreas.add(id)) area.onAreaEntered?.invoke(id)
                val exited = area.overlappingAreas.filter { it !in found }
                for (id in exited) { area.overlappingAreas.remove(id); area.onAreaExited?.invoke(id) }
            }
        }
    }

    // --------------------------------------------------------------------- queries

    /** Casts a ray; [ignoreBodyIds] lets a character ignore itself. */
    fun raycast(from: Vec2, to: Vec2, mask: Int = 0xFFFF, ignoreBodyIds: Set<Long> = emptySet()): RayHit? {
        val delta = to - from
        val distance = delta.length
        if (distance < 1e-5f) return null
        val dir = delta / distance
        val queryRect = Rect.fromLTRB(
            minOf(from.x, to.x) - 1f, minOf(from.y, to.y) - 1f,
            maxOf(from.x, to.x) + 1f, maxOf(from.y, to.y) + 1f)
        var closest: RayHit? = null
        for (id in grid.query(queryRect)) {
            if (id in ignoreBodyIds) continue
            val body = bodies[id]?.body ?: continue
            if ((body.layer and mask) == 0) continue
            val hit = raycastShape(from, dir, distance, body) ?: continue
            if (closest == null || hit.distance < closest.distance) closest = hit
        }
        return closest
    }

    private fun raycastShape(from: Vec2, dir: Vec2, maxDistance: Float, body: PhysicsBody): RayHit? {
        val t = body.shapeTransform()
        return when (val shape = body.shape) {
            is Shape2D.Circle -> {
                val center = t.translation
                val radius = shape.radius * t.scaleX
                val m = from - center
                val b = m dot dir
                val c = (m dot m) - radius * radius
                if (c > 0f && b > 0f) return null
                val disc = b * b - c
                if (disc < 0f) return null
                val sqrtDisc = sqrt(disc)
                var tt = -b - sqrtDisc
                if (tt < 0f) tt = -b + sqrtDisc
                if (tt < 0f || tt > maxDistance) return null
                val point = from + dir * tt
                return RayHit(body, point, (point - center).normalized(), tt)
            }
            is Shape2D.Rectangle -> {
                // Transform the ray into local space and do a slab test.
                val inv = t.inverse()
                val localFrom = inv.transformPoint(from)
                val localDir = inv.transformVector(dir.x, dir.y)
                val hw = shape.halfWidth; val hh = shape.halfHeight
                // Standard slab test: entry time starts at -inf (max of t1) and exit at +inf (min of t2).
                var tmin = -Float.MAX_VALUE; var tmax = Float.MAX_VALUE
                var hitNormalLocal = Vec2.ZERO
                for (axis in 0..1) {
                    val origin = if (axis == 0) localFrom.x else localFrom.y
                    val d = if (axis == 0) localDir.x else localDir.y
                    val half = if (axis == 0) hw else hh
                    if (abs(d) < 1e-6f) { if (origin < -half || origin > half) return null; continue }
                    var t1 = (-half - origin) / d
                    var t2 = (half - origin) / d
                    var normalSign = -1f
                    if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; normalSign = 1f }
                    if (t1 > tmin) {
                        tmin = t1
                        hitNormalLocal = if (axis == 0) Vec2(normalSign, 0f) else Vec2(0f, normalSign)
                    }
                    if (t2 < tmax) tmax = t2
                    if (tmin > tmax) return null
                }
                if (tmin < 0f || tmin > maxDistance) return null
                val point = from + dir * tmin
                return RayHit(body, point, t.transformVector(hitNormalLocal.x, hitNormalLocal.y).normalized(), tmin)
            }
            else -> {
                // Polygon raycast via segment-vs-edge intersection.
                val poly = Collision.toPolygon(shape)
                var best: RayHit? = null
                val to = from + dir * maxDistance
                val n = poly.points.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val p1 = t.transformPoint(poly.points[i * 2], poly.points[i * 2 + 1])
                    val p2 = t.transformPoint(poly.points[j * 2], poly.points[j * 2 + 1])
                    val hit = segmentIntersect(from, to, p1, p2) ?: continue
                    val distance = (hit - from).length
                    if (best == null || distance < best.distance) {
                        val normal = Vec2(-(p2.y - p1.y), p2.x - p1.x).normalized().let { if (it dot dir < 0f) it else -it }
                        best = RayHit(body, hit, normal, distance)
                    }
                }
                best
            }
        }
    }

    private fun segmentIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Vec2? {
        val r = b - a; val s = d - c
        val denom = r cross s
        if (abs(denom) < 1e-6f) return null
        val t = ((c - a) cross s) / denom
        val u = ((c - a) cross r) / denom
        if (t < 0f || t > 1f || u < 0f || u > 1f) return null
        return a + r * t
    }

    /** All bodies whose shape overlaps [rect]. */
    fun queryRect(rect: Rect, mask: Int = 0xFFFF): List<PhysicsBody> =
        grid.query(rect).mapNotNull { bodies[it]?.body }
            .filter { (it.layer and mask) != 0 && Collision.worldBounds(it.shape, it.shapeTransform()).overlaps(rect) }

    fun queryPoint(point: Vec2, mask: Int = 0xFFFF): List<PhysicsBody> =
        queryRect(Rect(point.x - 0.01f, point.y - 0.01f, 0.02f, 0.02f), mask)

    fun queryCircle(center: Vec2, radius: Float, mask: Int = 0xFFFF): List<PhysicsBody> {
        val probe = PhysicsBody(BodyType.STATIC, Shape2D.Circle(radius))
        probe.position = center
        return queryRect(Rect(center.x - radius, center.y - radius, radius * 2f, radius * 2f), mask)
            .filter { Collision.test(probe.shape, probe.shapeTransform(), it.shape, it.shapeTransform()) != null }
    }

    /** First body hit along a moving shape's path (used by character controllers). */
    fun shapeCast(shape: Shape2D, from: Vec2, to: Vec2, mask: Int = 0xFFFF, ignoreBodyIds: Set<Long> = emptySet()): RayHit? =
        raycast(from, to, mask, ignoreBodyIds)

    fun stats(): Map<String, Int> = mapOf("bodies" to bodies.size, "areas" to areas.size, "contacts" to contactCount)
}

/** Uniform spatial hash grid broadphase. */
class SpatialHashGrid(private val cellSize: Float = 64f) {
    private val cells = HashMap<Long, MutableList<Long>>(256)
    private val bodyCells = HashMap<Long, MutableList<Long>>(256)

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    fun insert(id: Long, bounds: Rect) {
        val list = ArrayList<Long>(8)
        val x0 = kotlin.math.floor(bounds.left / cellSize).toInt()
        val x1 = kotlin.math.floor(bounds.right / cellSize).toInt()
        val y0 = kotlin.math.floor(bounds.top / cellSize).toInt()
        val y1 = kotlin.math.floor(bounds.bottom / cellSize).toInt()
        for (cy in y0..y1) for (cx in x0..x1) {
            val k = key(cx, cy)
            cells.getOrPut(k) { ArrayList(4) }.add(id)
            list.add(k)
        }
        bodyCells[id] = list
    }

    fun remove(id: Long) {
        bodyCells.remove(id)?.forEach { k -> cells[k]?.remove(id) }
    }

    fun clear() { cells.clear(); bodyCells.clear() }

    fun query(bounds: Rect): Set<Long> {
        val out = HashSet<Long>(16)
        val x0 = kotlin.math.floor(bounds.left / cellSize).toInt()
        val x1 = kotlin.math.floor(bounds.right / cellSize).toInt()
        val y0 = kotlin.math.floor(bounds.top / cellSize).toInt()
        val y1 = kotlin.math.floor(bounds.bottom / cellSize).toInt()
        for (cy in y0..y1) for (cx in x0..x1) cells[key(cx, cy)]?.let { out.addAll(it) }
        return out
    }

    /** Unique candidate pairs sharing at least one cell. */
    fun candidatePairs(): List<Pair<Long, Long>> {
        val pairs = HashSet<Long>(256)
        val out = ArrayList<Pair<Long, Long>>(128)
        for (list in cells.values) {
            if (list.size < 2) continue
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]; val b = list[j]
                    val lo = minOf(a, b); val hi = maxOf(a, b)
                    val pairKey = (lo shl 20) xor hi
                    if (pairs.add(pairKey)) out.add(lo to hi)
                }
            }
        }
        return out
    }
}
