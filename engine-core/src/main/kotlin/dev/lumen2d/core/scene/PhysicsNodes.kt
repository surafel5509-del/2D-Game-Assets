/**
 * Lumen2D — physics nodes.
 *
 * The gameplay-facing half of the physics engine: bodies, areas and collision shapes that
 * scripts and the editor manipulate. Each node owns a [PhysicsBody] inside the tree's
 * [dev.lumen2d.core.physics.PhysicsWorld] and keeps transforms in sync both ways.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.physics.BodyType
import dev.lumen2d.core.physics.Collision
import dev.lumen2d.core.physics.PhysicsBody
import dev.lumen2d.core.physics.RayHit
import dev.lumen2d.core.physics.Shape2D
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Signal

/** Shape kinds offered by [CollisionShape2DNode.shapeKind]. */
enum class ShapeKind { RECTANGLE, CIRCLE, CAPSULE, POLYGON }

/**
 * Collision shape attached to a body or area. Shapes live here (not on the body) because
 * bodies support several shapes in Godot-style engines; Lumen2D keeps one primary shape per
 * collision node, and multiple CollisionShape2D children merge into their parent body.
 */
open class CollisionShape2DNode(name: String = "") : Node2D(name) {
    var shapeKind: ShapeKind = ShapeKind.RECTANGLE
    var size: Vec2 = Vec2(16f, 16f)
    var radius: Float = 8f
    var capsuleHeight: Float = 12f
    var polygonPoints: MutableList<Vec2> = ArrayList()
    var oneWay: Boolean = false
    var disabled: Boolean = false
    /** Editor-only colour override for the shape gizmo. */
    var debugColor: Color = Color.fromHex("#4CC9F0")

    fun buildShape(): Shape2D = when (shapeKind) {
        ShapeKind.RECTANGLE -> Shape2D.Rectangle(size)
        ShapeKind.CIRCLE -> Shape2D.Circle(radius)
        ShapeKind.CAPSULE -> Shape2D.Capsule(radius, capsuleHeight)
        ShapeKind.POLYGON -> if (polygonPoints.size >= 3)
            Shape2D.Polygon(polygonPoints.flatMap { listOf(it.x, it.y) }.toFloatArray())
        else Shape2D.Rectangle(size)
    }

    fun shapeTransform(): dev.lumen2d.core.math.Transform2D = globalTransform()

    /** World-space bounds of the shape (also used by the editor's selection). */
    fun shapeBounds(): Rect = Collision.worldBounds(buildShape(), shapeTransform())

    /** The owning body/area node, if any. */
    fun ownerBody(): CollisionOwner? = ancestors().firstOrNull { it is CollisionOwner } as? CollisionOwner

    override fun localBounds(): Rect = buildShape().localBounds()

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (tree?.debugDrawEnabled != true) return
        val t = globalTransform()
        when (shapeKind) {
            ShapeKind.CIRCLE -> renderer.drawCircle(t.translation.x, t.translation.y, radius * t.scaleX, debugColor, filled = false, lineWidth = 1f)
            else -> {
                val bounds = shapeBounds()
                renderer.drawRect(bounds, debugColor, filled = false, lineWidth = 1f)
            }
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("shapeKind", PropertyType.ENUM, "Shape", ShapeKind.RECTANGLE.name, category = "Shape",
            enumValues = ShapeKind.entries.map { it.name }),
        PropertyDef("size", PropertyType.VECTOR2, "Size", Vec2(16f, 16f), min = 1f, max = 4096f, step = 1f, category = "Shape"),
        PropertyDef("radius", PropertyType.FLOAT, "Radius", 8f, min = 0.5f, max = 2048f, step = 0.5f, category = "Shape"),
        PropertyDef("capsuleHeight", PropertyType.FLOAT, "Capsule height", 12f, min = 0f, max = 2048f, category = "Shape"),
        PropertyDef("oneWay", PropertyType.BOOL, "One way", false, category = "Shape",
            tooltip = "Only blocks movement from below (platforms you can jump through)"),
        PropertyDef("disabled", PropertyType.BOOL, "Disabled", false, category = "Shape"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "shapeKind" -> shapeKind.name
        "size" -> size
        "radius" -> radius
        "capsuleHeight" -> capsuleHeight
        "oneWay" -> oneWay
        "disabled" -> disabled
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "shapeKind" -> { shapeKind = runCatching { ShapeKind.valueOf(value.toString()) }.getOrDefault(shapeKind); return true }
            "size" -> { size = asVec2(value, size); return true }
            "radius" -> { radius = asFloat(value, 8f); return true }
            "capsuleHeight" -> { capsuleHeight = asFloat(value, 12f); return true }
            "oneWay" -> { oneWay = asBool(value, false); (ownerBody() as? PhysicsBodyNode)?.refreshShape(); return true }
            "disabled" -> { disabled = asBool(value, false); (ownerBody() as? PhysicsBodyNode)?.refreshShape(); return true }
        }
        return super.setProperty(property, value)
    }

