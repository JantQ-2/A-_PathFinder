package jant.path.pathfinder

import jant.path.module.PathfinderModule
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.rotation.EasingType
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Controls player movement and rotation for pathfinding.
 * 
 * This object handles:
 * - Smooth Bezier curve camera rotation toward targets
 * - Movement input simulation (W/A/S/D keys)
 * - Jump detection and execution
 * - Sprint control
 * 
 * CUSTOMIZATION TIPS:
 * - Adjust rotation speed via PathfinderModule.rotationSpeed
 * - Modify Bezier control points for different curve shapes
 * - Change angle thresholds in moveTowards() for different movement patterns
 * - Modify pitch calculation in updateRotation() for custom look angles
 */
object MovementController {

    // Number of ticks remaining for jump execution (prevents spam jumping)
    var jumpticks = 1
    
    private val mc = MinecraftClient.getInstance()
    private var targetYaw: Float? = null  // Desired horizontal rotation
    private var targetPitch: Float = 5.0f  // Desired vertical rotation
    private var lastFrameTime = System.nanoTime()  // For delta time calculation
    private var rotationProgress: Float = 1.0f  // 0.0 to 1.0, where 1.0 = at target
    private var startYaw: Float = 0f
    private var startPitch: Float = 0f
    
    /**
     * Cubic Bezier interpolation function.
     * Creates smooth, natural-looking curves between start and end points.
     */
    private fun cubicBezier(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val u = 1f - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t
        
        return uuu * p0 + 3 * uu * t * p1 + 3 * u * tt * p2 + ttt * p3
    }
    
    /**
     * Apply Bezier curve to rotation with control points for smooth motion.
     */
    private fun bezierEase(t: Float): Float {
        // Control points for smooth acceleration and deceleration
        // Adjust these for different curve shapes:
        // (0.25, 0.1) = start control point (slow start)
        // (0.75, 0.9) = end control point (slow end)
        return cubicBezier(t, 0f, 0.25f, 0.75f, 1f)
    }

