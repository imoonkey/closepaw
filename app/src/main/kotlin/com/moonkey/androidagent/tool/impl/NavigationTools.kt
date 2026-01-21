package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.BaseTool
import org.json.JSONObject

/**
 * BackTool - Press the system back button.
 */
class BackTool : BaseTool() {
    
    override val name: String = "back"
    
    override val description: String = 
        "Press the system back button to go to the previous screen."
    
    override val parameterSchema: JSONObject = emptySchema()
    
    override fun validate(params: JSONObject): ValidationResult {
        return ValidationResult.Valid
    }
    
    override fun createUIAction(params: JSONObject): UIAction {
        return UIAction.SystemButton(SystemButtonType.BACK)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        return "Press back button"
    }
}

/**
 * HomeTool - Press the system home button.
 */
class HomeTool : BaseTool() {
    
    override val name: String = "home"
    
    override val description: String = 
        "Press the system home button to go to the home screen."
    
    override val parameterSchema: JSONObject = emptySchema()
    
    override fun validate(params: JSONObject): ValidationResult {
        return ValidationResult.Valid
    }
    
    override fun createUIAction(params: JSONObject): UIAction {
        return UIAction.SystemButton(SystemButtonType.HOME)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        return "Press home button"
    }
}

