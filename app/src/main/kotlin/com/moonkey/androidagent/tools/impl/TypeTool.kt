package com.moonkey.androidagent.tools.impl

import com.moonkey.androidagent.infra.tools.ValidationResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tools.base.BaseTool
import org.json.JSONObject

/**
 * TypeTool - Type text into a UI element.
 */
class TypeTool : BaseTool() {
    
    override val name: String = "type"
    
    override val description: String = 
        "Type text into an editable UI element (e.g., text field, search box)."
    
    override val parameterSchema: JSONObject = createSchema(
        properties = mapOf(
            "element_index" to ("integer" to "The index of the element to type into"),
            "text" to ("string" to "The text to type")
        ),
        required = listOf("element_index", "text")
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        
        val elementIndex = validateRequiredInt(params, "element_index", errors)
        if (elementIndex != null && elementIndex < 0) {
            errors.add("element_index must be non-negative")
        }
        
        validateRequiredString(params, "text", errors)
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    override fun createUIAction(params: JSONObject): UIAction? {
        val elementIndex = params.optInt("element_index", -1)
        val text = params.optString("text", "")
        if (elementIndex < 0) return null
        return UIAction.Type(elementIndex, text)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        val elementIndex = params.optInt("element_index", -1)
        val text = params.optString("text", "")
        val displayText = if (text.length > 20) "${text.take(20)}..." else text
        return "Type \"$displayText\" into element at index $elementIndex"
    }
}

