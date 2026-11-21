package io.github.rmac0001.foursight.core.store

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.rmac0001.foursight.db.FourSightDatabase

object AndroidDatabase {
    fun open(context: Context): FourSightDatabase {
        val driver: SqlDriver =
            AndroidSqliteDriver(
                schema = FourSightDatabase.Schema,
                context = context,
                name = "foursight.db",
                callback =
                    object : AndroidSqliteDriver.Callback(FourSightDatabase.Schema) {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }
                    },
            )
        return FourSightDatabase(driver)
    }
}
