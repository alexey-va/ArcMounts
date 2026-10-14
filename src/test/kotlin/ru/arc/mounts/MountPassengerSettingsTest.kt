package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.logging.Logger

class MountPassengerSettingsTest : StringSpec({
    "live edits replace geometry atomically and invalid edits preserve the last good seats" {
        val root = Files.createTempDirectory("mount-seats")
        try {
            val file = Files.createDirectories(root.resolve("modules")).resolve("mounts.yml")
            val settings = MountPassengerSettings(root, MountPassengerSeatSettings(0.8, 180.0, 90.0), Logger.getAnonymousLogger())
            Files.writeString(file, "passengers: {carrier-scale: 1.0, carrier-yaw-offset: 135.0}")
            settings.refresh()
            settings.forMount("ravager") shouldBe MountPassengerSeatSettings(1.0, 180.0, 135.0)
            Files.writeString(file, """
                passengers:
                  carrier-scale: 1.0
                  carrier-yaw-offset: 135.0
                  mounts:
                    polar_bear:
                      carrier-scale: 1.2
                      single-carrier-yaw-offset: 170.0
            """.trimIndent())
            settings.refresh()
            settings.forMount("polar_bear") shouldBe MountPassengerSeatSettings(1.2, 170.0, 135.0)
            settings.forMount("ravager") shouldBe MountPassengerSeatSettings(1.0, 180.0, 135.0)
            Files.writeString(file, "passengers: {carrier-scale: 99}")
            settings.refresh()
            settings.forMount("polar_bear") shouldBe MountPassengerSeatSettings(1.2, 170.0, 135.0)
            Files.writeString(file, "passengers: [broken")
            settings.refresh()
            settings.forMount("ravager") shouldBe MountPassengerSeatSettings(1.0, 180.0, 135.0)
            Files.writeString(file, "passengers: {carrier-scale: 0.9, single-carrier-yaw-offset: 175}")
            settings.refresh()
            settings.forMount("polar_bear") shouldBe MountPassengerSeatSettings(0.9, 175.0, 90.0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
