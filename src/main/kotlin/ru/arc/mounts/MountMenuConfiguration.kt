package ru.arc.mounts

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuElementKind
import ru.arc.menu.MenuElementLayout
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuRegionLayout
import ru.arc.menu.MenuSlot
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Path

/** Mounts-owned projection of the operator's shared GUI file. The filtered view is never saved. */
internal object MountMenuConfiguration {
    const val RESOURCE = "guis/menus.yml"
    private const val LAYOUT_ROOT = "menus.layouts"
    private const val TEMPLATE_ROOT = "menus.templates"

    private val list = MenuId.of("mount-list")
    private val detail = MenuId.of("mount-detail")
    private val progression = MenuId.of("mount-progression")
    private val tuning = MenuId.of("mount-tuning")
    private val skins = MenuId.of("mount-skins")
    private val confirm = MenuId.of("mount-confirm")
    private val mountsRegion = MenuRegionId.of("mounts")
    private val levelsRegion = MenuRegionId.of("levels")
    private val speedsRegion = MenuRegionId.of("speeds")
    private val stepsRegion = MenuRegionId.of("steps")
    private val sizesRegion = MenuRegionId.of("sizes")
    private val abilitiesRegion = MenuRegionId.of("abilities")
    private val skinsRegion = MenuRegionId.of("skins")

    private val contracts = linkedMapOf(
        list to MenuContract(requiredElements = elements("previous", "filter", "next"), requiredRegions = setOf(mountsRegion)),
        detail to MenuContract(
            requiredElements = elements("icon", "favorite", "upgrade", "summon", "settings", "back", "whistle"),
        ),
        progression to MenuContract(requiredElements = elements("info", "back"), requiredRegions = setOf(levelsRegion)),
        tuning to MenuContract(
            requiredElements = elements("info", "back", "rider-view", "skins", "glow"),
            requiredRegions = setOf(speedsRegion, stepsRegion, sizesRegion, abilitiesRegion),
        ),
        skins to MenuContract(requiredElements = elements("previous", "next", "back"), requiredRegions = setOf(skinsRegion)),
        confirm to MenuContract(requiredElements = elements("cancel", "info", "accept")),
    )

    data class Prepared(val config: Config, val referencedTemplates: Set<String>)

    fun load(dataRoot: Path, transferSlot: Int): PaperMenuConfiguration = parse(prepare(dataRoot), transferSlot)

    /** File access and immutable projection; does not resolve native Material registry values. */
    fun prepare(dataRoot: Path): Prepared {
        val persistentConfig = ConfigManager.of(dataRoot, RESOURCE)
        persistentConfig.mergeMissingFromBundled(RESOURCE)
        migrateMountSettings(persistentConfig)

        // This is an in-memory projection of the shared host file. Never filter the
        // ConfigManager-owned instance: later reload/merge/save calls must retain
        // layouts and templates owned by ARC or server operators.
        val config = Config(dataRoot, RESOURCE)

        config.keys(LAYOUT_ROOT).filterNot(contracts.keys.mapTo(hashSetOf()) { it.value }::contains)
            .forEach { config.removeKey("$LAYOUT_ROOT.$it") }
        val referencedTemplates = config.keys(LAYOUT_ROOT).flatMapTo(linkedSetOf()) { rawMenu ->
            val path = "$LAYOUT_ROOT.$rawMenu"
            buildList {
                config.stringOrNull("$path.background.template")?.let(::add)
                config.keys("$path.elements").forEach { element ->
                    config.stringOrNull("$path.elements.$element.template")?.let(::add)
                }
            }
        }
        config.keys(TEMPLATE_ROOT).filterNot(referencedTemplates::contains)
            .forEach { config.removeKey("$TEMPLATE_ROOT.$it") }

        return Prepared(config, referencedTemplates)
    }

    /** Resolves the small native item-template catalogue on the server thread. */
    fun parse(prepared: Prepared, transferSlot: Int): PaperMenuConfiguration {
        val parsed = PaperMenuConfigurationParser.require(
            prepared.config,
            LAYOUT_ROOT,
            TEMPLATE_ROOT,
            contracts,
            requiredTemplates = prepared.referencedTemplates,
            textContracts = mapOf("background" to PaperMenuTextContract()),
        )
        return parsed.withMountRuntimeLayouts(transferSlot)
    }

    fun listLayoutId(rows: Int, empty: Boolean): MenuId =
        MenuId.of(
            when {
                empty -> "mount-list-empty-$rows"
                rows == 6 -> "mount-list"
                else -> "mount-list-$rows"
            },
        )

