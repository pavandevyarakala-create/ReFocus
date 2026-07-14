package com.reeltrak.app.data.local.dao;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.reeltrak.app.data.local.entity.DailyStatisticsEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DailyStatisticsDao_Impl implements DailyStatisticsDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<DailyStatisticsEntity> __insertionAdapterOfDailyStatisticsEntity;

  private final SharedSQLiteStatement __preparedStmtOfIncrementCount;

  private final SharedSQLiteStatement __preparedStmtOfUpdateGoal;

  private final SharedSQLiteStatement __preparedStmtOfFinalizeDay;

  public DailyStatisticsDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDailyStatisticsEntity = new EntityInsertionAdapter<DailyStatisticsEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `daily_statistics` (`dateEpochDay`,`totalReelsScrolled`,`dailyGoal`,`completionPercentage`,`colorLevel`,`lastUpdated`) VALUES (?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DailyStatisticsEntity entity) {
        statement.bindLong(1, entity.getDateEpochDay());
        statement.bindLong(2, entity.getTotalReelsScrolled());
        statement.bindLong(3, entity.getDailyGoal());
        statement.bindDouble(4, entity.getCompletionPercentage());
        statement.bindLong(5, entity.getColorLevel());
        statement.bindLong(6, entity.getLastUpdated());
      }
    };
    this.__preparedStmtOfIncrementCount = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE daily_statistics SET totalReelsScrolled = totalReelsScrolled + ?, lastUpdated = ? WHERE dateEpochDay = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateGoal = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE daily_statistics SET dailyGoal = ? WHERE dateEpochDay = ?";
        return _query;
      }
    };
    this.__preparedStmtOfFinalizeDay = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE daily_statistics SET completionPercentage = ?, colorLevel = ? WHERE dateEpochDay = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final DailyStatisticsEntity entity,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfDailyStatisticsEntity.insertAndReturnId(entity);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object incrementCount(final long dateEpochDay, final int amount, final long timestamp,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfIncrementCount.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, amount);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, timestamp);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, dateEpochDay);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfIncrementCount.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGoal(final long dateEpochDay, final int goal,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateGoal.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, goal);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, dateEpochDay);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateGoal.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object finalizeDay(final long dateEpochDay, final float percentage, final int level,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfFinalizeDay.acquire();
        int _argIndex = 1;
        _stmt.bindDouble(_argIndex, percentage);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, level);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, dateEpochDay);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfFinalizeDay.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object getStatisticsForDate(final long dateEpochDay,
      final Continuation<? super DailyStatisticsEntity> $completion) {
    final String _sql = "SELECT * FROM daily_statistics WHERE dateEpochDay = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, dateEpochDay);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<DailyStatisticsEntity>() {
      @Override
      @Nullable
      public DailyStatisticsEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfDateEpochDay = CursorUtil.getColumnIndexOrThrow(_cursor, "dateEpochDay");
          final int _cursorIndexOfTotalReelsScrolled = CursorUtil.getColumnIndexOrThrow(_cursor, "totalReelsScrolled");
          final int _cursorIndexOfDailyGoal = CursorUtil.getColumnIndexOrThrow(_cursor, "dailyGoal");
          final int _cursorIndexOfCompletionPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "completionPercentage");
          final int _cursorIndexOfColorLevel = CursorUtil.getColumnIndexOrThrow(_cursor, "colorLevel");
          final int _cursorIndexOfLastUpdated = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdated");
          final DailyStatisticsEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpDateEpochDay;
            _tmpDateEpochDay = _cursor.getLong(_cursorIndexOfDateEpochDay);
            final int _tmpTotalReelsScrolled;
            _tmpTotalReelsScrolled = _cursor.getInt(_cursorIndexOfTotalReelsScrolled);
            final int _tmpDailyGoal;
            _tmpDailyGoal = _cursor.getInt(_cursorIndexOfDailyGoal);
            final float _tmpCompletionPercentage;
            _tmpCompletionPercentage = _cursor.getFloat(_cursorIndexOfCompletionPercentage);
            final int _tmpColorLevel;
            _tmpColorLevel = _cursor.getInt(_cursorIndexOfColorLevel);
            final long _tmpLastUpdated;
            _tmpLastUpdated = _cursor.getLong(_cursorIndexOfLastUpdated);
            _result = new DailyStatisticsEntity(_tmpDateEpochDay,_tmpTotalReelsScrolled,_tmpDailyGoal,_tmpCompletionPercentage,_tmpColorLevel,_tmpLastUpdated);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<DailyStatisticsEntity> getStatisticsForDateFlow(final long dateEpochDay) {
    final String _sql = "SELECT * FROM daily_statistics WHERE dateEpochDay = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, dateEpochDay);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"daily_statistics"}, new Callable<DailyStatisticsEntity>() {
      @Override
      @Nullable
      public DailyStatisticsEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfDateEpochDay = CursorUtil.getColumnIndexOrThrow(_cursor, "dateEpochDay");
          final int _cursorIndexOfTotalReelsScrolled = CursorUtil.getColumnIndexOrThrow(_cursor, "totalReelsScrolled");
          final int _cursorIndexOfDailyGoal = CursorUtil.getColumnIndexOrThrow(_cursor, "dailyGoal");
          final int _cursorIndexOfCompletionPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "completionPercentage");
          final int _cursorIndexOfColorLevel = CursorUtil.getColumnIndexOrThrow(_cursor, "colorLevel");
          final int _cursorIndexOfLastUpdated = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdated");
          final DailyStatisticsEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpDateEpochDay;
            _tmpDateEpochDay = _cursor.getLong(_cursorIndexOfDateEpochDay);
            final int _tmpTotalReelsScrolled;
            _tmpTotalReelsScrolled = _cursor.getInt(_cursorIndexOfTotalReelsScrolled);
            final int _tmpDailyGoal;
            _tmpDailyGoal = _cursor.getInt(_cursorIndexOfDailyGoal);
            final float _tmpCompletionPercentage;
            _tmpCompletionPercentage = _cursor.getFloat(_cursorIndexOfCompletionPercentage);
            final int _tmpColorLevel;
            _tmpColorLevel = _cursor.getInt(_cursorIndexOfColorLevel);
            final long _tmpLastUpdated;
            _tmpLastUpdated = _cursor.getLong(_cursorIndexOfLastUpdated);
            _result = new DailyStatisticsEntity(_tmpDateEpochDay,_tmpTotalReelsScrolled,_tmpDailyGoal,_tmpCompletionPercentage,_tmpColorLevel,_tmpLastUpdated);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<DailyStatisticsEntity>> getStatisticsRangeFlow(final long startDay,
      final long endDay) {
    final String _sql = "SELECT * FROM daily_statistics WHERE dateEpochDay >= ? AND dateEpochDay <= ? ORDER BY dateEpochDay ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, startDay);
    _argIndex = 2;
    _statement.bindLong(_argIndex, endDay);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"daily_statistics"}, new Callable<List<DailyStatisticsEntity>>() {
      @Override
      @NonNull
      public List<DailyStatisticsEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfDateEpochDay = CursorUtil.getColumnIndexOrThrow(_cursor, "dateEpochDay");
          final int _cursorIndexOfTotalReelsScrolled = CursorUtil.getColumnIndexOrThrow(_cursor, "totalReelsScrolled");
          final int _cursorIndexOfDailyGoal = CursorUtil.getColumnIndexOrThrow(_cursor, "dailyGoal");
          final int _cursorIndexOfCompletionPercentage = CursorUtil.getColumnIndexOrThrow(_cursor, "completionPercentage");
          final int _cursorIndexOfColorLevel = CursorUtil.getColumnIndexOrThrow(_cursor, "colorLevel");
          final int _cursorIndexOfLastUpdated = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdated");
          final List<DailyStatisticsEntity> _result = new ArrayList<DailyStatisticsEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DailyStatisticsEntity _item;
            final long _tmpDateEpochDay;
            _tmpDateEpochDay = _cursor.getLong(_cursorIndexOfDateEpochDay);
            final int _tmpTotalReelsScrolled;
            _tmpTotalReelsScrolled = _cursor.getInt(_cursorIndexOfTotalReelsScrolled);
            final int _tmpDailyGoal;
            _tmpDailyGoal = _cursor.getInt(_cursorIndexOfDailyGoal);
            final float _tmpCompletionPercentage;
            _tmpCompletionPercentage = _cursor.getFloat(_cursorIndexOfCompletionPercentage);
            final int _tmpColorLevel;
            _tmpColorLevel = _cursor.getInt(_cursorIndexOfColorLevel);
            final long _tmpLastUpdated;
            _tmpLastUpdated = _cursor.getLong(_cursorIndexOfLastUpdated);
            _item = new DailyStatisticsEntity(_tmpDateEpochDay,_tmpTotalReelsScrolled,_tmpDailyGoal,_tmpCompletionPercentage,_tmpColorLevel,_tmpLastUpdated);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
