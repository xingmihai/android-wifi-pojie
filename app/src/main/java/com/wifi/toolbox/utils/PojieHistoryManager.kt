package com.wifi.toolbox.utils

import android.content.Context
import androidx.room.*
import com.wifi.toolbox.structs.PojieHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Entity(tableName = "history_metadata")
data class HistoryEntity(
    @PrimaryKey val ssid: String,
    val progress: Int,
    val successfulPassword: String?,
    val lasttime: Long
)

@Entity(
    tableName = "password_items",
    foreignKeys = [ForeignKey(
        entity = HistoryEntity::class,
        parentColumns = ["ssid"],
        childColumns = ["historyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("historyId")]
)
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: String,
    val password: String
)

data class HistoryWithPasswords(
    @Embedded val history: HistoryEntity,
    @Relation(
        parentColumn = "ssid",
        entityColumn = "historyId"
    )
    val passwords: List<PasswordEntity>
)

@Dao
interface PojieDao {
    @Transaction
    @Query("SELECT * FROM history_metadata ORDER BY lasttime DESC")
    fun getAllHistoryFlow(): Flow<List<HistoryWithPasswords>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(history: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswords(passwords: List<PasswordEntity>)

    @Query("DELETE FROM password_items WHERE historyId = :ssid")
    suspend fun deleteOldPasswords(ssid: String)

    @Transaction
    suspend fun fullUpsert(item: PojieHistoryItem) {
        insertMetadata(HistoryEntity(item.ssid, item.progress, item.password, item.lasttime))
        deleteOldPasswords(item.ssid)
        val entities = item.passwords.map { PasswordEntity(historyId = item.ssid, password = it) }
        insertPasswords(entities)
    }

    @Query("UPDATE history_metadata SET progress = :progress, lasttime = :time WHERE ssid = :ssid")
    suspend fun updateProgress(ssid: String, progress: Int, time: Long)

    @Query("DELETE FROM history_metadata WHERE ssid = :ssid")
    suspend fun deleteHistory(ssid: String)
}

// --- Database ---

@Database(entities = [HistoryEntity::class, PasswordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pojieDao(): PojieDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pojie_history.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- Manager ---

class PojieHistoryManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).pojieDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 说明：不再使用 SharingStarted.Eagerly。
     * 破解过程中进度会高频变更，Room 每次变更都会重跑一次「全表 + 密码表 JOIN」并重建几千个字符串对象，
     * 即使没有任何界面在观察也会持续空转。改为无订阅者 5 秒后停止收集。
     */
    val historyFlow: StateFlow<List<PojieHistoryItem>> = dao.getAllHistoryFlow()
        .map { list ->
            list.map {
                PojieHistoryItem(
                    it.history.ssid,
                    it.passwords.map { p -> p.password },
                    it.history.progress,
                    it.history.successfulPassword,
                    it.history.lasttime
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 全量写入（含整个密码列表）。
     * 只在「任务开始」和「破解成功」时调用 —— 这两个时刻各任务生命周期内最多各一次。
     */
    fun addOrUpdateHistory(item: PojieHistoryItem) {
        scope.launch {
            val itemWithTime = item.copy(lasttime = System.currentTimeMillis())
            dao.fullUpsert(itemWithTime)
        }
    }

    /**
     * 仅更新进度：单条 UPDATE，不触碰 password_items 表。
     *
     * 性能说明：原实现每尝试一个密码都走 fullUpsert（DELETE 全部密码行 + 重新 INSERT 全部密码行）。
     * 一个 2493 条的字典意味着每尝试一个密码就要做约 5000 行的写事务，
     * 这会占满 IO 线程池、拖慢真正的连接尝试，并且让 historyFlow 反复重算。
     * 进度变化只需要改 history_metadata 一行即可。
     */
    fun updateProgressOnly(ssid: String, progress: Int) {
        scope.launch {
            dao.updateProgress(ssid, progress, System.currentTimeMillis())
        }
    }

    fun deleteHistory(ssid: String) {
        scope.launch {
            dao.deleteHistory(ssid)
        }
    }
}