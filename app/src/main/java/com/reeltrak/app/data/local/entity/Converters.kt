package com.reeltrak.app.data.local.entity

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromSessionList(value: List<SessionData>?): String {
        return gson.toJson(value ?: emptyList<SessionData>())
    }

    @TypeConverter
    fun toSessionList(value: String?): List<SessionData> {
        if (value.isNullOrEmpty()) {
            return emptyList()
        }
        val listType = object : TypeToken<List<SessionData>>() {}.type
        return gson.fromJson(value, listType)
    }
}
