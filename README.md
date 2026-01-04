# Pathfinder System Documentation

A comprehensive A* pathfinding implementation

## File Structure

- **`PathfinderCore.kt`** - Main A* pathfinding engine
- **`MovementController.kt`** - Player movement and rotation control
- **`PathNode.kt`** - Data structure for A* algorithm nodes


### Basic Usage

```kotlin
// Start pathfinding to a position
val targetPos = BlockPos(x, y, z)
PathfinderCore.setTarget(targetPos)

// Check if currently pathfinding
if (PathfinderCore.isPathfinding()) {
    // Pathfinder is active
}

// Stop pathfinding
PathfinderCore.clearPath()
```

### Integration with Cobalt

The pathfinder implements `IPathExec` interface and needs to be registered:

```kotlin
// In your module's onTick
override fun onTick(player: ClientPlayerEntity) {
    if (enabled) {
        PathfinderCore.onTick(player)
    }
}

// In your render method
override fun onWorldRenderLast(ctx: WorldRenderContext, player: ClientPlayerEntity) {
    if (enabled) {
        PathfinderCore.onWorldRenderLast(ctx, player)
    }
}
```

## ⚙️ Customization Guide

### 1. Adjusting Path Behavior

#### Make jumps less preferred:
```kotlin
// In PathfinderCore.kt, increase:
private const val JUMP_COST_MULTIPLIER = 2.0  // Default: 1.5
```

#### Make paths straighter:
```kotlin
// Increase turn costs:
private const val DIAGONAL_TURN_COST = 0.5    // Default: 0.3
private const val CARDINAL_TURN_COST = 0.4    // Default: 0.2
```

#### Prefer faster paths over optimal:
```kotlin
// Increase heuristic bias:
private const val HEURISTIC_BIAS = 1.5        // Default: 1.2
```

### 2. Movement Speed

#### Adjust rotation speed:
```kotlin
// In MovementController.kt:
val rotationSpeed = PathfinderModule.rotationSpeed.toFloat() * 1.0f  // Change multiplier
```

#### Change rotation smoothness:
```kotlin
// Reduce for more responsive rotation:
private const val MIN_TIME_BETWEEN_SMALL_ROTATIONS = 0.1  // Default: 0.16
```

### 3. Block Handling

#### Add custom walkable blocks:
```kotlin
// In PathfinderCore.kt, isPassableBlock():
return when (block) {
    Blocks.YOUR_CUSTOM_BLOCK -> true
    // ... existing cases
}
```

#### Add dangerous blocks to avoid:
```kotlin
// In PathfinderCore.kt, isWalkable():
val isDangerous = blockState.block == Blocks.YOUR_DANGEROUS_BLOCK ||
                 // ... existing checks
```

### 4. Performance Tuning

#### For longer paths (slower):
```kotlin
private const val MAX_ITERATIONS = 100000    // Default: 50000
```

#### For faster recalculation on stuck:
```kotlin
private const val STUCK_THRESHOLD = 40       // Default: 60
```

#### For more responsive path updates:
```kotlin
private const val RECALC_COOLDOWN = 500L     // Default: 1000L
```

### 5. Movement Capabilities

#### Increase jump distance:
```kotlin
// In PathfinderCore.kt:
private fun getMaxJumpDistance(player: ClientPlayerEntity): Int {
    return 5 + speedLevel + jumpBoost  // Default: 3 + ...
}
```

#### Change fall distance limit:
```kotlin
private const val MAX_FALL_DISTANCE = 100    // Default: 60
```

## 🎨 Visualization Customization

### Change waypoint colors:
```kotlin
// In PathfinderCore.kt, onWorldRenderLast():
val color = when {
    index == currentIndex -> Color(255, 0, 0, 200)    // Current: red
    index < currentIndex -> Color(50, 50, 50, 100)    // Passed: dark gray
    else -> Color(0, 255, 0, 200)                     // Future: green
}
```

