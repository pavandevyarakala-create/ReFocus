package com.reeltrak.app.data.local.dao;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomDatabaseKt;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.reeltrak.app.data.local.entity.GlobalStatisticsEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class GlobalStatisticsDao_Impl implements GlobalStatisticsDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<GlobalStatisticsEntity> __insertionAdapterOfGlobalStatisticsEntity;

  public GlobalStatisticsDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfGlobalStatisticsEntity = new EntityInsertionAdapter<GlobalStatisticsEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `global_statistics` (`id`,`lifetimeReels`,`completedDays`) VALUES (?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final GlobalStatisticsEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getLifetimeReels());
        statement.bindLong(3, entity.getCompletedDays());
      }
    };
  }

  @Override
  public Object update(final GlobalStatisticsEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfGlobalStatisticsEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object incrementCompletedDay(final int todayReels,
      final Continuation<? super Unit> $completion) {
    return RoomDatabaseKt.withTransaction(__db, (__cont) -> GlobalStatisticsDao.DefaultImpls.incrementCompletedDay(GlobalStatisticsDao_Impl.this, todayReels, __cont), $completion);
  }

  @Override
  public Flow<GlobalStatisticsEntity> getGlobalStatistics() {
    final String _sql = "SELECT * FROM global_statistics WHERE id = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"global_statistics"}, new Callable<GlobalStatisticsEntity>() {
      @Override
      @Nullable
      public GlobalStatisticsEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfLifetimeReels = CursorUtil.getColumnIndexOrThrow(_cursor, "lifetimeReels");
          final int _cursorIndexOfCompletedDays = CursorUtil.getColumnIndexOrThrow(_cursor, "completedDays");
          final GlobalStatisticsEntity _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final long _tmpLifetimeReels;
            _tmpLifetimeReels = _cursor.getLong(_cursorIndexOfLifetimeReels);
            final int _tmpCompletedDays;
            _tmpCompletedDays = _cursor.getInt(_cursorIndexOfCompletedDays);
            _result = new GlobalStatisticsEntity(_tmpId,_tmpLifetimeReels,_tmpCompletedDays);
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
  public Object getGlobalStatisticsSync(
      final Continuation<? super GlobalStatisticsEntity> $completion) {
    final String _sql = "SELECT * FROM global_statistics WHERE id = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<GlobalStatisticsEntity>() {
      @Override
      @Nullable
      public GlobalStatisticsEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfLifetimeReels = CursorUtil.getColumnIndexOrThrow(_cursor, "lifetimeReels");
          final int _cursorIndexOfCompletedDays = CursorUtil.getColumnIndexOrThrow(_cursor, "completedDays");
          final GlobalStatisticsEntity _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final long _tmpLifetimeReels;
            _tmpLifetimeReels = _cursor.getLong(_cursorIndexOfLifetimeReels);
            final int _tmpCompletedDays;
            _tmpCompletedDays = _cursor.getInt(_cursorIndexOfCompletedDays);
            _result = new GlobalStatisticsEntity(_tmpId,_tmpLifetimeReels,_tmpCompletedDays);
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
