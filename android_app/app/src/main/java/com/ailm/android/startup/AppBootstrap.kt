package com.ailm.android.startup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ailm.android.runtime.StandaloneRuntime
import com.ailm.android.workers.InitialSetupWorker

object AppBootstrap {
    private val initializationStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var runtimeInitialized = false

    @Volatile
    private var workersRegistered = false

    @Volatile
    private var initializationCompleted = false

    @Volatile
    private var initializationError: String? = null

    fun initializeApplication(context: Context) {
        // Activity recreation cannot start another 300K-artwork database
        // initializer or competing maintenance worker.
        if (!initializationStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        // Initialize runtime and register workers asynchronously so application
        // startup and the splash screen are not blocked by heavy init work.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                // An uncaught exception on an Executor thread is a fatal Android
                // process exception. Preserve the installed database and expose
                // the startup error in the UI instead of killing the process
                // while the owner is looking at the splash screen.
                try {
                    initializeRuntime(appContext)
                } catch (failure: Throwable) {
                    initializationError = failure.message?.take(240)
                        ?: failure.javaClass.simpleName
                    Log.e("AsterionStartup", "Runtime initialization failed; data preserved.", failure)
                }
                if (runtimeInitialized) {
                    try {
                        registerStartupWorkers(appContext)
                    } catch (failure: Throwable) {
                        // WorkManager startup is auxiliary; do not kill the
                        // already initialized gallery.
                        Log.e("AsterionStartup", "Deferred setup work unavailable.", failure)
                    }
                }
            } finally {
                initializationCompleted = true
                notifyInitializationListeners()
                executor.shutdown()
            }
        }
    }

    private val initListeners = mutableListOf<() -> Unit>()

    fun isRuntimeInitialized(): Boolean = runtimeInitialized

    fun isInitializationCompleted(): Boolean = initializationCompleted

    fun initializationFailure(): String? = initializationError

    /**
     * The Compose gallery must never issue its first DB request before
     * StandaloneRuntime has finished wiring the existing profile and Fusion.
     * Waiting is cancellable and does not block the UI thread or modify data.
     */
    suspend fun awaitInitialization() {
        if (initializationCompleted) return
        suspendCancellableCoroutine<Unit> { continuation ->
            val callback: () -> Unit = {
                if (continuation.isActive) continuation.resume(Unit)
            }
            addInitializationListener(callback)
            continuation.invokeOnCancellation {
                synchronized(initListeners) { initListeners.remove(callback) }
            }
        }
    }

    fun addInitializationListener(listener: () -> Unit) {
        val shouldPostImmediately = synchronized(initListeners) {
            if (initializationCompleted) {
                true
            } else {
                initListeners.add(listener)
                false
            }
        }
        if (shouldPostImmediately) {
            Handler(Looper.getMainLooper()).post { listener() }
        }
    }

    private fun notifyInitializationListeners() {
        val toNotify = synchronized(initListeners) {
            val copy = initListeners.toList()
            initListeners.clear()
            copy
        }
        val handler = Handler(Looper.getMainLooper())
        toNotify.forEach { listener ->
            handler.post { listener() }
        }
    }

    private fun initializeRuntime(context: Context) {
        if (runtimeInitialized) {
            return
        }
        synchronized(this) {
            if (runtimeInitialized) {
                return
            }
            StandaloneRuntime.initialize(context.applicationContext)
            runtimeInitialized = true
        }
    }

    private fun registerStartupWorkers(context: Context) {
        if (workersRegistered) {
            return
        }
        synchronized(this) {
            if (workersRegistered) {
                return
            }

            val appContext = context.applicationContext
            val initialSetupRequest = OneTimeWorkRequestBuilder<InitialSetupWorker>()
                .addTag(INITIAL_SETUP_WORK_TAG)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                INITIAL_SETUP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                initialSetupRequest,
            )
            workersRegistered = true
        }
    }

    private const val INITIAL_SETUP_WORK_NAME = "ailm.initial.setup"
    private const val INITIAL_SETUP_WORK_TAG = "ailm_initial_setup"
}
