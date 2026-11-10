package net.tgnra.morningissue.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the latest value of a screen's flows across configuration changes. One instance lives
 * in each navigation destination's view-model store, so a rotated screen draws again from what
 * it already had instead of going blank until the database answers.
 */
class RetainedFlows : ViewModel() {
    private val states = HashMap<String, StateFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> state(key: String, flow: () -> Flow<T>): StateFlow<T?> =
        states.getOrPut(key) {
            // Kept collecting for a few seconds without a collector: long enough for a rotation.
            flow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)
        } as StateFlow<T?>

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Collects [flow] as state that survives rotation; null until the first value arrives. [key]
 * names the flow within this destination and must change when what is observed changes (put
 * the ID in it). [flow] is only called the first time a key is seen.
 */
@Composable
fun <T : Any> retainedState(key: String, flow: () -> Flow<T>): State<T?> {
    val holder: RetainedFlows = viewModel()
    return holder.state(key, flow).collectAsStateWithLifecycle()
}
