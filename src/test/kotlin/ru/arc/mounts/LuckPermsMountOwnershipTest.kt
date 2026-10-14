package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryMode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class LuckPermsMountOwnershipTest : StringSpec({
    "Bukkit OP does not manufacture mount ownership and effective LP grants still work" {
        val id = UUID.randomUUID()
        val lp = mockk<LuckPerms>()
        val user = mockk<User>()
        val contextManager = mockk<ContextManager>()
        val queryBuilder = mockk<QueryOptions.Builder>()
        val queryOptions = mockk<QueryOptions>()
        val context = mockk<ImmutableContextSet>()
        val cachedData = mockk<CachedDataManager>()
        val defaultPermissions = mockk<CachedPermissionData>()
        val permissions = mockk<CachedPermissionData>()
        every { lp.userManager.getUser(id) } returns user
        every { lp.contextManager } returns contextManager
        every { contextManager.queryOptionsBuilder(QueryMode.CONTEXTUAL) } returns queryBuilder
        every { contextManager.getContext(user) } returns java.util.Optional.of(context)
        every { contextManager.staticContext } returns context
        every { queryBuilder.context(context) } returns queryBuilder
        every { queryBuilder.build() } returns queryOptions
        every { user.cachedData } returns cachedData
        // This is the Bukkit/OP-shaped default calculator that caused the
        // production regression: it reports every permission to an operator.
        every { cachedData.permissionData } returns defaultPermissions
        every { defaultPermissions.checkPermission(any()) } returns Tristate.TRUE
        every { cachedData.getPermissionData(queryOptions) } returns permissions
        every { user.nodes } returns emptySet()
        every { permissions.checkPermission(any()) } returns Tristate.UNDEFINED
        val mount = testMount()
        val ownership = LuckPermsMountOwnership(lp)
        val operator = MountPermissionSubject(id, "Operator") { true }

        ownership.profile(operator, mount).level shouldBe 0
        verify(exactly = 0) { defaultPermissions.checkPermission(any()) }

        // LP resolves active-context group and direct grants through this cache.
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.TRUE
        ownership.profile(operator, mount).level shouldBe 2
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.FALSE
        ownership.profile(operator, mount).level shouldBe 0
    }

    "grant-all adds every catalog entitlement once per batch and preserves player selections" {
        mockkStatic(PermissionNode::class)
        try {
            val playerId = UUID.randomUUID()
            val catalog = bundledMountCatalog()
            val happyGhast = checkNotNull(catalog["happy_ghast"])
            val happySizes = happyGhast.sizeOptions.filter(MountSizeOptionDefinition::grantOnly)
            happySizes.map(MountSizeOptionDefinition::id).toSet() shouldBe setOf("keychain", "colossal")

            val expected = catalog.all.flatMap { mount ->
                buildList {
                    add(mount.levelPermission(mount.maxLevel))
                    add(mount.glowPermission)
                    mount.skins.forEach { add(mount.skinPermission(it.id)) }
                    mount.abilities.upgrades.forEach { add(mount.abilityPermission(it.id)) }
                    mount.sizeOptions.filter(MountSizeOptionDefinition::grantOnly)
                        .forEach { add(mount.sizeOwnershipPermission(it.id)) }
                }
            }.toSet()
            catalog.all.sumOf { it.skins.size } shouldBe 793
            expected.size shouldBe 1114
            val selectedAndUnrelated = setOf(
                happyGhast.glowDisabledPermission,
                happyGhast.activeSkinPermission("starlight"),
                happyGhast.speedTuningPermission(80),
                happyGhast.sizeTuningPermission("standard"),
                favoriteMountPermission(happyGhast.id),
                "unrelated.permission",
            )
            val stored = selectedAndUnrelated.associateWith(::permissionNode).toMutableMap()
            val addedPermissions = mutableListOf<String>()

            val lp = mockk<LuckPerms>()
            val userManager = mockk<UserManager>()
            val user = mockk<User>()
            val nodeMap = mockk<NodeMap>()
            val builder = mockk<PermissionNode.Builder>()
            val permissionName = slot<String>()
            every { lp.userManager } returns userManager
            every { user.data() } returns nodeMap
            every { user.nodes } answers { stored.values.toSet() }
            every { PermissionNode.builder(capture(permissionName)) } returns builder
            every { builder.value(true) } returns builder
            every { builder.build() } answers { permissionNode(permissionName.captured) }
            every { nodeMap.add(any()) } answers {
                val node = firstArg<PermissionNode>()
                addedPermissions += node.permission
                if (stored.put(node.permission, node) == null) {
                    DataMutateResult.SUCCESS
                } else {
                    DataMutateResult.FAIL_ALREADY_HAS
                }
            }
            var modifyUserCalls = 0
            every { userManager.modifyUser(playerId, any<Consumer<in User>>()) } answers {
                modifyUserCalls++
                secondArg<Consumer<in User>>().accept(user)
                CompletableFuture.completedFuture(null)
            }

            val ownership = LuckPermsMountOwnership(lp)
            ownership.grantAll(playerId, catalog.all).join()
            modifyUserCalls shouldBe 1
            addedPermissions.toSet() shouldBe expected
            addedPermissions.size shouldBe expected.size
            stored.keys.toSet() shouldBe (selectedAndUnrelated + expected)
            setOf("arc.mounts.happy_ghast.size.keychain", "arc.mounts.happy_ghast.size.colossal")
                .all { it in stored } shouldBe true
            val ownedHappySizeIds = stored.keys.filter { it.startsWith(happyGhast.sizeOwnershipPermissionPrefix) }
                .map { it.removePrefix(happyGhast.sizeOwnershipPermissionPrefix) }.toSet()
            happyGhast.availableSizeOptions(happyGhast.maxLevel, ownedHappySizeIds) shouldBe happyGhast.sizeOptions

            val afterFirstGrant = stored.keys.toSet()
            ownership.grantAll(playerId, catalog.all).join()
            modifyUserCalls shouldBe 2
            addedPermissions.toSet() shouldBe expected
            addedPermissions.size shouldBe expected.size * 2
            stored.keys shouldBe afterFirstGrant
            selectedAndUnrelated.all { it in stored } shouldBe true
            verify(exactly = 2) { userManager.modifyUser(playerId, any<Consumer<in User>>()) }
            verify(exactly = 0) { nodeMap.remove(any<Node>()) }
        } finally {
            unmockkStatic(PermissionNode::class)
        }
    }
})

private fun bundledMountCatalog(): MountCatalog {
    val dataPath = Files.createTempDirectory("arc-mount-ownership-grant-all-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(LuckPermsMountOwnershipTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath).catalog()
}

private fun permissionNode(permission: String): PermissionNode =
    mockk {
        every { this@mockk.permission } returns permission
        every { value } returns true
    }
