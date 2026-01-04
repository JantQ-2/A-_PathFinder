package jant.path.pathfinder

import net.minecraft.util.math.BlockPos

/**
 * Represents a single node in the A* pathfinding algorithm.
 * 
 * This class stores information about a block position in the pathfinding grid,
 * including costs used by the A* algorithm to determine the optimal path.
 * 
 * @property pos The block position this node represents
 * @property gCost The actual cost from the start node to this node (distance traveled)
 * @property hCost The estimated (heuristic) cost from this node to the goal
 * @property parent Reference to the previous node in the path (used to reconstruct the final path)
 */
data class PathNode(
    val pos: BlockPos,
    var gCost: Double = Double.MAX_VALUE,  // Initialized to max value until calculated
    var hCost: Double = 0.0,
    var parent: PathNode? = null
) {
    /**
     * Total estimated cost of the path through this node.
     * Formula: fCost = gCost + hCost
     * 
     * This is used by the priority queue to select the most promising node to explore next.
     */
    val fCost: Double
        get() = gCost + hCost

    /**
     * Two nodes are equal if they represent the same block position.
     * This allows proper deduplication in sets and maps.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathNode) return false
        return pos == other.pos
    }

    /**
     * Hash based on position only, ensuring nodes at the same position
     * have the same hash code regardless of their cost values.
     */
    override fun hashCode(): Int = pos.hashCode()
}

