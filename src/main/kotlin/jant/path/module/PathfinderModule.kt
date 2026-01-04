package jant.path.module

import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.*
import org.cobalt.internal.rotation.EasingType

object PathfinderModule : Module(
  name = "Pathfinder",
) {

  val rotationEasing by ModeSetting(
    name = "Rotation Easing",
    description = "Easing type for smooth rotation during pathfinding",
    defaultValue = 0,
    options = arrayOf(
      "LINEAR",
      "EASE_IN_SINE",
      "EASE_OUT_SINE",
      "EASE_IN_OUT_SINE",
      "EASE_IN_QUAD",
      "EASE_OUT_QUAD",
      "EASE_IN_OUT_QUAD",
      "EASE_IN_CUBIC",
      "EASE_OUT_CUBIC",
      "EASE_IN_OUT_CUBIC",
      "EASE_IN_EXPO",
      "EASE_OUT_EXPO",
      "EASE_IN_OUT_EXPO"
    )
  )

  val rotationSpeed by SliderSetting(
    name = "Rotation Speed",
    description = "How fast to rotate (degrees per tick)",
    defaultValue = 15.0,
    min = 1.0,
    max = 45.0
  )

  /**
   * Helper to get the selected EasingType
   */
  fun getEasingType(): EasingType {
    return EasingType.entries[rotationEasing]
  }

}


