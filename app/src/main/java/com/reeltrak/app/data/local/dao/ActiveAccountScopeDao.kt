package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reeltrak.app.data.local.entity.ActiveAccountScope

@Dao
interface ActiveAccountScopeDao {
    @Query("SELECT account_scope FROM active_account_scope WHERE singleton_id = 1")
    suspend fun getActiveScope(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActiveScope(scope: ActiveAccountScope)
}
