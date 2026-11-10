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
    /** Общий для всех устройств идентификатор (синхронизация). */
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
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
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
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
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
)

/** Напоминание: «сдать лабу», «купить тетрадь». Повтор — каждый день, по будням или раз в неделю. */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val uid: String = newUid(),
    val title: String,
    val note: String = "",
    /** Когда напомнить (мс); для повторяющихся — ближайший раз. */
    val at: Long,
    /** none, daily, weekdays, weekly. */
    val repeat: String = "none",
    val done: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")

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

    @Query("SELECT * FROM clean_notes")
    suspend fun all(): List<CleanNoteEntity>

    @Query("SELECT * FROM clean_notes WHERE uid = :uid")
    suspend fun byUid(uid: String): CleanNoteEntity?

    @Update
    suspend fun update(n: CleanNoteEntity)
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

    @Query("SELECT COUNT(*) FROM cards WHERE due_at <= :now")
    suspend fun dueCount(now: Long): Int

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

    @Query("SELECT * FROM cards WHERE uid = :uid")
    suspend fun byUid(uid: String): CardEntity?

    @Query("DELETE FROM cards WHERE uid = :uid")
    suspend fun deleteUid(uid: String)
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(a: ActivityEntity)

    @Query("SELECT * FROM activity WHERE kind NOT IN ('mistake_detail', 'mistake_reviewed') ORDER BY at")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT * FROM activity WHERE kind IN ('mistake_detail', 'mistake_reviewed') AND at >= :since ORDER BY at DESC")
    fun observeMistakes(since: Long): Flow<List<ActivityEntity>>

    @Query("SELECT COUNT(*) FROM activity WHERE uid = :uid")
    suspend fun countUid(uid: String): Int

    @Query("DELETE FROM activity WHERE uid = :uid")
    suspend fun deleteUid(uid: String)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY done, at")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE uid = :uid")
    suspend fun get(uid: String): ReminderEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(r: ReminderEntity)

    @Query("DELETE FROM reminders WHERE uid = :uid")
    suspend fun delete(uid: String)
}

@Database(
    entities = [Conversation::class, Message::class, CleanNoteEntity::class, CardEntity::class, ActivityEntity::class, ReminderEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun cleanNotes(): CleanNoteDao
    abstract fun cards(): CardDao
    abstract fun activity(): ActivityDao
    abstract fun reminders(): ReminderDao

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

        /** Общие идентификаторы для синхронизации и напоминания. Старые записи получают случайный uid. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("clean_notes", "cards", "activity")) {
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE `$t` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`uid` TEXT NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `repeat` TEXT NOT NULL, `done` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        const val DB_NAME = "nastavnik.db"
    }
}
