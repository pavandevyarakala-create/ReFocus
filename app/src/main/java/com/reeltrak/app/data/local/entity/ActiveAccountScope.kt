package com.reeltrak.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** The account namespace currently visible through account-scoped local data queries. */
@Entity(tableName = "active_account_scope")
data class ActiveAccountScope(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id") val singletonId: Int = 1,
    @ColumnInfo(name = "account_scope") val accountScope: String
)
