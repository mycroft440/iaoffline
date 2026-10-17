package com.example.ialocal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.ialocal.runtime.DeviceMemory
import com.example.ialocal.runtime.MemorySnapshot
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The device RAM, read again every [intervalMillis] while the screen is visible, so loading or
 * releasing a model shows up instead of the value from when the screen opened.
 */
@Composable
fun rememberLiveMemory(intervalMillis: Long = 2_000L): MemorySnapshot {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by produceState(initialValue = DeviceMemory.read(context), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { DeviceMemory.read(context) }
                delay(intervalMillis)
            }
        }
    }
    return snapshot
}

/** "3,4 GB" in the device's locale, in the binary gigabytes Android's own settings use. */
fun formatMemoryGb(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

/** A RAM size as phones and the catalog state it: "12 GB", "1,5 GB". */
fun formatAdvertisedGb(gigabytes: Double): String =
    if (gigabytes % 1.0 == 0.0) String.format(Locale.getDefault(), "%.0f GB", gigabytes)
    else String.format(Locale.getDefault(), "%.1f GB", gigabytes)
