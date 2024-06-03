package ru.lezchook.gestureapplocker.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import ru.lezchook.gestureapplocker.model.AppModel
import ru.lezchook.gestureapplocker.model.BlockModel

@Dao
interface AppDao {

    @Insert(onConflict = REPLACE)
    fun insertAppModel(app: AppModel)

    @Query("SELECT * FROM apps")
    fun getAllApps(): List<AppModel>

    @Query("UPDATE apps SET status = :status WHERE id = :id")
    fun updateAppModel(id: Int, status: Int)

    @Query("SELECT * FROM apps WHERE status = 1")
    fun  getAllLockedApp(): List<AppModel>

    @Query("SELECT * FROM block WHERE id = 0")
    fun getBlockInfo(): List<BlockModel>

    @Insert(onConflict = REPLACE)
    fun insertBlockInfo(blockModel: BlockModel)

    @Query("DELETE FROM apps")
    fun deleteAppList()
}