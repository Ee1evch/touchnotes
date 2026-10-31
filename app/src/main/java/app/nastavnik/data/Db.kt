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
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Карточка интервального повторения (из чистовика или встроенного примера). */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Из какого чистовика; null — пример или чистовик уже удалён. */
    @ColumnInfo(name = "note_id") val noteId: Long?,
    val subject: String,
    val topic: String,
    val front: String,
    val back: String,
    /** Коробка Лейтнера: 0 — новая или забытая, 5 — давно выученная. */
    val box: Int = 0,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val reviews: Int = 0,
    val lapses: Int = 0,
)

/** Учебное событие для «Прогресса»: ответ наставника, тихая проверка, чистовик, повторённая карточка. */
@Entity(tableName = "activity")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val topic: String,
    /** Сколько ошибок нашлось (для карточки — 1, если её забыли). */
    val errors: Int,
    val at: Long,
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

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun all(): List<Conversation>
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

    /** Для поиска: все ответы и вопросы без ошибок, свежие первыми. */
    @Query("SELECT * FROM messages WHERE is_error = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Message>
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

@Dao
interface CardDao {
    @Insert
    suspend fun insertAll(cards: List<CardEntity>)

    @Update
    suspend fun update(card: CardEntity)

    @Query("SELECT * FROM cards WHERE due_at <= :now ORDER BY box, due_at LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE due_at <= :now")
    fun observeDueCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE box >= :box")
    fun observeLearned(box: Int): Flow<Int>

    @Query("SELECT MIN(due_at) FROM cards WHERE due_at > :now")
    suspend fun nextDue(now: Long): Long?

    @Query("SELECT COUNT(*) FROM cards WHERE note_id = :noteId")
    fun observeForNote(noteId: Long): Flow<Int>

    @Query("DELETE FROM cards WHERE note_id = :noteId")
    suspend fun deleteForNote(noteId: Long)

    @Query("SELECT * FROM cards ORDER BY created_at DESC")
    suspend fun all(): List<CardEntity>

    @Query("DELETE FROM cards WHERE note_id IS NULL AND topic = :topic")
    suspend fun deleteLoose(topic: String)
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(a: ActivityEntity)

    @Query("SELECT * FROM activity ORDER BY at")
    fun observeAll(): Flow<List<ActivityEntity>>
}

@Database(
    entities = [Conversation::class, Message::class, CleanNoteEntity::class, CardEntity::class, ActivityEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun cleanNotes(): CleanNoteDao
    abstract fun cards(): CardDao
    abstract fun activity(): ActivityDao

    companion object {
        /** Карточки и учебные события. История задач и чистовики при обновлении сохраняются. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `note_id` INTEGER, " +
                        "`subject` TEXT NOT NULL, `topic` TEXT NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, " +
                        "`box` INTEGER NOT NULL, `due_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`reviews` INTEGER NOT NULL, `lapses` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `topic` TEXT NOT NULL, `errors` INTEGER NOT NULL, `at` INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        const val DB_NAME = "nastavnik.db"
    }
}
