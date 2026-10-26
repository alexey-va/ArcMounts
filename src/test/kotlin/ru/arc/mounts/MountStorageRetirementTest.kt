package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.TestTaskScheduler
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.paper.menu.PaperMenuRuntime
import java.nio.file.Files
import java.util.concurrent.CompletionException

class MountStorageRetirementTest : FunSpec({
    test("a failed retired lease remains in the terminal drain while other leases close") {
        val worker = BoundedAsyncExecutor("mount-retirement-test", 1, 8)
        val path = Files.createTempDirectory("mount-retirement-test")
        val host = mockk<ArcMountConfiguration> { every { dataPath() } returns path }
        val module = MountModule(mockk(), host, TestTaskScheduler(), { null }, MountTechnicalMetrics(null),
            mockk(), mockk<PaperMenuRuntime>(), worker)
        var failedCloses = 0
        var otherCloses = 0
        val retired = module.retireStorage(AutoCloseable {
            failedCloses++
            error("retired lease close failed")
        })
        module.retireStorage(AutoCloseable { otherCloses++ })
        val terminal = worker.closeAsync { module.closeStorageResources() }
        shouldThrow<CompletionException> { retired.join() }.cause?.message shouldBe "retired lease close failed"
        shouldThrow<CompletionException> { terminal.join() }.cause?.message shouldBe "retired lease close failed"
        failedCloses shouldBe 1
        otherCloses shouldBe 1
        Files.delete(path)
    }
})
