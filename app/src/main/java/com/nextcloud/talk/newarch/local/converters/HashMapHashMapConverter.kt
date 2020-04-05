package com.nextcloud.talk.newarch.local.converters

import androidx.room.TypeConverter
import com.bluelinelabs.logansquare.LoganSquare

class HashMapHashMapConverter {
    @TypeConverter
    fun fromDoubleHashMapToString(map: HashMap<String, HashMap<String, String>>?): String? {
        if (map == null) {
            return ""
        }

        return LoganSquare.serialize(map)
    }

    @TypeConverter
    fun fromStringToDoubleHashMap(value: String?): HashMap<String, HashMap<String, String>>? {
        if (value.isNullOrEmpty()) {
            return null
        }

        return LoganSquare.parseMap(value, HashMap::class.java) as HashMap<String, HashMap<String, String>>?
    }
}