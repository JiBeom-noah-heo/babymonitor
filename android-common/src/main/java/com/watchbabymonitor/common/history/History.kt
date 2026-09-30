package com.watchbabymonitor.common.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 소음 이벤트 한 건 (CLAUDE.md Phase 6 "소음 이벤트 히스토리").
 * 감지기는 자기가 보낸 알림을, 수신기는 받은 알림을 기록한다. 오디오는 저장하지 않는다.
 *
 * @property role 기록한 기기의 역할 (SENSOR / RECEIVER)
 * @property delivered 감지기: 알림을 받은 수신기 수 (0 = 전달 실패). 수신기: null
 * @property peerName 상대 기기 이름 (아는 경우)
 */
@Entity(tableName = "noise_events")
data class NoiseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: Float,
    val role: String,
    val preset: String?,
    val delivered: Int?,
    val peerName: String?,
)

@Dao
interface NoiseEventDao {
    @Insert
    suspend fun insert(event: NoiseEvent)

    @Query("SELECT * FROM noise_events ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NoiseEvent>>

    @Query("DELETE FROM noise_events WHERE ts < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM noise_events")
    suspend fun clear()
}

@Database(entities = [NoiseEvent::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun events(): NoiseEventDao

    companion object {
        @Volatile
        private var instance: HistoryDb? = null

        fun get(context: Context): HistoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDb::class.java, "history.db")
                .build()
                .also { instance = it }
        }
    }
}
