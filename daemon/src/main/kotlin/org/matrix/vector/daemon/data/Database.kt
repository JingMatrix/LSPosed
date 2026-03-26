package org.matrix.vector.daemon.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.matrix.vector.daemon.utils.FakeContext

private const val TAG = "VectorDatabase"
private const val DB_VERSION = 4

class Database(context: Context? = FakeContext()) :
    SQLiteOpenHelper(context, FileSystem.dbPath.absolutePath, null, DB_VERSION) {

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.setForeignKeyConstraintsEnabled(true)
    db.enableWriteAheadLogging()
    // Improve write performance
    db.execSQL("PRAGMA synchronous=NORMAL;")
  }

  override fun onCreate(db: SQLiteDatabase) {
    Log.i(TAG, "Creating new Vector database")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS modules (
            mid integer PRIMARY KEY AUTOINCREMENT,
            module_pkg_name text NOT NULL UNIQUE,
            apk_path text NOT NULL,
            enabled BOOLEAN DEFAULT 0 CHECK (enabled IN (0, 1)),
            auto_include BOOLEAN DEFAULT 0 CHECK (auto_include IN (0, 1))
        );
        """
            .trimIndent())

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS scope (
            mid integer,
            app_pkg_name text NOT NULL,
            user_id integer NOT NULL,
            PRIMARY KEY (mid, app_pkg_name, user_id),
            CONSTRAINT scope_module_constraint FOREIGN KEY (mid) REFERENCES modules (mid) ON DELETE CASCADE
        );
        """
            .trimIndent())

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS configs (
            module_pkg_name text NOT NULL,
            user_id integer NOT NULL,
            `group` text NOT NULL,
            `key` text NOT NULL,
            data blob NOT NULL,
            PRIMARY KEY (module_pkg_name, user_id, `group`, `key`),
            CONSTRAINT config_module_constraint FOREIGN KEY (module_pkg_name) REFERENCES modules (module_pkg_name) ON DELETE CASCADE
        );
        """
            .trimIndent())

    db.execSQL("CREATE INDEX IF NOT EXISTS configs_idx ON configs (module_pkg_name, user_id);")

    // Insert self
    db.execSQL(
        "INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES ('lspd', ?)",
        arrayOf(FileSystem.managerApkPath.toString()))
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "Upgrading database from $oldVersion to $newVersion")
    if (oldVersion < 2) {
      db.execSQL("DROP INDEX IF EXISTS configs_idx;")
      db.execSQL("ALTER TABLE scope RENAME TO old_scope;")
      db.execSQL("ALTER TABLE configs RENAME TO old_configs;")
      onCreate(db) // Recreate tables with strict constraints
      runCatching { db.execSQL("INSERT INTO scope SELECT * FROM old_scope;") }
      runCatching { db.execSQL("INSERT INTO configs SELECT * FROM old_configs;") }
      db.execSQL("DROP TABLE old_scope;")
      db.execSQL("DROP TABLE old_configs;")
    }
    if (oldVersion < 3) {
      db.execSQL("UPDATE scope SET app_pkg_name = 'system' WHERE app_pkg_name = 'android';")
    }
    if (oldVersion < 4) {
      runCatching {
        db.execSQL(
            "ALTER TABLE modules ADD COLUMN auto_include BOOLEAN DEFAULT 0 CHECK (auto_include IN (0, 1));")
      }
    }
  }
}
