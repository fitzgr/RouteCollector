package com.grant.routecollector.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DriveEntity::class, TrackPointEntity::class, MarkerEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): RouteDao

    companion object {
        // SQLite cannot change an existing foreign key in place, so copy the
        // current markers to a table with a nullable trip association.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `markers_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `driveId` INTEGER,
                        `timestamp` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `kind` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        FOREIGN KEY(`driveId`) REFERENCES `drives`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `markers_new` (`id`, `driveId`, `timestamp`, `latitude`, `longitude`, `kind`, `note`) " +
                        "SELECT `id`, `driveId`, `timestamp`, `latitude`, `longitude`, `kind`, `note` FROM `markers`"
                )
                db.execSQL("DROP TABLE `markers`")
                db.execSQL("ALTER TABLE `markers_new` RENAME TO `markers`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_markers_driveId` ON `markers` (`driveId`)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "route_collector.db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}
