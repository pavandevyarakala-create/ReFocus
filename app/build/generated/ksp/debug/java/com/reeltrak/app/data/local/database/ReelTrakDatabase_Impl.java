package com.reeltrak.app.data.local.database;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import com.reeltrak.app.data.local.dao.DailyReelDataDao;
import com.reeltrak.app.data.local.dao.DailyReelDataDao_Impl;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ReelTrakDatabase_Impl extends ReelTrakDatabase {
  private volatile DailyReelDataDao _dailyReelDataDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(10) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `daily_reel_data` (`dateString` TEXT NOT NULL, `totalReelsCount` INTEGER NOT NULL, `totalTimeSpentMillis` INTEGER NOT NULL, `dailyGoal` INTEGER NOT NULL, `isSkullDay` INTEGER NOT NULL, `isStrictModeOn` INTEGER NOT NULL, `isStrictModeLocked` INTEGER NOT NULL, `isLimitLocked` INTEGER NOT NULL, `sessions` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dateString`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'bbbdb11f36fae354fcd26b2c00e7b2b7')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `daily_reel_data`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsDailyReelData = new HashMap<String, TableInfo.Column>(10);
        _columnsDailyReelData.put("dateString", new TableInfo.Column("dateString", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("totalReelsCount", new TableInfo.Column("totalReelsCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("totalTimeSpentMillis", new TableInfo.Column("totalTimeSpentMillis", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("dailyGoal", new TableInfo.Column("dailyGoal", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("isSkullDay", new TableInfo.Column("isSkullDay", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("isStrictModeOn", new TableInfo.Column("isStrictModeOn", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("isStrictModeLocked", new TableInfo.Column("isStrictModeLocked", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("isLimitLocked", new TableInfo.Column("isLimitLocked", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("sessions", new TableInfo.Column("sessions", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyReelData.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysDailyReelData = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesDailyReelData = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoDailyReelData = new TableInfo("daily_reel_data", _columnsDailyReelData, _foreignKeysDailyReelData, _indicesDailyReelData);
        final TableInfo _existingDailyReelData = TableInfo.read(db, "daily_reel_data");
        if (!_infoDailyReelData.equals(_existingDailyReelData)) {
          return new RoomOpenHelper.ValidationResult(false, "daily_reel_data(com.reeltrak.app.data.local.entity.DailyReelData).\n"
                  + " Expected:\n" + _infoDailyReelData + "\n"
                  + " Found:\n" + _existingDailyReelData);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "bbbdb11f36fae354fcd26b2c00e7b2b7", "f90b9402b880da03ece092b046460978");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "daily_reel_data");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `daily_reel_data`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(DailyReelDataDao.class, DailyReelDataDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public DailyReelDataDao getDailyReelDataDao() {
    if (_dailyReelDataDao != null) {
      return _dailyReelDataDao;
    } else {
      synchronized(this) {
        if(_dailyReelDataDao == null) {
          _dailyReelDataDao = new DailyReelDataDao_Impl(this);
        }
        return _dailyReelDataDao;
      }
    }
  }
}