    /**
     * Smoothly rotates the player's camera toward a target position using Bezier curves.
     * 
     * This function calculates the required yaw (horizontal) and pitch (vertical) rotations,
     * then applies them gradually using Bezier interpolation for very smooth, natural movement.
     * Also locks player's look control during pathfinding.
     * 
     * @param player The player entity to rotate
     * @param target The 3D position to look at
     * 
     * CUSTOMIZATION:
     * - Change basePitch value to adjust default looking angle
     * - Modify Bezier control points in bezierEase() for different curve shapes
     * - Adjust rotationSpeed for different speeds
     */
    fun updateRotation(player: ClientPlayerEntity, target: Vec3d) {
        // Lock player's look control during automated rotation
        MovementManager.setLookLock(true)
        // Calculate player's eye position (where the camera is)
        val playerPos = Vec3d(player.x, player.y + player.getEyeHeight(player.pose), player.z)
        val direction = target.subtract(playerPos)

        // Calculate horizontal distance for pitch calculation
        val horizontalDist = kotlin.math.sqrt(direction.x * direction.x + direction.z * direction.z)
        
        // Calculate target yaw (horizontal rotation) using arctangent
        val newTargetYaw = Math.toDegrees(atan2(-direction.x, direction.z)).toFloat()

        val verticalDiff = direction.y
        
        // Base pitch - slight downward angle (positive = down in Minecraft)
        val basePitch = 5.0f

        // Calculate target pitch (vertical rotation) based on height difference
        val newTargetPitch = if (kotlin.math.abs(verticalDiff) < 0.5) {
            // Nearly level - use base pitch
            basePitch
        } else if (verticalDiff > 2.0) {
            // Looking significantly upward
            val calculatedPitch = -Math.toDegrees(atan2(verticalDiff, horizontalDist)).toFloat()
            calculatedPitch.coerceIn(basePitch - 5f, basePitch + 5f)
        } else if (verticalDiff < -2.0) {
            // Looking significantly downward
            val calculatedPitch = -Math.toDegrees(atan2(verticalDiff, horizontalDist)).toFloat()
            calculatedPitch.coerceIn(basePitch - 5f, basePitch + 5f)
        } else {
            // Small vertical differences - dampened response (60% of calculated angle)
            val calculatedPitch = -Math.toDegrees(atan2(verticalDiff, horizontalDist)).toFloat() * 0.6f
            calculatedPitch.coerceIn(basePitch - 5f, basePitch + 5f)
        }

        // Check if we have a new target
        var targetDiffYaw = newTargetYaw - (targetYaw ?: newTargetYaw)
        while (targetDiffYaw > 180) targetDiffYaw -= 360
        while (targetDiffYaw < -180) targetDiffYaw += 360
        
        val targetDiffPitch = kotlin.math.abs(newTargetPitch - targetPitch)
        val hasNewTarget = kotlin.math.abs(targetDiffYaw) > 1.0f || targetDiffPitch > 0.5f
        
        if (hasNewTarget) {
            // Start new rotation
            startYaw = player.yaw
            startPitch = player.pitch
            targetYaw = newTargetYaw
            targetPitch = newTargetPitch
            rotationProgress = 0.0f
        }

        // If we're already at target, nothing to do
        if (rotationProgress >= 1.0f) {
            return
        }

        // Calculate time since last frame for smooth, frame-rate independent rotation
        val frameTime = System.nanoTime()
        val deltaTime = ((frameTime - lastFrameTime) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        lastFrameTime = frameTime

        // Get rotation speed multiplier from module settings
        val speedMultiplier = PathfinderModule.rotationSpeed.toFloat()
        
        // Calculate total rotation needed
        val yawDiff = targetYaw?.let { target ->
            var diff = target - startYaw
            while (diff > 180) diff -= 360
            while (diff < -180) diff += 360
            diff
        } ?: 0f
        
        val pitchDiff = targetPitch - startPitch
        val totalRotation = kotlin.math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)
        
        // Dynamic rotation speed: larger rotations are faster
        // Small rotations (< 40°): use base speed
        // Large rotations (> 40°): scale up speed progressively
        val baseRotationTime = 0.5f  // seconds for 90 degrees
        val minRotationTime = 0.15f / speedMultiplier  // minimum time for any rotation
        
        val timeToComplete = if (totalRotation > 0) {
            // Apply speed boost for rotations larger than 40 degrees
            val speedBoost = if (totalRotation > 40.0f) {
                // Scale from 1.0x at 40° to 3.0x at 180°
                1.0f + ((totalRotation - 40.0f) / 140.0f) * 2.0f
            } else {
                1.0f
            }
            
            val calculatedTime = (totalRotation / 90.0f) * baseRotationTime / (speedMultiplier * speedBoost)
            calculatedTime.coerceAtLeast(minRotationTime)
        } else {
            0.016f  // one frame
        }
        
        // Calculate progress increment for this frame
        val progressIncrement = if (timeToComplete > 0) {
            (deltaTime.toFloat() / timeToComplete).coerceAtMost(1.0f)
        } else {
            1.0f
        }
        
        // Advance progress based on rotation speed
        rotationProgress = (rotationProgress + progressIncrement).coerceIn(0f, 1f)
        
        // Apply Bezier easing to progress
        val easedProgress = bezierEase(rotationProgress)
        
        // Calculate yaw with proper angle wrapping
        targetYaw?.let { target ->
            val interpolatedYaw = startYaw + (yawDiff * easedProgress)
            player.yaw = interpolatedYaw
        }
        
        // Calculate pitch
        val interpolatedPitch = startPitch + (pitchDiff * easedProgress)
        player.pitch = interpolatedPitch.coerceIn(-90f, 90f)
    }
    
    /**
     * Stops rotation and releases look lock.
     * Call this when pathfinding ends to restore player control.
     */
    fun stopRotation() {
        MovementManager.setLookLock(false)
        rotationProgress = 1.0f
    }

