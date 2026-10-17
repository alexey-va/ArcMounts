package ru.arc.mounts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArcMountsPluginGenerationTest : FunSpec({
    test("startup is immediate and disable completes accepted IO before a direct re-enable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcMountsPlugin::class.java)
            plugin.starts shouldBe 1
            val write = CompletableFuture<Unit>()
            plugin.nextDrain = write
            val returned = AtomicBoolean()
            val writer = CompletableFuture.runAsync {
                try {
                    plugin.closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                    returned.get() shouldBe false
                } finally { write.complete(Unit) }
            }
            paper.server.pluginManager.disablePlugin(plugin)
            returned.set(true)
            writer.get(5, TimeUnit.SECONDS)
            write.isDone shouldBe true
            plugin.onDisable()
            plugin.closes shouldBe 1
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
            plugin.nextDrain = CompletableFuture.completedFuture(Unit)
        }
    }

    test("a failed storage drain is reported after native detach without scheduling a new generation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcMountsPlugin::class.java)
            plugin.nextDrain = CompletableFuture.failedFuture(IllegalStateException("storage close failed"))
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.closes shouldBe 1
            plugin.starts shouldBe 1
            paper.performTicks(2)
            plugin.starts shouldBe 1
        }
    }
})

open class ControlledArcMountsPlugin : ArcMountsPlugin() {
    var starts = 0
    var closes = 0
    var nextDrain = CompletableFuture.completedFuture(Unit)
    val closeEntered = CountDownLatch(1)
    override fun startGeneration() { starts++ }
    override fun closeGeneration(): CompletableFuture<Unit> {
        closes++
        closeEntered.countDown()
        return nextDrain
    }
}
