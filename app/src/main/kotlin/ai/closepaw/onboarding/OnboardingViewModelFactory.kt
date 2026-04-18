package ai.closepaw.onboarding

import android.content.Context
import ai.closepaw.app.AppSettingsState
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.llm.ModelCatalog
import kotlinx.coroutines.CoroutineScope

/**
 * Assembles an [OnboardingViewModel] with the app-scoped [ai.closepaw.auth.AuthStore].
 *
 * MainActivity uses this factory so it doesn't need to know how [AuthStoreHolder]
 * is wired. Tests can skip the factory and construct the view model directly.
 */
object OnboardingViewModelFactory {
    fun create(
        context: Context,
        store: OnboardingStore,
        settingsState: AppSettingsState,
        modelCatalog: ModelCatalog,
        permissionMonitor: PermissionStateMonitor,
        demoController: OnboardingDemoController,
        scope: CoroutineScope
    ): OnboardingViewModel = OnboardingViewModel(
        store = store,
        settingsState = settingsState,
        modelCatalog = modelCatalog,
        permissionMonitor = permissionMonitor,
        authStore = AuthStoreHolder.get(context.applicationContext),
        demoController = demoController,
        scope = scope
    )
}
