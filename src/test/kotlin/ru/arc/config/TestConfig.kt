package ru.arc.config

import java.nio.file.Files

/** Test-only Config backed by the real YAML implementation with injected seed values. */
class TestConfig(data: Map<String, Any> = emptyMap()) :
    Config(Files.createTempDirectory("arc-mounts-test-config-"), "test-config.yml") {
    init {
        data.forEach { (path, value) -> inject(path, value) }
    }

    operator fun set(path: String, value: Any) = inject(path, value)

    override fun reload() = Unit

    override fun save() = Unit

    private fun inject(path: String, value: Any) {
        when (value) {
            is Int -> setInt(path, value)
            is Long -> setLong(path, value)
            is Boolean -> setBoolean(path, value)
            is Double -> setDouble(path, value)
            is String -> setString(path, value)
            is List<*> -> setStringList(path, value.map { it.toString() })
            is Map<*, *> -> value.forEach { (key, nested) ->
                if (key != null && nested != null) inject("$path.$key", nested)
            }
            else -> setString(path, value.toString())
        }
    }
}
