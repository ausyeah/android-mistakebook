package com.mistakebook.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.data.local.entities.Notebook
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.QuestionTagCrossRef
import com.mistakebook.data.local.entities.ReviewLog
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.local.entities.Tag

@Database(
    entities = [
        Subject::class,
        Question::class,
        Tag::class,
        QuestionTagCrossRef::class,
        ReviewLog::class,
        CaptureTask::class,
        Notebook::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MistakeBookDatabase : RoomDatabase() {

    abstract fun subjectDao(): SubjectDao

    abstract fun questionDao(): QuestionDao

    abstract fun tagDao(): TagDao

    abstract fun reviewLogDao(): ReviewLogDao

    abstract fun captureTaskDao(): CaptureTaskDao

    abstract fun notebookDao(): NotebookDao

    companion object {
        const val DB_NAME = "mistake_book.db"

        /**
         * 1 → 2：新增错题本。
         *
         * **必须写 Migration，不能只加字段不改版本号。**
         * Room 开库时会核对 schema 指纹：版本号不变但表结构变了，
         * 老用户一打开就抛 IllegalStateException 直接闪退。
         * 也不能开 fallbackToDestructiveMigration——那会静默清空用户攒下的错题。
         *
         * 迁移内容与 [Notebook] / [Question.notebookId] 的定义严格对应：
         * 建表 + 建索引 + 加列。老题目的 notebookId 留 NULL，
         * 由 [com.mistakebook.data.repos.NotebookRepository.seedDefault]
         * 启动时统一归入默认错题本。
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notebooks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notebooks_sortOrder` ON `notebooks` (`sortOrder`)"
                )
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `notebookId` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_notebookId` ON `questions` (`notebookId`)"
                )
            }
        }

        fun build(context: Context): MistakeBookDatabase =
            Room.databaseBuilder(context, MistakeBookDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
