package jant.path.command

import jant.path.pathfinder.PathfinderCore
import net.minecraft.util.math.BlockPos
import org.cobalt.api.command.Command
import org.cobalt.api.command.annotation.DefaultHandler
import org.cobalt.api.command.annotation.SubCommand
import org.cobalt.api.util.ChatUtils

object PathCommand : Command(
    name = "path",
    aliases = arrayOf("pathfind", "pf")
) {

    @DefaultHandler
    fun main() {
        ChatUtils.sendMessage("§7Path commands: §fgoto <x> <y> <z>§7, §fstop§7, §fstatus")
    }

    @SubCommand
    fun goto(x: Int, y: Int, z: Int) {
        val pos = BlockPos(x, y, z)
        PathfinderCore.setTarget(pos)
        ChatUtils.sendMessage("§aPathfinding to §f$pos")
    }

    @SubCommand
    fun stop() {
        PathfinderCore.clearPath()
        ChatUtils.sendMessage("§cPathfinding stopped")
    }

    @SubCommand
    fun status() {
        val status = if (PathfinderCore.isPathfinding()) {
            "§aActive"
        } else {
            "§7Inactive"
        }
        ChatUtils.sendMessage("§7Pathfinding status: $status")
    }
}

