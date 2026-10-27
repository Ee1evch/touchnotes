package app.nastavnik.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subject: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** "user" или "assistant". */
    val role: String,
    val text: String,
    @ColumnInfo(name = "had_image") val hadImage: Boolean = false,
    /** Сообщение об ошибке — показываем, но не отправляем в историю для модели. */
    @ColumnInfo(name = "is_error") val isError: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "clean_notes")
data class CleanNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long?,
    val subject: String,
    val title: String,
    /** JSON чистовика — можно перерисовать без запроса к API. */
    val json: String,
    /** Локальная копия PDF во внутренней папке приложения (её отдаём через FileProvider). */
    @ColumnInfo(name = "pdf_path") val pdfPath: String?,
    /** Пути PNG-страниц через перевод строки. */
    @ColumnInfo(name = "png_paths") val pngPaths: String?,
    /** Куда файл сохранён в общих «Документах» (для показа пользователю). */
    @ColumnInfo(name = "public_path") val publicPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    val subject: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(c: Conversation): Long

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): Conversation?

    @Query("UPDATE conversations SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT c.id, c.title, c.subject, c.updated_at, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count " +
            "FROM conversations c ORDER BY c.updated_at DESC",
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(m: Message): Long

    @Query("UPDATE messages SET text = :text, is_error = :isError WHERE id = :id")
    suspend fun update(id: Long, text: String, isError: Boolean)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY id")
    suspend fun forConversation(conversationId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY id")
    fun observe(conversationId: Long): Flow<List<Message>>
}

@Dao
interface CleanNoteDao {
    @Insert
    suspend fun insert(n: CleanNoteEntity): Long

    @Query("SELECT * FROM clean_notes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CleanNoteEntity>>

    @Query("SELECT * FROM clean_notes WHERE id = :id")
    suspend fun get(id: Long): CleanNoteEntity?

    @Query("DELETE FROM clean_notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [Conversation::class, Message::class, CleanNoteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun cleanNotes(): CleanNoteDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nastavnik.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
