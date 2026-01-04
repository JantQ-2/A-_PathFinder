package jant.path

import jant.path.command.ApiPathCommand
import jant.path.command.ExampleCommand
import jant.path.command.PathCommand
import jant.path.module.PathfinderModule
import jant.path.pathfinder.PathfinderCore
import org.cobalt.Cobalt
import org.cobalt.api.addon.Addon
import org.cobalt.api.command.CommandManager
import org.cobalt.api.module.Module

object PathfinderAddon : Addon() {

  override fun onLoad() {
    CommandManager.register(ExampleCommand)
    CommandManager.register(PathCommand)
    CommandManager.register(ApiPathCommand)

    Cobalt.setPathExec(PathfinderCore)
  }

  override fun onUnload() {

  }

  override fun getModules(): List<Module> {
    return listOf(PathfinderModule)
  }

}


