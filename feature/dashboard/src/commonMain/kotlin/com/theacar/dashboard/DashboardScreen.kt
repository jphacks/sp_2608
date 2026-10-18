package com.theacar.dashboard

enum class DashboardScreen {
    RECORD, SETTINGS;

    companion object {
        fun of(name: String?): DashboardScreen = entries.firstOrNull { it.name == name } ?: RECORD
    }
}
