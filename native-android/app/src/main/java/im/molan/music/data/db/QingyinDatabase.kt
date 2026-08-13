package im.molan.music.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "lyrics")
data class LyricEntity(
    @PrimaryKey val trackKey: String,
    val source: String,
    val lyric: String,
    val translation: String,
    val missing: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "artworks")
data class ArtworkEntity(
    @PrimaryKey val url: String,
    val filePath: String,
    val updatedAt: Long,
)

@Dao
interface LyricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricEntity)

    @Query("SELECT * FROM lyrics WHERE trackKey = :trackKey")
    suspend fun byKey(trackKey: String): LyricEntity?

    @Query("DELETE FROM lyrics WHERE trackKey = :trackKey")
    suspend fun delete(trackKey: String)

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun count(): Long
}

@Dao
interface ArtworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtworkEntity)

    @Query("SELECT * FROM artworks WHERE url = :url")
    suspend fun byUrl(url: String): ArtworkEntity?

    @Query("DELETE FROM artworks WHERE url = :url")
    suspend fun delete(url: String)

    @Query("SELECT COUNT(*) FROM artworks")
    suspend fun count(): Long
}

@Database(
    entities = [LyricEntity::class, ArtworkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class QingyinDatabase : RoomDatabase() {
    abstract fun lyricDao(): LyricDao
    abstract fun artworkDao(): ArtworkDao

    companion object {
        @Volatile
        private var instance: QingyinDatabase? = null

        fun getInstance(context: Context): QingyinDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                QingyinDatabase::class.java,
                "qingyin.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}