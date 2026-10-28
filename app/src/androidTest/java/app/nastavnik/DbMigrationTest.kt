package app.nastavnik

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nastavnik.data.AppDatabase
import app.nastavnik.data.CardEntity
import app.nastavnik.data.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Обновление с версии без карточек: задачи сохраняются, новые таблицы создаются как ждёт Room. */
@RunWith(AndroidJUnit4::class)
class DbMigrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migratesFromVersion1KeepingHistory() = runBlocking<Unit> {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()

        // Свежая база, затем «откатываем» её к версии 1: таблиц карточек и событий ещё нет.
        val fresh = open()
        val id = fresh.conversations().insert(Conversation(title = "Пределы", subject = "", createdAt = 1, updatedAt = 1))
        fresh.close()
        SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE cards")
            db.execSQL("DROP TABLE activity")
            db.version = 1
        }

        // Room проверяет схему после миграции и упадёт, если таблицы не совпадут с сущностями.
        val migrated = open()
        assertNotNull("история пропала", migrated.conversations().get(id))
        migrated.cards().insertAll(listOf(CardEntity(noteId = null, subject = "", topic = "Т", front = "В", back = "О", dueAt = 0, createdAt = 0)))
        assertEquals(1, migrated.cards().due(System.currentTimeMillis(), 10).size)
        migrated.close()
        ctx.deleteDatabase(name)
    }
}
