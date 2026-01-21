package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.BaseTool
import org.json.JSONObject

/**
 * ClickTool - Click on a UI element by its index.
 */
class ClickTool : BaseTool() {
    
    override val name: String = "click"
    
    override val description: String = 
        "Click on a UI element identified by its index in the screen elements list."
    
    override val parameterSchema: JSONObject = createSchema(
        properties = mapOf(
            "element_index" to ("integer" to "The index of the element to click (from screen elements)")
        ),
        required = listOf("element_index")
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        
        val elementIndex = validateRequiredInt(params, "element_index", errors)
        if (elementIndex != null && elementIndex < 0) {
            errors.add("element_index must be non-negative")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    override fun createUIAction(params: JSONObject): UIAction? {
        val elementIndex = params.optInt("element_index", -1)
        if (elementIndex < 0) return null
        return UIAction.Click(elementIndex)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        val elementIndex = params.optInt("element_index", -1)
        return "Click on element at index $elementIndex"
    }
}

