package ru.arc.mounts

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify

class MountCarrierPosePacketsTest : StringSpec({
    "register and close are idempotent with PacketEvents listener lifecycle" {
        withPacketEvents { eventManager ->
            val listener = MountCarrierPosePackets()

            listener.start()
            listener.start()
            verify(exactly = 1) { eventManager.registerListener(listener) }

            listener.close()
            listener.close()
            verify(exactly = 1) { eventManager.unregisterListener(listener) }
        }
    }

    "carrier metadata is sent only by the after-send callback and uses SITTING pose" {
        withPacketEvents {
            val listener = MountCarrierPosePackets()
            val viewer = user()
            val tasks = mutableListOf<Runnable>()

            listener.register(101)
            listener.onPacketSend(setPassengersEvent(viewer, intArrayOf(101), tasks))

            tasks shouldHaveSize 1
            verify(exactly = 0) { viewer.sendPacket(any<WrapperPlayServerEntityMetadata>()) }

            tasks.single().run()

            val metadata = captureMetadata(viewer)
            metadata.entityId shouldBe 101
            metadata.entityMetadata shouldHaveSize 1
            val pose = metadata.entityMetadata.single()
            pose.index shouldBe 6
            pose.type shouldBe EntityDataTypes.ENTITY_POSE
            pose.value shouldBe EntityPose.SITTING
        }
    }

    "unrelated passengers do not schedule metadata" {
        withPacketEvents {
            val listener = MountCarrierPosePackets()
            val viewer = user()
            val tasks = mutableListOf<Runnable>()

            listener.register(101)
            listener.onPacketSend(setPassengersEvent(viewer, intArrayOf(202), tasks))

            tasks.shouldBeEmpty()
            verify(exactly = 0) { viewer.sendPacket(any<WrapperPlayServerEntityMetadata>()) }
        }
    }

    "unregister before callback suppresses a stale graph replay and new viewers still receive later replays" {
        withPacketEvents {
            val listener = MountCarrierPosePackets()
            val firstViewer = user()
            val firstTasks = mutableListOf<Runnable>()

            listener.register(101)
            listener.onPacketSend(setPassengersEvent(firstViewer, intArrayOf(101), firstTasks))
            firstTasks shouldHaveSize 1
            listener.unregister(101)
            firstTasks.single().run()
            verify(exactly = 0) { firstViewer.sendPacket(any<WrapperPlayServerEntityMetadata>()) }

            listener.register(101)
            val secondViewer = user()
            val secondTasks = mutableListOf<Runnable>()
            listener.onPacketSend(setPassengersEvent(secondViewer, intArrayOf(101), secondTasks))
            secondTasks shouldHaveSize 1
            secondTasks.single().run()

            captureMetadata(secondViewer).entityId shouldBe 101
        }
    }
})

private fun withPacketEvents(block: (EventManager) -> Unit) {
    val eventManager = mockk<EventManager>(relaxed = true)
    val serverManager = mockk<ServerManager> {
        every { version } returns ServerVersion.V_1_21_11
    }
    val api = mockk<PacketEventsAPI<Any>>(relaxed = true) {
        every { this@mockk.eventManager } returns eventManager
        every { this@mockk.serverManager } returns serverManager
        every { settings } returns PacketEventsSettings()
    }
    mockkStatic(PacketEvents::class)
    every { PacketEvents.getAPI() } returns api
    try {
        block(eventManager)
    } finally {
        unmockkStatic(PacketEvents::class)
    }
}

private fun user(): User = mockk(relaxed = true) {
    every { clientVersion } returns ClientVersion.V_1_21_11
}

private fun setPassengersEvent(viewer: User, passengerIds: IntArray, tasks: MutableList<Runnable>): PacketSendEvent {
    val packet = mockk<WrapperPlayServerSetPassengers> {
        every { passengers } returns passengerIds
    }
    return mockk(relaxed = true) {
        every { isCancelled } returns false
        every { packetType } returns PacketType.Play.Server.SET_PASSENGERS
        every { user } returns viewer
        every { serverVersion } returns ServerVersion.V_1_21_11
        every { lastUsedWrapper } returns packet
        every { tasksAfterSend } returns tasks
    }
}

private fun captureMetadata(viewer: User): WrapperPlayServerEntityMetadata {
    val metadata = slot<WrapperPlayServerEntityMetadata>()
    verify(exactly = 1) { viewer.sendPacket(capture(metadata)) }
    return metadata.captured
}