    /**
     * Simulates WASD key presses to move the player toward a target position.
     * 
     * This function calculates the angle between the player's facing direction and the target,
     * then presses the appropriate movement keys (forward, back, left, right) to move in that direction.
     * 
     * @param player The player entity to move
     * @param target The 3D position to move toward
     * 
     * ANGLE ZONES (customizable):
     * - Forward only: -45° to 45°
     * - Forward+Right: 45° to 90°
     * - Forward+Left: -90° to -45°
     * - Strafe Right: 90° to 135°
     * - Strafe Left: -135° to -90°
     * - Backward+Right: 135° to 180°
     * - Backward+Left: -180° to -135°
     */
    fun moveTowards(player: ClientPlayerEntity, target: Vec3d) {
        val playerPos = Vec3d(player.x, player.y, player.z)
        val direction = target.subtract(playerPos).normalize()

        // Calculate the angle we need to move toward
        val targetYaw = Math.toDegrees(atan2(-direction.x, direction.z)).toFloat()
        var angleDiff = targetYaw - player.yaw

        // Normalize to [-180, 180]
        while (angleDiff > 180) angleDiff -= 360
        while (angleDiff < -180) angleDiff += 360

        // Reset all movement keys first
        mc.options.forwardKey.setPressed(false)
        mc.options.backKey.setPressed(false)
        mc.options.leftKey.setPressed(false)
        mc.options.rightKey.setPressed(false)

        val absAngle = kotlin.math.abs(angleDiff)

        // Determine which keys to press based on angle to target
        when {

            absAngle < 45 -> {
                mc.options.forwardKey.setPressed(true)
            }

            angleDiff in 45.0..90.0 -> {
                mc.options.forwardKey.setPressed(true)
                mc.options.rightKey.setPressed(true)
            }

            angleDiff in -90.0..-45.0 -> {
                mc.options.forwardKey.setPressed(true)
                mc.options.leftKey.setPressed(true)
            }

            angleDiff in 90.0..135.0 -> {
                mc.options.rightKey.setPressed(true)
            }

            angleDiff in -135.0..-90.0 -> {
                mc.options.leftKey.setPressed(true)
            }

            angleDiff > 135 -> {
                mc.options.backKey.setPressed(true)
                mc.options.rightKey.setPressed(true)
            }

            angleDiff < -135 -> {
                mc.options.backKey.setPressed(true)
                mc.options.leftKey.setPressed(true)
            }
        }
    }

    /**
     * Controls sprint behavior.
     * Simple wrapper to enable/disable sprinting.
     * 
     * @param shouldSprint Whether to enable sprinting
     */
    fun setSprinting(shouldSprint: Boolean) {
        mc.options.sprintKey.setPressed(shouldSprint)
    }

    /**
     * Executes a jump if conditions are met.
     * Uses jumpticks counter to ensure jumps complete properly without spam.
     * 
     * @param player The player entity to make jump
     */
    fun jump(player: ClientPlayerEntity) {
        if (player.isOnGround && jumpticks > 0) {
            mc.options.jumpKey.setPressed(true)
            jumpticks--
        } else if (jumpticks <= 0) {
            mc.options.jumpKey.setPressed(false)
            jumpticks = 1
        }
    }

    /**
     * Resets jump state.
     * Call this to cancel an ongoing jump or reset the jump counter.
     */
    fun resetJump() {
        mc.options.jumpKey.setPressed(false)
        jumpticks = 1
    }
    /*
    fun rightClick() {
        mc.options.useKey.setPressed(true)
    }
    */

