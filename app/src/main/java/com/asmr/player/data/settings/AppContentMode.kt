package com.asmr.player.data.settings

enum class AppContentMode(val storageValue: String) {
    Asmr("asmr"),
    Podcast("podcast");

    companion object {
        fun fromStorageValue(value: String?): AppContentMode {
            return entries.firstOrNull { it.storageValue == value } ?: Asmr
        }
    }
}
