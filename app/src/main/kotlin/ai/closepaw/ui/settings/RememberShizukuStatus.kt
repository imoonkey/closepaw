package ai.closepaw.ui.settings

import ai.closepaw.platform.virtualdisplay.ShizukuClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rikka.shizuku.Shizuku

@Composable
fun rememberShizukuStatus(client: ShizukuClient): State<ShizukuStatus> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<ShizukuStatus>(initialValue = ShizukuStatus.Unavailable, client, lifecycle) {
        fun read(): ShizukuStatus = when {
            !client.isAvailable() -> ShizukuStatus.Unavailable
            !client.hasPermission() -> ShizukuStatus.NeedsPermission
            else -> ShizukuStatus.Ready
        }

        value = read()

        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            value = read()
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            value = read()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                value = read()
            }
        }

        client.addRequestPermissionResultListener(permissionListener)
        client.addBinderDeadListener(binderDeadListener)
        lifecycle.addObserver(lifecycleObserver)

        awaitDispose {
            client.removeRequestPermissionResultListener(permissionListener)
            client.removeBinderDeadListener(binderDeadListener)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
}