    /**
     * Determines whether the player should jump to reach the target position.
     * 
     * Checks for:
     * 1. Obstacles in the way (walls, blocks)
     * 2. Gaps in the ground ahead
     * 3. Height differences requiring a jump
     * 
     * Accounts for potion effects (Jump Boost, Speed) and special blocks (slabs, stairs, snow).
     * 
     * @param player The player entity
     * @param target The target block position
     * @return true if the player should jump, false otherwise
     * 
     * CUSTOMIZATION:
     * - Modify maxHorizontalDist calculation to change jump range detection
     * - Adjust effectiveHeightDiff calculation for different height thresholds
     * - Add/remove block types from slab/stair detection logic
     */
    fun shouldJump(player: ClientPlayerEntity, target: BlockPos): Boolean {
        val world = mc.world ?: return false
        val playerPos = BlockPos.ofFloored(player.x, player.y, player.z)

        // Only jump when on ground to prevent mid-air jump spam
        if (!player.isOnGround) return false

        // Check for potion effects that influence jumping
        val jumpBoostEffect = player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.JUMP_BOOST)
        val jumpBoostLevel = if (jumpBoostEffect != null) jumpBoostEffect.amplifier + 1 else 0
        val speedEffect = player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED)
        val speedLevel = if (speedEffect != null) speedEffect.amplifier + 1 else 0

        // Calculate max horizontal jump distance based on effects
        val maxHorizontalDist = 3.0 + speedLevel + (jumpBoostLevel * 0.5)

        val dx = target.x - playerPos.x
        val dz = target.z - playerPos.z
        val horizontalDistance = sqrt((dx * dx + dz * dz).toDouble())

        if (horizontalDistance > maxHorizontalDist) return false

        val directionX = if (kotlin.math.abs(dx) > kotlin.math.abs(dz)) kotlin.math.sign(dx.toFloat()).toInt() else 0
        val directionZ = if (kotlin.math.abs(dz) > kotlin.math.abs(dx)) kotlin.math.sign(dz.toFloat()).toInt() else 0

        val ahead = playerPos.add(directionX, 0, directionZ)
        val blockAhead = world.getBlockState(ahead)
        val groundAhead = world.getBlockState(ahead.down())

        val blockAheadName = blockAhead.block.toString().lowercase()
        val isSlabStairOrSnow = blockAheadName.contains("slab") || 
                                blockAheadName.contains("stair") ||
                                blockAheadName.contains("snow")

        val hasObstacle = !blockAhead.isAir && blockAhead.isSolidBlock(world, ahead) && !isSlabStairOrSnow

        val minGapForJump = if (speedLevel < 3) 1 else 2
        var gapDetected = false

        for (dist in 1..minOf(3, horizontalDistance.toInt() + 1)) {
            val checkPos = playerPos.add(directionX * dist, 0, directionZ * dist)
            val groundAtCheck = world.getBlockState(checkPos.down())

            val groundName = groundAtCheck.block.toString().lowercase()
            val isSlabOrStairGround = groundName.contains("slab") || 
                                     groundName.contains("stair") ||
                                     groundName.contains("snow")

            if (!groundAtCheck.isSolidBlock(world, checkPos.down()) && !isSlabOrStairGround) {

                if (dist >= minGapForJump) {
                    gapDetected = true
                    break
                }
            }
        }

        val heightDiff = target.y - playerPos.y

        val targetGroundBelow = world.getBlockState(target.down())
        val targetGroundName = targetGroundBelow.block.toString().lowercase()
        val targetHasSlabBelow = targetGroundName.contains("slab") || 
                                 targetGroundName.contains("stair") ||
                                 targetGroundName.contains("snow")

        val effectiveHeightDiff = if (heightDiff == 1 && targetHasSlabBelow) 0.3 else heightDiff.toDouble()
        val targetIsHigher = effectiveHeightDiff > 0.5
        
        // Only jump for obstacles or when target is higher
        // Don't jump for gaps (drops) - let player walk off naturally
        return (hasObstacle || targetIsHigher) && !gapDetected
    }

    /**
     * Releases all movement keys and stops the player.
     * Call this when pathfinding is complete or cancelled.
     * 
     * @param player The player entity (currently unused but kept for API consistency)
     */
    fun stopMovement(player: ClientPlayerEntity) {
        mc.options.sprintKey.setPressed(false)
        mc.options.forwardKey.setPressed(false)
        mc.options.backKey.setPressed(false)
        mc.options.leftKey.setPressed(false)
        mc.options.rightKey.setPressed(false)
        mc.options.jumpKey.setPressed(false)
        mc.options.sneakKey.setPressed(false)
    }
}

