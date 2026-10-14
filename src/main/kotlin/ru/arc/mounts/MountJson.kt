package ru.arc.mounts

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** The mount records use only Gson's built-in scalar, enum, collection, and UUID adapters. */
internal object MountJson {
    val gson: Gson = GsonBuilder().create()
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()
}
