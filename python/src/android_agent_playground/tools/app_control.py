from __future__ import annotations

from dataclasses import dataclass
import json
import time

from android_agent_playground.perceptor import Perceptor
from android_agent_playground.platform import ActionResultCancelled, ActionResultFailure, ActionResultSuccess
from android_agent_playground.tools.registry import (
    ToolExecutionCancelled,
    ToolExecutionFailure,
    ToolExecutionResult,
    ToolExecutionSuccess,
    ToolInvocation,
    ToolObservationScreenState,
    ToolSpec,
    ToolExecutionContext,
    ValidationResult,
)


APP_PACKAGE_ALIASES = {
    "google maps": "com.google.android.apps.maps",
    "maps": "com.google.android.apps.maps",
    "chrome": "com.android.chrome",
    "google chrome": "com.android.chrome",
    "browser": "com.android.chrome",
    "gmail": "com.google.android.gm",
    "email": "com.google.android.gm",
    "youtube": "com.google.android.youtube",
    "play store": "com.android.vending",
    "google play": "com.android.vending",
    "files": "com.google.android.apps.nbu.files",
    "phone": "com.android.dialer",
    "dialer": "com.android.dialer",
    "camera": "com.android.camera",
    "settings": "com.android.settings",
    "messages": "com.google.android.apps.messaging",
    "sms": "com.google.android.apps.messaging",
}

APP_SEARCH_ALIASES = {
    "map": ["maps", "地图", "com.google.android.apps.maps"],
    "maps": ["map", "地图", "com.google.android.apps.maps"],
    "browser": ["chrome", "浏览器", "com.android.chrome"],
    "search": ["google", "chrome", "browser"],
    "video": ["youtube", "tiktok"],
    "email": ["gmail", "mail"],
    "chat": ["whatsapp", "wechat", "微信", "messenger"],
    "music": ["spotify", "music", "yt music"],
}


class AppControlTool(ToolSpec):
    name = "app_control"
    description = (
        "Control apps on the device.\n\n"
        "Actions:\n"
        "- list_apps: Get list of installed launchable apps. Use filter to search by name.\n"
        "- open_app: Launch an app by package_name (e.g., 'com.google.android.gm') or app_name (e.g., 'Gmail'). "
        "Package name takes precedence if both provided."
    )

    def __init__(self) -> None:
        self._handlers = {
            "list_apps": ListAppsActionHandler(),
            "open_app": OpenAppActionHandler(),
        }

    @property
    def parameter_schema(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "action": {"type": "string", "description": "The action to perform"},
                "package_name": {"type": "string", "description": "Package name for open_app"},
                "app_name": {"type": "string", "description": "Display name for open_app"},
                "filter": {"type": "string", "description": "Filter for list_apps"},
            },
            "required": ["action"],
            "additionalProperties": False,
        }

    def validate(self, params: dict) -> ValidationResult:
        action = params.get("action")
        if not action:
            return ValidationResult.invalid("Missing required parameter: action")
        handler = self._handlers.get(action)
        if handler is None:
            return ValidationResult.invalid(f"Unknown action: {action}")
        return handler.validate(params)

    def create_invocation(self, params: dict) -> ToolInvocation:
        action = params.get("action")
        handler = self._handlers[action]
        return handler.create_invocation(params)


class ListAppsActionHandler:
    action_name = "list_apps"

    def validate(self, params: dict) -> ValidationResult:
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        filter_term = params.get("filter", "")
        description = f"List apps matching '{filter_term}'" if filter_term else "List all installed apps"
        return ListAppsInvocation(params=params, description=description, filter_term=filter_term)


@dataclass(frozen=True)
class ListAppsInvocation:
    params: dict
    description: str
    filter_term: str
    tool_name: str = "app_control"

    def get_description(self) -> str:
        return self.description

    def execute(self, context: ToolExecutionContext) -> ToolExecutionResult:
        if context.is_cancelled():
            return ToolExecutionCancelled("Cancelled before execution")
        apps = context.platform.get_installed_apps()
        filtered = apps
        if self.filter_term:
            search_term = self.filter_term.lower()
            alias_terms = APP_SEARCH_ALIASES.get(search_term, [])
            all_terms = [search_term, *alias_terms]
            filtered = [
                app
                for app in apps
                if any(
                    term in app.label.lower() or term in app.package_name.lower()
                    for term in all_terms
                )
            ]
        filtered_sorted = sorted(filtered, key=lambda app: app.label.lower())
        payload = {
            "apps": [{"package_name": app.package_name, "label": app.label} for app in filtered_sorted],
            "count": len(filtered_sorted),
        }
        if self.filter_term:
            payload["filter"] = self.filter_term
        return ToolExecutionSuccess(output=json.dumps(payload, indent=2))


class OpenAppActionHandler:
    action_name = "open_app"

    def validate(self, params: dict) -> ValidationResult:
        package_name = params.get("package_name", "")
        app_name = params.get("app_name", "")
        if not package_name and not app_name:
            return ValidationResult.invalid("open_app requires either package_name or app_name")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        package_name = params.get("package_name", "")
        app_name = params.get("app_name", "")
        description = f"Open app: {package_name}" if package_name else f"Open app: {app_name}"
        return OpenAppInvocation(params=params, description=description, package_name=package_name, app_name=app_name)


@dataclass(frozen=True)
class OpenAppInvocation:
    params: dict
    description: str
    package_name: str
    app_name: str
    tool_name: str = "app_control"

    def get_description(self) -> str:
        return self.description

    def execute(self, context: ToolExecutionContext) -> ToolExecutionResult:
        if context.is_cancelled():
            return ToolExecutionCancelled("Cancelled before execution")

        target_package = self.package_name or self._resolve_package(context)
        if not target_package:
            return ToolExecutionFailure("App not found. Use list_apps to see available apps.")

        result = context.platform.launch_app(target_package)
        if isinstance(result, ActionResultFailure):
            return ToolExecutionFailure(result.reason, result.exception)
        if isinstance(result, ActionResultCancelled):
            return ToolExecutionFailure(result.reason)
        if not isinstance(result, ActionResultSuccess):
            return ToolExecutionFailure("Unexpected result from launch_app")

        time.sleep(0.8)
        snapshot = None
        try:
            snapshot = context.platform.capture_screen()
        except Exception:
            snapshot = None

        observation = None
        if snapshot:
            tree = Perceptor.to_prompt_json(snapshot)
            observation = ToolObservationScreenState(
                accessibility_tree=tree,
                element_count=len(snapshot.elements),
                snapshot=snapshot,
            )

        return ToolExecutionSuccess(output=f"Launched app: {target_package}", observation=observation)

    def _resolve_package(self, context: ToolExecutionContext) -> str | None:
        search_term = self.app_name.lower()
        apps = context.platform.get_installed_apps()

        match = next((app for app in apps if app.label.lower() == search_term), None)
        if match:
            return match.package_name
        match = next((app for app in apps if search_term in app.label.lower()), None)
        if match:
            return match.package_name
        match = next((app for app in apps if search_term in app.package_name.lower()), None)
        if match:
            return match.package_name
        alias_pkg = APP_PACKAGE_ALIASES.get(search_term)
        if alias_pkg:
            match = next((app for app in apps if app.package_name == alias_pkg), None)
            if match:
                return match.package_name
        return None
