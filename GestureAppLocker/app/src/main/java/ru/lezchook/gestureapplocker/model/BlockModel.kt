package ru.lezchook.gestureapplocker.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block")
data class BlockModel(
    @PrimaryKey
    val id: Int,
    var tfliteModel: ByteArray,
    var blockedFlag: Int,
    var recoveryCode: String,
    var attemptCount: Int
)