    private fun PaperMenuConfiguration.withMountRuntimeLayouts(transferSlot: Int): PaperMenuConfiguration {
        val layouts = catalog.layouts.toMutableMap()
        val detailLayout = layouts.getValue(detail)
        require(transferSlot in 0 until detailLayout.rows * 9)
        require(transferSlot !in detailLayout.elements.values.flatMap { it.slots }.map(MenuSlot::index)) {
            "Mount transfer button slot collides with a configured detail element"
        }
        layouts[detail] = detailLayout.copy(
            elements = detailLayout.elements + (MenuElementId.of("transfer") to
                MenuElementLayout.button(MenuElementId.of("transfer"), MenuSlot.of(transferSlot))),
        )

        val baseList = layouts.getValue(list)
        val listContent = baseList.region(mountsRegion)
        require(listContent.isNotEmpty()) { "Mount list region cannot be empty" }
        (2..6).forEach { rows ->
            val id = if (rows == 6) list else MenuId.of("mount-list-$rows")
            val bodySlots = listContent.filter { it.index < (rows - 1) * 9 }
            require(bodySlots.isNotEmpty()) { "Mount list has no content slots for a $rows-row layout" }
            val controls = remapControls(baseList, rows)
            val regular = baseList.copy(
                id = id,
                rows = rows,
                elements = withFooterDecorations(controls.first, rows, controls.second),
                regions = baseList.regions + (mountsRegion to MenuRegionLayout(mountsRegion, bodySlots)),
            )
            layouts[id] = regular
            layouts[MenuId.of("mount-list-empty-$rows")] = emptyListLayout(baseList, rows, controls)
        }

        val baseSkins = layouts.getValue(skins)
        val skinControls = setOf("previous", "back", "next").associateWith { baseSkins.slot(it).index }
        layouts[skins] = baseSkins.copy(
            elements = withFooterDecorations(baseSkins.elements, baseSkins.rows, skinControls.values.toSet()),
        )

        return copy(catalog = MenuCatalog(layouts = layouts))
    }

    private fun emptyListLayout(
        base: MenuLayout,
        rows: Int,
        controls: Pair<Map<MenuElementId, MenuElementLayout>, Set<Int>>,
    ): MenuLayout {
        val bodyRows = rows - 1
        val bodySlots = base.region(mountsRegion).filter { it.index < bodyRows * 9 }
        val infoSlot = bodySlots.minBy { slot ->
            kotlin.math.abs(slot.row - (bodyRows - 1) / 2) * 9 + kotlin.math.abs(slot.index % 9 - 4)
        }
        val infoId = MenuElementId.of("info")
        return MenuLayout(
            id = MenuId.of("mount-list-empty-$rows"),
            rows = rows,
            elements = withFooterDecorations(
                controls.first + (infoId to MenuElementLayout(infoId, listOf(infoSlot), MenuElementKind.DECORATION)),
                rows,
                controls.second,
            ),
        )
    }

    private fun remapControls(base: MenuLayout, rows: Int): Pair<Map<MenuElementId, MenuElementLayout>, Set<Int>> {
        val controlIds = listOf("previous", "filter", "next").map(MenuElementId::of)
        val elements = controlIds.associate { id ->
            val original = base.elements.getValue(id)
            val slot = original.slots.single()
            val remapped = MenuSlot.of((rows - 1) * 9 + slot.index % 9)
            id to original.copy(slots = listOf(remapped))
        }
        return elements to elements.values.flatMapTo(linkedSetOf()) { element -> element.slots.map(MenuSlot::index) }
    }

    private fun withFooterDecorations(
        existing: Map<MenuElementId, MenuElementLayout>,
        rows: Int,
        controlSlots: Set<Int>,
    ): Map<MenuElementId, MenuElementLayout> = existing + ((rows - 1) * 9 until rows * 9)
        .filterNot(controlSlots::contains)
        .associate { slot ->
            val id = MenuElementId.of("footer-$slot")
            id to MenuElementLayout(id, listOf(MenuSlot.of(slot)), MenuElementKind.DECORATION)
        }

    private fun migrateMountSettings(config: Config) {
        val detailPath = "$LAYOUT_ROOT.mount-detail"
        val oldGlowSlot = config.intOrNull("$detailPath.elements.glow.slot") ?: return
        config.mergeMissingFromBundled(RESOURCE)
        config.setInt("$detailPath.elements.settings.slot", oldGlowSlot)
        config.setString(
            "$detailPath.elements.settings.template",
            config.stringOrNull("$detailPath.elements.glow.template") ?: "background",
        )
        config.removeKey("$detailPath.elements.glow")
        config.removeKey("$detailPath.elements.skins")
        config.removeKey("$detailPath.regions.abilities")
        config.removeKey("$LAYOUT_ROOT.mount-progression.elements.tuning")
        val tuningPath = "$LAYOUT_ROOT.mount-tuning"
        if (config.intOrNull("$tuningPath.rows") == 5) config.setInt("$tuningPath.rows", 6)
        if (config.intOrNull("$tuningPath.elements.back.slot") == 36) config.setInt("$tuningPath.elements.back.slot", 45)
        if (config.intOrNull("$tuningPath.elements.rider-view.slot") == 40) {
            config.setInt("$tuningPath.elements.rider-view.slot", 49)
        }
        config.saveStrict()
    }

    private fun elements(vararg ids: String): Set<MenuElementId> = ids.mapTo(linkedSetOf(), MenuElementId::of)
}
