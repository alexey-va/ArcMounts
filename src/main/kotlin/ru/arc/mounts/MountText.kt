package ru.arc.mounts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.arc.util.TextUtils

/** Stable command feedback owned by the mount plugin rather than the ARC implementation. */
internal object MountText {
    fun noPermissions(): Component =
        Component.text("[", NamedTextColor.GRAY)
            .append(Component.text("!", NamedTextColor.DARK_RED))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("У вас нет на это разрешения!", NamedTextColor.DARK_RED))
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)

    fun playerOnly(): Component = Component.text("Эта команда доступна только игрокам!", NamedTextColor.RED)

    fun mm(text: String, strip: Boolean = true): Component = TextUtils.mm(text, strip)
}
