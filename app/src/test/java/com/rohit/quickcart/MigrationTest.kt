package com.rohit.quickcart

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.rohit.quickcart.data.local.MIGRATION_1_2
import com.rohit.quickcart.data.local.QuickCartDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creates a real version-1 database from the exported schema (app/schemas/.../1.json), fills it
 * with data, runs MIGRATION_1_2, and lets Room check that the result exactly matches version 2.
 * Proves an app update keeps the user's cart and order history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), QuickCartDatabase::class.java)

    @Test
    fun migrate1To2_keepsCartAndOrders() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO products VALUES ('milk','Toned Milk','dairy','500 ml',2800,2800,'🥛',1)")
            execSQL("INSERT INTO cart_items VALUES ('milk', 3, 1000)")
            execSQL(
                "INSERT INTO orders VALUES ('o1','QC1','DELIVERED',5600,2500,400,2,'Sector 62','UPI',0,1.0,1000,1000)",
            )
            close()
        }

        // validateDroppedTables + Room's schema check: throws if the migrated DB differs from v2 in any way.
        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        db.query("SELECT quantity FROM cart_items WHERE productId = 'milk'").use {
            it.moveToFirst(); assertEquals(3, it.getInt(0))
        }
        db.query("SELECT description FROM products WHERE id = 'milk'").use {
            it.moveToFirst(); assertEquals("", it.getString(0))
        }
        db.query("SELECT tipPaise, address FROM orders WHERE localId = 'o1'").use {
            it.moveToFirst(); assertEquals(0L, it.getLong(0)); assertEquals("Sector 62", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM recent_searches").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
