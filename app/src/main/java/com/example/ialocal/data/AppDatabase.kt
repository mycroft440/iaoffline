package com.example.ialocal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        AiModelEntity::class,
        AgentEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun modelDao(): ModelDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN agentId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_models (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        apiModelId TEXT NOT NULL,
                        format TEXT NOT NULL,
                        architecture TEXT,
                        filePath TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        importedAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        contextLength INTEGER NOT NULL,
                        sizeLabel TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_models_apiModelId ON ai_models(apiModelId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agents (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL,
                        temperature REAL NOT NULL,
                        maxTokens INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(modelId) REFERENCES ai_models(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agents_modelId ON agents(modelId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_models ADD COLUMN ggufVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN tensorCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN declaredContextLength INTEGER")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN verificationStatus TEXT NOT NULL DEFAULT 'IMPORTED'")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN lastVerifiedAt INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN extractedText TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_models ADD COLUMN calibratedContextLength INTEGER")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN contextCalibrationStatus TEXT NOT NULL DEFAULT 'NOT_CALIBRATED'")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN contextCalibrationKey TEXT")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN contextCalibrationUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN lastFailedContextLength INTEGER")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN lastContextExitReason TEXT")
                db.execSQL("ALTER TABLE ai_models ADD COLUMN contextCalibrationError TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "ia-local.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
