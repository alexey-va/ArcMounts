package ru.arc.mounts

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import java.util.concurrent.ConcurrentHashMap

/** Keeps the native client seat dimensions consistent with the server's fixed sitting pose. */
internal class MountCarrierPosePackets : PacketListenerAbstract(PacketListenerPriority.MONITOR), AutoCloseable {
    private val carriers = ConcurrentHashMap.newKeySet<Int>()
    private var started = false

    fun start() {
        if (started) return
        PacketEvents.getAPI().eventManager.registerListener(this)
        started = true
    }

    fun register(entityId: Int) { carriers.add(entityId) }

    fun unregister(entityId: Int) { carriers.remove(entityId) }

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled || event.packetType != PacketType.Play.Server.SET_PASSENGERS || carriers.isEmpty()) return
        val seats = WrapperPlayServerSetPassengers(event).passengers.filter { it in carriers }
        if (seats.isEmpty()) return
        val viewer = event.user
        // Vanilla startRiding resets Pose to STANDING, including during pairing
        // and graph replays. The server pose is unchanged, so no dirty metadata
        // follows it automatically. Restore the pose after this exact packet.
        event.tasksAfterSend.add(Runnable {
            seats.filter { it in carriers }.forEach { entityId ->
                viewer.sendPacket(WrapperPlayServerEntityMetadata(
                    entityId,
                    listOf(EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.SITTING)),
                ))
            }
        })
    }

    override fun close() {
        carriers.clear()
        if (started) PacketEvents.getAPI().eventManager.unregisterListener(this)
        started = false
    }
}
