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
import com.reeltrak.app.data.local.dao.DailyStatisticsDao;
import com.reeltrak.app.data.local.dao.DailyStatisticsDao_Impl;
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao;
import com.reeltrak.app.data.local.dao.GlobalStatisticsDao_Impl;
import com.reeltrak.app.data.local.dao.SessionDao;
import com.reeltrak.app.data.local.dao.SessionDao_Impl;
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
  private volatile SessionDao _sessionDao;

  private volatile DailyStatisticsDao _dailyStatisticsDao;

  private volatile GlobalStatisticsDao _globalStatisticsDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(5) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `scroll_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `appName` TEXT NOT NULL, `startTimeStamp` INTEGER NOT NULL, `endTimeStamp` INTEGER NOT NULL, `reelsScrolled` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `daily_statistics` (`dateEpochDay` INTEGER NOT NULL, `totalReelsScrolled` INTEGER NOT NULL, `dailyGoal` INTEGER NOT NULL, `completionPercentage` REAL NOT NULL, `colorLevel` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`dateEpochDay`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `global_statistics` (`id` INTEGER NOT NULL, `lifetimeReels` INTEGER NOT NULL, `completedDays` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0cbd76e77496c8855fc08c5911ab4201')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `scroll_sessions`");
        db.execSQL("DROP TABLE IF EXISTS `daily_statistics`");
        db.execSQL("DROP TABLE IF EXISTS `global_statistics`");
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
        final HashMap<String, TableInfo.Column> _columnsScrollSessions = new HashMap<String, TableInfo.Column>(5);
        _columnsScrollSessions.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsScrollSessions.put("appName", new TableInfo.Column("appName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsScrollSessions.put("startTimeStamp", new TableInfo.Column("startTimeStamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsScrollSessions.put("endTimeStamp", new TableInfo.Column("endTimeStamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsScrollSessions.put("reelsScrolled", new TableInfo.Column("reelsScrolled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysScrollSessions = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesScrollSessions = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoScrollSessions = new TableInfo("scroll_sessions", _columnsScrollSessions, _foreignKeysScrollSessions, _indicesScrollSessions);
        final TableInfo _existingScrollSessions = TableInfo.read(db, "scroll_sessions");
        if (!_infoScrollSessions.equals(_existingScrollSessions)) {
          return new RoomOpenHelper.ValidationResult(false, "scroll_sessions(com.reeltrak.app.data.local.entity.SessionEntity).\n"
                  + " Expected:\n" + _infoScrollSessions + "\n"
                  + " Found:\n" + _existingScrollSessions);
        }
        final HashMap<String, TableInfo.Column> _columnsDailyStatistics = new HashMap<String, TableInfo.Column>(6);
        _columnsDailyStatistics.put("dateEpochDay", new TableInfo.Column("dateEpochDay", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyStatistics.put("totalReelsScrolled", new TableInfo.Column("totalReelsScrolled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyStatistics.put("dailyGoal", new TableInfo.Column("dailyGoal", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyStatistics.put("completionPercentage", new TableInfo.Column("completionPercentage", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyStatistics.put("colorLevel", new TableInfo.Column("colorLevel", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyStatistics.put("lastUpdated", new TableInfo.Column("lastUpdated", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysDailyStatistics = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesDailyStatistics = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoDailyStatistics = new TableInfo("daily_statistics", _columnsDailyStatistics, _foreignKeysDailyStatistics, _indicesDailyStatistics);
        final TableInfo _existingDailyStatistics = TableInfo.read(db, "daily_statistics");
        if (!_infoDailyStatistics.equals(_existingDailyStatistics)) {
          return new RoomOpenHelper.ValidationResult(false, "daily_statistics(com.reeltrak.app.data.local.entity.DailyStatisticsEntity).\n"
                  + " Expected:\n" + _infoDailyStatistics + "\n"
                  + " Found:\n" + _existingDailyStatistics);
        }
        final HashMap<String, TableInfo.Column> _columnsGlobalStatistics = new HashMap<String, TableInfo.Column>(3);
        _columnsGlobalStatistics.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlobalStatistics.put("lifetimeReels", new TableInfo.Column("lifetimeReels", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlobalStatistics.put("completedDays", new TableInfo.Column("completedDays", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysGlobalStatistics = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesGlobalStatistics = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoGlobalStatistics = new TableInfo("global_statistics", _columnsGlobalStatistics, _foreignKeysGlobalStatistics, _indicesGlobalStatistics);
        final TableInfo _existingGlobalStatistics = TableInfo.read(db, "global_statistics");
        if (!_infoGlobalStatistics.equals(_existingGlobalStatistics)) {
          return new RoomOpenHelper.ValidationResult(false, "global_statistics(com.reeltrak.app.data.local.entity.GlobalStatisticsEntity).\n"
                  + " Expected:\n" + _infoGlobalStatistics + "\n"
                  + " Found:\n" + _existingGlobalStatistics);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "0cbd76e77496c8855fc08c5911ab4201", "7ec7f5c2c3be73987c4ae37966a97205");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "scroll_sessions","daily_statistics","global_statistics");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `scroll_sessions`");
      _db.execSQL("DELETE FROM `daily_statistics`");
      _db.execSQL("DELETE FROM `global_statistics`");
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
    _typeConvertersMap.put(SessionDao.class, SessionDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(DailyStatisticsDao.class, DailyStatisticsDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(GlobalStatisticsDao.class, GlobalStatisticsDao_Impl.getRequiredConverters());
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
  public SessionDao getSessionDao() {
    if (_sessionDao != null) {
      return _sessionDao;
    } else {
      synchronized(this) {
        if(_sessionDao == null) {
          _sessionDao = new SessionDao_Impl(this);
        }
        return _sessionDao;
      }
    }
  }

  @Override
  public DailyStatisticsDao getDailyStatisticsDao() {
    if (_dailyStatisticsDao != null) {
      return _dailyStatisticsDao;
    } else {
      synchronized(this) {
        if(_dailyStatisticsDao == null) {
          _dailyStatisticsDao = new DailyStatisticsDao_Impl(this);
        }
        return _dailyStatisticsDao;
      }
    }
  }

  @Override
  public GlobalStatisticsDao getGlobalStatisticsDao() {
    if (_globalStatisticsDao != null) {
      return _globalStatisticsDao;
    } else {
      synchronized(this) {
        if(_globalStatisticsDao == null) {
          _globalStatisticsDao = new GlobalStatisticsDao_Impl(this);
        }
        return _globalStatisticsDao;
      }
    }
  }
}
