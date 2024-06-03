package ru.lezchook.gestureapplocker.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppModel(
    @PrimaryKey
    val id: Int,
    var appName: String,
    var appIcon: ByteArray,
    var packageName: String,
    var status: Int,
)