package com.rohit.quickcart.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        ProductEntity::class,
        CartItemEntity::class,
        OrderEntity::class,
        OrderItemEntity::class,
        RecentSearchEntity::class,
    ],
    version = 2,
    exportSchema = true, // schemas are saved in app/schemas/ and used by the migration test
)
abstract class QuickCartDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun cartDao(): CartDao
    abstract fun orderDao(): OrderDao
    abstract fun recentSearchDao(): RecentSearchDao
}

/**
 * Version 1 -> 2: product descriptions, delivery-partner tip, recent searches.
 *
 * A migration keeps the user's data (cart, order history). Without one, Room would either
 * crash on startup or, with fallbackToDestructiveMigration(), silently delete everything.
 * The test MigrationTest checks that the result matches exactly what Room expects for version 2.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `products` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `orders` ADD COLUMN `tipPaise` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recent_searches` " +
                "(`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))",
        )
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