    override fun onReady() { (ownerBody() as? PhysicsBodyNode)?.refreshShape() }
}

/** Implemented by nodes that own a [PhysicsBody]. */
interface CollisionOwner {
    val body: PhysicsBody?
    fun refreshShape()
}

/**
 * Shared implementation for the three body nodes: creates the body, syncs transforms and
 * exposes the common inspector properties.
 */
abstract class PhysicsBodyNode(name: String = "") : Node2D(name), CollisionOwner {

    var bodyType: BodyType = BodyType.STATIC
    var mass: Float = 1f
    var gravityScale: Float = 1f
    var friction: Float = 0.4f
    var restitution: Float = 0f
    var linearDamping: Float = 0.05f
    var fixedRotation: Boolean = true
    var collisionLayer: Int = 1
    var collisionMask: Int = 0xFFFF
    var enabledPhysics: Boolean = true

    override var body: PhysicsBody? = null
        protected set

    /** Emitted for every contact the body took part in during a physics step. */
    val bodyEntered = Signal<PhysicsBody>()
    val bodyExited = Signal<PhysicsBody>()
    /** Convenience flag used by platformers (updated by CharacterBody2DNode). */
    var onFloor: Boolean = false
        protected set

    override fun onReady() {
        val world = tree?.physics ?: return
        if (body == null) {
            body = world.createBody(bodyType, Shape2D.Rectangle(Vec2(16f, 16f)))
            body!!.ownerId = instanceId
        }
        refreshShape()
        syncToBody()
    }

    override fun onExitTree() {
        body?.let { tree?.physics?.removeBody(it) }
        body = null
    }

    /** Rebuilds the physics shape from child CollisionShape2D nodes. */
    override fun refreshShape() {
        val b = body ?: return
        val shapeNode = children.filterIsInstance<CollisionShape2DNode>().firstOrNull { !it.disabled }
        if (shapeNode != null) {
            b.shape = shapeNode.buildShape()
            b.shapeOffset = shapeNode.position
            b.shapeRotation = shapeNode.rotation
            b.oneWay = shapeNode.oneWay
        }
        b.type = bodyType
        b.mass = mass
        b.applyMass(mass)
        b.gravityScale = gravityScale
        b.friction = friction
        b.restitution = restitution
        b.linearDamping = linearDamping
        b.fixedRotation = fixedRotation
        b.layer = collisionLayer
        b.mask = collisionMask
        b.enabled = enabledPhysics
        b.transform = b.shapeTransform()
    }

    /** Pushes the node transform into the physics body (before a step). */
    protected open fun syncToBody() {
        val b = body ?: return
        b.position = globalPosition
        b.rotation = globalRotation
    }

    /** Pulls the solved transform back into the node (after a step). */
    protected open fun syncFromBody() {
        val b = body ?: return
        if (b.type == BodyType.STATIC) return
        setGlobalPosition(b.position)
        rotation = b.rotation
    }

    override fun onPhysicsProcess(delta: Float) {
        when (bodyType) {
            BodyType.RIGID -> syncFromBody()
            BodyType.STATIC -> syncToBody()
            BodyType.KINEMATIC -> syncToBody()
        }
    }

