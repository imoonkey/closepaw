package ai.closepaw.tool

import com.openai.core.JsonValue
import org.json.JSONArray
import org.json.JSONObject

internal fun jsonObjectToJsonValueMap(json: JSONObject): Map<String, JsonValue> {
    val map = mutableMapOf<String, JsonValue>()
    json.keys().forEach { key ->
        map[key] = JsonValue.from(convertJsonElement(json.get(key)))
    }
    return map
}

private fun convertJsonElement(value: Any?): Any? {
    return when (value) {
        JSONObject.NULL -> null
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            value.keys().forEach { key ->
                map[key] = convertJsonElement(value.get(key))
            }
            map
        }
        is JSONArray -> {
            val list = mutableListOf<Any?>()
            for (i in 0 until value.length()) {
                list.add(convertJsonElement(value.get(i)))
            }
            list
        }
        else -> value
    }
}
