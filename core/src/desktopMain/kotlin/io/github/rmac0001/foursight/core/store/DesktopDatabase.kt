package io.github.rmac0001.foursight.core.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.rmac0001.foursight.db.FourSightDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object DesktopDatabase {
    /** Opens (creating or migrating as needed) the database file at [path]. */
    fun open(path: Path): FourSightDatabase {
        path.parent?.let(Files::createDirectories)
        val driver =
            JdbcSqliteDriver(
                url = "jdbc:sqlite:${path.toAbsolutePath()}",
                properties = Properties().apply { put("foreign_keys", "true") },
                schema = FourSightDatabase.Schema,
            )
        return FourSightDatabase(driver)
    }

    /** An in-memory database, for tests and developer builds only. */
    fun inMemory(): FourSightDatabase {
        val driver =
            JdbcSqliteDriver(
                url = JdbcSqliteDriver.IN_MEMORY,
                properties = Properties().apply { put("foreign_keys", "true") },
                schema = FourSightDatabase.Schema,
            )
        return FourSightDatabase(driver)
    }

    /**
     * Default location on Windows: %USERPROFILE%\FourSight\foursight.db (Roger's decision, November
     * 22, 2026). Not under AppData: Windows redirects a packaged app's AppData writes (the Claude
     * desktop app, which will start the MCP server) into a private copy that Task Scheduler and other
     * programs can't see, which would split FourSight into two databases. Elsewhere (developer
     * machines, CI) it falls back to ~/.foursight/foursight.db.
     */
    fun defaultPath(): Path {
        val profile = System.getenv("USERPROFILE")
        return if (profile != null && System.getProperty("os.name").startsWith("Windows")) {
            Path.of(profile, "FourSight", "foursight.db")
        } else {
            Path.of(System.getProperty("user.home"), ".foursight", "foursight.db")
        }
    }
}