    var velocity: Vec2
        get() = body?.velocity ?: Vec2.ZERO
        set(value) { body?.velocity = value }

    fun applyImpulse(impulse: Vec2, atPoint: Vec2? = null) = body?.applyImpulse(impulse, atPoint)
    fun applyForce(force: Vec2) = body?.applyForce(force)

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("mass", PropertyType.FLOAT, "Mass", 1f, min = 0.01f, max = 1000f, step = 0.05f, category = "Physics"),
        PropertyDef("gravityScale", PropertyType.FLOAT, "Gravity scale", 1f, min = -4f, max = 8f, step = 0.05f, category = "Physics"),
        PropertyDef("friction", PropertyType.FLOAT, "Friction", 0.4f, min = 0f, max = 1f, step = 0.02f, category = "Physics"),
        PropertyDef("restitution", PropertyType.FLOAT, "Bounce", 0f, min = 0f, max = 1f, step = 0.02f, category = "Physics"),
        PropertyDef("linearDamping", PropertyType.FLOAT, "Damping", 0.05f, min = 0f, max = 2f, step = 0.01f, category = "Physics"),
        PropertyDef("fixedRotation", PropertyType.BOOL, "Fixed rotation", true, category = "Physics"),
        PropertyDef("collisionLayer", PropertyType.INT, "Layer", 1, min = 1f, max = 65535f, step = 1f, category = "Physics"),
        PropertyDef("collisionMask", PropertyType.INT, "Mask", 0xFFFF, min = 1f, max = 65535f, step = 1f, category = "Physics"),
        PropertyDef("enabledPhysics", PropertyType.BOOL, "Enabled", true, category = "Physics"),
        PropertyDef("debugVelocity", PropertyType.VECTOR2, "Velocity", Vec2.ZERO, category = "Debug", animateable = false),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "mass" -> mass
        "gravityScale" -> gravityScale
        "friction" -> friction
        "restitution" -> restitution
        "linearDamping" -> linearDamping
        "fixedRotation" -> fixedRotation
        "collisionLayer" -> collisionLayer
        "collisionMask" -> collisionMask
        "enabledPhysics" -> enabledPhysics
        "debugVelocity" -> velocity
        "onFloor" -> onFloor
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "mass" -> { mass = asFloat(value, 1f); refreshShape(); return true }
            "gravityScale" -> { gravityScale = asFloat(value, 1f); refreshShape(); return true }
            "friction" -> { friction = asFloat(value, 0.4f); refreshShape(); return true }
            "restitution" -> { restitution = asFloat(value, 0f); refreshShape(); return true }
            "linearDamping" -> { linearDamping = asFloat(value, 0.05f); refreshShape(); return true }
            "fixedRotation" -> { fixedRotation = asBool(value, true); refreshShape(); return true }
            "collisionLayer" -> { collisionLayer = asInt(value, 1); refreshShape(); return true }
            "collisionMask" -> { collisionMask = asInt(value, 0xFFFF); refreshShape(); return true }
            "enabledPhysics" -> { enabledPhysics = asBool(value, true); refreshShape(); return true }
            "velocity" -> { velocity = asVec2(value, Vec2.ZERO); return true }
            "debugVelocity" -> { velocity = asVec2(value, Vec2.ZERO); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Immovable world geometry: ground, walls, platforms. */
open class StaticBody2DNode(name: String = "") : PhysicsBodyNode(name) {
    init { bodyType = BodyType.STATIC }
}

/** Fully simulated body: crates, ragdolls, debris. */
open class RigidBody2DNode(name: String = "") : PhysicsBodyNode(name) {
    init { bodyType = BodyType.RIGID }

    override fun onPhysicsProcess(delta: Float) {
        syncFromBody()
    }

    /** Wakes the body (throwing a crate, stomping ground). */
    fun wake() = body?.wake()
}

/**
 * Kinematic body moved by scripts: players, enemies, moving platforms.
 * Provides `moveAndSlide`, `moveAndCollide` and floor/wall detection.
 */
open class CharacterBody2DNode(name: String = "") : PhysicsBodyNode(name) {
    init { bodyType = BodyType.KINEMATIC }

    /** Velocity applied by the next [moveAndSlide] call. */
    var motionVelocity: Vec2 = Vec2.ZERO
    var upDirection: Vec2 = Vec2.UP
    var floorMaxAngle: Float = 0.8f
    var slideCount: Int = 4
    var floorSnapLength: Float = 6f
    var movingPlatformCarry: Boolean = true

    var onWall: Boolean = false
        private set
    var onCeiling: Boolean = false
        private set
    var floorNormal: Vec2 = Vec2.UP
        private set
    var lastCollision: RayHit? = null
        private set
    /** Set true when the body just landed — sample games use it for dust and sound. */
    var justLanded: Boolean = false
        private set

    private var wasOnFloor = false
    private val carriedPlatforms = ArrayList<PhysicsBody>()

    /**
     * Moves the body by [motionVelocity] * delta, sliding along surfaces.
     * Returns the actual movement applied.
     */
    fun moveAndSlide(delta: Float, velocityOverride: Vec2? = null): Vec2 {
        val world = tree?.physics ?: return Vec2.ZERO
        val b = body ?: return Vec2.ZERO
        val vel = velocityOverride ?: motionVelocity
        val start = b.position
        var remaining = vel * delta
        var applied = Vec2.ZERO
        onWall = false; onCeiling = false
        justLanded = false
        val previousFloor = onFloor
        onFloor = false
        floorNormal = Vec2.UP

        var iterations = 0
        while (remaining.lengthSquared > 1e-8f && iterations < slideCount) {
            iterations++
            val target = b.position + remaining
            val hit = world.raycast(b.position, target, b.mask, setOf(b.ownerId))
            if (hit == null) {
                b.position = target
                applied += remaining
                remaining = Vec2.ZERO
                break
            } else {
                lastCollision = hit
                val travel = hit.distance.coerceAtLeast(0f)
                val direction = remaining.normalized()
                b.position = b.position + direction * travel
                applied += direction * travel
                val normal = hit.normal
                val upDot = normal dot upDirection
                if (upDot > floorMaxAngle) { onFloor = true; floorNormal = normal }
                else if (upDot < -floorMaxAngle) onCeiling = true
                else onWall = true
                // Slide: remove the component into the surface.
                val into = remaining dot normal
                remaining = remaining - normal * into
                if (remaining.lengthSquared < 1e-6f) break
            }
        }

        // Floor snapping keeps characters glued to slopes and moving platforms.
        if (previousFloor && !onFloor && floorSnapLength > 0f) {
            val below = b.position + Vec2(0f, floorSnapLength)
            val hit = world.raycast(b.position, below, b.mask, setOf(b.ownerId))
            if (hit != null && (hit.normal dot upDirection) > floorMaxAngle) {
                b.position = b.position + Vec2(0f, hit.distance)
                onFloor = true
                floorNormal = hit.normal
            }
        }

        if (onFloor && !wasOnFloor) justLanded = true
        wasOnFloor = onFloor

        // Carry riders on moving platforms (we track platforms we stood on last step).
        if (movingPlatformCarry && onFloor) {
            for (platform in carriedPlatforms) b.position += platform.platformDelta
        }

        b.velocity = applied / delta.coerceAtLeast(1e-4f)
        setGlobalPosition(b.position)
        return applied
    }

    /** Moves once and stops at the first collision (no sliding). */
    fun moveAndCollide(motion: Vec2): RayHit? {
        val world = tree?.physics ?: return null
        val b = body ?: return null
        val target = b.position + motion
        val hit = world.raycast(b.position, target, b.mask, setOf(b.ownerId))
        if (hit == null) {
            b.position = target
        } else {
            lastCollision = hit
            val direction = motion.normalized()
            b.position = b.position + direction * hit.distance.coerceAtLeast(0f)
        }
        setGlobalPosition(b.position)
        return hit
    }

    /** Downward ray used for ground checks when not moving. */
    fun isOnFloorCheck(distance: Float = 4f): Boolean {
        val world = tree?.physics ?: return false
        val b = body ?: return false
        val hit = world.raycast(b.position, b.position + Vec2(0f, distance), b.mask, setOf(b.ownerId)) ?: return false
        return (hit.normal dot upDirection) > floorMaxAngle
    }

    override fun onPhysicsProcess(delta: Float) {
        // Scripts drive motionVelocity; nodes that prefer automatic movement can set
        // autoMoveVelocity and let the engine apply it.
        if (autoMove) moveAndSlide(delta)
        syncToBody()
    }

    /** When true the node calls [moveAndSlide] itself every physics step. */
    var autoMove: Boolean = false

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("floorMaxAngle", PropertyType.FLOAT, "Floor angle", 0.8f, min = 0f, max = 1.6f, step = 0.05f, category = "Character"),
        PropertyDef("floorSnapLength", PropertyType.FLOAT, "Floor snap", 6f, min = 0f, max = 32f, category = "Character"),
        PropertyDef("movingPlatformCarry", PropertyType.BOOL, "Carried by platforms", true, category = "Character"),
        PropertyDef("autoMove", PropertyType.BOOL, "Auto move", false, category = "Character"),
        PropertyDef("motionX", PropertyType.FLOAT, "Motion X", 0f, min = -4000f, max = 4000f, step = 1f, category = "Character"),
        PropertyDef("motionY", PropertyType.FLOAT, "Motion Y", 0f, min = -4000f, max = 4000f, step = 1f, category = "Character"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "floorMaxAngle" -> floorMaxAngle
        "floorSnapLength" -> floorSnapLength
        "movingPlatformCarry" -> movingPlatformCarry
        "autoMove" -> autoMove
        "motionX" -> motionVelocity.x
        "motionY" -> motionVelocity.y
        "velocity" -> motionVelocity
        "onFloor" -> onFloor
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "floorMaxAngle" -> { floorMaxAngle = asFloat(value, 0.8f); return true }
            "floorSnapLength" -> { floorSnapLength = asFloat(value, 6f); return true }
            "movingPlatformCarry" -> { movingPlatformCarry = asBool(value, true); return true }
            "autoMove" -> { autoMove = asBool(value, false); return true }
            "motionX" -> { motionVelocity = Vec2(asFloat(value, 0f), motionVelocity.y); return true }
            "motionY" -> { motionVelocity = Vec2(motionVelocity.x, asFloat(value, 0f)); return true }
            "velocity" -> { motionVelocity = asVec2(value, Vec2.ZERO); return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * Trigger volume: reports bodies entering/leaving without blocking them. Used for pickups,
 * checkpoints, damage zones, ladders and "door opens when player is near".
 */
open class Area2DNode(name: String = "") : Node2D(name) {
    var layer: Int = 1
    var mask: Int = 0xFFFF
    var monitorBodies: Boolean = true
    var monitorAreas: Boolean = false
    var disabled: Boolean = false

    var shapeKind: ShapeKind = ShapeKind.RECTANGLE
    var size: Vec2 = Vec2(32f, 32f)
    var radius: Float = 16f

    private var handle: dev.lumen2d.core.physics.AreaHandle? = null

    val bodyEntered = Signal<PhysicsBody>()
    val bodyExited = Signal<PhysicsBody>()
    val areaEntered = Signal<Long>()
    val areaExited = Signal<Long>()

    override fun onReady() {
        val world = tree?.physics ?: return
        val shape: Shape2D = when (shapeKind) {
            ShapeKind.CIRCLE -> Shape2D.Circle(radius)
            ShapeKind.POLYGON -> Shape2D.Rectangle(size)
            else -> Shape2D.Rectangle(size)
        }
        handle = world.createArea(globalTransform(), shape).also { area ->
            area.layer = layer
            area.mask = mask
            area.monitorBodies = monitorBodies
            area.monitorAreas = monitorAreas
            area.onBodyEntered = { body -> bodyEntered.emit(body) }
            area.onBodyExited = { body -> bodyExited.emit(body) }
            area.onAreaEntered = { id -> areaEntered.emit(id) }
            area.onAreaExited = { id -> areaExited.emit(id) }
        }
    }

    override fun onExitTree() {
        handle?.let { tree?.physics?.removeArea(it) }
        handle = null
    }

    override fun onPhysicsProcess(delta: Float) {
        val world = tree?.physics ?: return
        val area = handle ?: return
        area.transform = globalTransform()
        area.mask = mask
        area.layer = layer
        area.monitorBodies = monitorBodies && !disabled
        area.monitorAreas = monitorAreas && !disabled
    }

    val overlappingBodies: List<PhysicsBody> get() = handle?.overlappingBodies?.toList() ?: emptyList()
    val overlapCount: Int get() = handle?.overlappingBodies?.size ?: 0

    /** True when a specific body (usually the player) is inside the area. */
    fun contains(body: PhysicsBody?): Boolean = body != null && handle?.overlappingBodies?.contains(body) == true

    /** Finds the first overlapping node that belongs to a group (pickups, enemies). */
    fun firstNodeInGroup(group: String): Node? {
        for (body in overlappingBodies) {
            val node = tree?.nodeAt("/root")?.descendants()?.firstOrNull { it.instanceId == body.ownerId }
            if (node != null && node.isInGroup(group)) return node
        }
        return null
    }

    override fun localBounds(): Rect = if (shapeKind == ShapeKind.CIRCLE) Rect(-radius, -radius, radius * 2f, radius * 2f)
        else Rect(-size.x / 2f, -size.y / 2f, size.x, size.y)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (tree?.debugDrawEnabled != true) return
        val color = Color.fromHex("#F72585").withAlpha(0.7f)
        val t = globalTransform()
        if (shapeKind == ShapeKind.CIRCLE) {
            renderer.drawCircle(t.translation.x, t.translation.y, radius, color, filled = false, lineWidth = 1f)
        } else {
            renderer.drawRect(Rect(t.translation.x - size.x / 2f, t.translation.y - size.y / 2f, size.x, size.y),
                color, filled = false, lineWidth = 1f)
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("shapeKind", PropertyType.ENUM, "Shape", ShapeKind.RECTANGLE.name, category = "Area",
            enumValues = listOf(ShapeKind.RECTANGLE.name, ShapeKind.CIRCLE.name)),
        PropertyDef("size", PropertyType.VECTOR2, "Size", Vec2(32f, 32f), min = 1f, max = 4096f, step = 1f, category = "Area"),
        PropertyDef("radius", PropertyType.FLOAT, "Radius", 16f, min = 1f, max = 2048f, category = "Area"),
        PropertyDef("layer", PropertyType.INT, "Layer", 1, min = 1f, max = 65535f, step = 1f, category = "Area"),
        PropertyDef("mask", PropertyType.INT, "Mask", 0xFFFF, min = 1f, max = 65535f, step = 1f, category = "Area"),
        PropertyDef("monitorBodies", PropertyType.BOOL, "Monitor bodies", true, category = "Area"),
        PropertyDef("monitorAreas", PropertyType.BOOL, "Monitor areas", false, category = "Area"),
        PropertyDef("disabled", PropertyType.BOOL, "Disabled", false, category = "Area"),
        PropertyDef("overlapCount", PropertyType.INT, "Overlaps", 0, category = "Debug", animateable = false),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "shapeKind" -> shapeKind.name
        "size" -> size
        "radius" -> radius
        "layer" -> layer
        "mask" -> mask
        "monitorBodies" -> monitorBodies
        "monitorAreas" -> monitorAreas
        "disabled" -> disabled
        "overlapCount" -> overlapCount
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "shapeKind" -> { shapeKind = runCatching { ShapeKind.valueOf(value.toString()) }.getOrDefault(shapeKind); return true }
            "size" -> { size = asVec2(value, size); return true }
            "radius" -> { radius = asFloat(value, 16f); return true }
            "layer" -> { layer = asInt(value, 1); return true }
            "mask" -> { mask = asInt(value, 0xFFFF); return true }
            "monitorBodies" -> { monitorBodies = asBool(value, true); return true }
            "monitorAreas" -> { monitorAreas = asBool(value, false); return true }
            "disabled" -> { disabled = asBool(value, false); return true }
        }
        return super.setProperty(property, value)
    }
}
