package ru.lezchook.gestureapplocker.database

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.lezchook.gestureapplocker.model.AppModel
import ru.lezchook.gestureapplocker.model.BlockModel

@Database(
    version = 2,
    entities = [AppModel::class, BlockModel::class],
    exportSchema = false
)
abstract class AppDB : RoomDatabase() {

    abstract fun getAppDao(): AppDao
}