### Adjust box size:
```kotlin
val box = Box(
    pos.x + 0.2, pos.y + 0.2, pos.z + 0.2,  // Smaller boxes
    pos.x + 0.8, pos.y + 0.8, pos.z + 0.8
)
```

### Change line thickness:
```kotlin
Render3D.drawLine(ctx, start, end, lineColor, esp = true, thickness = 5f)  // Default: 3f
```

## 🧪 Adding Custom Movement Patterns

### Example: Add 4-block jumps
```kotlin
// In getNeighbors():
for ((dx, dz) in cardinalDirs) {
    for (distance in 1..4) {  // Increased from 3
        for (height in 1..maxJumpHeight) {
            val jumpPos = pos.add(dx * distance, height, dz * distance)
            if (isWalkable(jumpPos) && isJumpPathClear(pos, jumpPos)) {
                neighbors.add(jumpPos)
            }
        }
    }
}
```

### Example: Add swimming support
```kotlin
// In isWalkable():
val canSwim = blockState.block == Blocks.WATER && player.hasEffect(StatusEffects.WATER_BREATHING)
return (hasSolidGround || canSwim) && canWalkThroughFeet && canWalkThroughHead && !isDangerous
```

## 🐛 Debugging

### Enable debug output:
```kotlin
private const val DEBUG = true  // In PathfinderCore.kt
```

This will print:
- Stuck detection messages
- Path calculation failures
- Render errors
- Other diagnostic information

### Monitor path status:
```kotlin
val path = PathfinderCore.getCurrentPath()
println("Waypoints: ${path?.size ?: 0}")
println("Current index: $currentIndex")
println("Distance to goal: ${player.blockPos.getManhattanDistance(targetPos)}")
```

## 📊 Algorithm Overview

### A* Pathfinding
1. **Open Set**: Positions to explore (priority queue by fCost)
2. **Closed Set**: Already explored positions
3. **Costs**:
   - `gCost`: Actual distance from start
   - `hCost`: Estimated distance to goal (heuristic)
   - `fCost`: Total estimated cost (gCost + hCost)

### Movement Features
- **Cardinal & Diagonal Movement**: 8-directional movement
- **Jumping**: 1-3 blocks horizontal, 1-2 blocks vertical (with effects)
- **Falling**: Up to 60 blocks safely
- **Climbing**: 1-2 block heights
- **Obstacle Avoidance**: Walls, gaps, dangerous blocks

### Optimization Techniques
- **Path Smoothing**: Removes unnecessary waypoints
- **Waypoint Skipping**: Jumps ahead when line-of-sight exists
- **Turn Costs**: Penalizes direction changes for smoother paths
- **Wall Avoidance**: Prefers open spaces over tight corridors
- **Stuck Detection**: Auto-recalculates if no progress

## 🔧 Common Issues

### Bot gets stuck in corners
- Increase `DIAGONAL_TURN_COST`
- Increase wall proximity penalties in `checkWallProximity()`

### Paths are too long/indirect
- Decrease `JUMP_COST_MULTIPLIER`
- Increase `HEURISTIC_BIAS`

### Bot doesn't jump when needed
- Check `shouldJump()` logic in `MovementController`
- Verify `maxJumpDistance` and `maxJumpHeight` calculations

### Rotation is jerky
- Increase `MIN_TIME_BETWEEN_SMALL_ROTATIONS`
- Decrease rotation speed multiplier

### Performance issues
- Decrease `MAX_ITERATIONS`
- Increase `RECALC_COOLDOWN`
- Reduce `MAX_FALL_DISTANCE`

## 🤝 Contributing

When modifying the pathfinder:
1. Test in various terrain types (plains, caves, buildings)
2. Test with different potion effects
3. Verify path visualization is correct
4. Check performance with long paths (>100 blocks)
5. Update documentation for significant changes

## 📄 License

Part of the Cobalt Example Addon - see LICENSE file in project root.
