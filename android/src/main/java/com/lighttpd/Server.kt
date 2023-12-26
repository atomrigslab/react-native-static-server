package com.lighttpd

import android.util.Log
import com.drpogodin.reactnativestaticserver.Errors
import java.util.function.BiConsumer

/**
 * Java interface for native Lighttpd server running in a dedicated Thread.
 * Use Thread methods to operate the server:
 * .start() - To launch it;
 * .isActive() - To check its current status;
 * .interrupt() - To gracefully terminate it.
 * Also, `signalConsumer` callback provided to Server instance upon construction
 * will provide you with server state change Signals.
 *
 * As Java Thread instances may be executed only once, to restart the server
 * you should create and launch a new instance of Server object.
 *
 * BEWARE: With the current Lighttpd implementation,
 * and the way it is integrated into this library, it is not safe to run
 * multiple server instances in parallel! Be sure the previous server instance,
 * if any, has terminated or crashed before launching a new one!
 */
class Server(
        var id: Double,
        var configPath: String,
        var errlogPath: String,
        private val signalConsumer: BiConsumer<String, String?>
) : Thread() {
    override fun interrupt() {
        Log.i(LOGTAG, "Server.interrupt() triggered")
        gracefulShutdown()
        // No need to call super.interrupt() here, the native this.shutdown()
        // method will set within the native layer necessary flags that will
        // cause graceful termination of the thread.
    }

    private external fun gracefulShutdown()
    external fun launch(configPath: String, errlogPath: String): Int
    override fun run() {
        Log.i(LOGTAG, "Server.run() triggered")
        if (activeServer != null) {
            val msg = "Another Server instance is active"
            Log.e(LOGTAG, msg)
            signalConsumer.accept(CRASHED, msg)
            return
        }
        try {
            activeServer = this
            val res = launch(configPath, errlogPath)
            if (res != 0) {
                throw Exception("Native server exited with status $res")
            }

            // NOTE: It MUST BE set "null" prior to sending out TERMINATED or CRASHED
            // signals.
            activeServer = null
            Log.i(LOGTAG, "Server terminated gracefully")
            signalConsumer.accept(TERMINATED, null)
        } catch (error: Exception) {
            activeServer = null
            Log.e(LOGTAG, "Server crashed", error)
            signalConsumer.accept(CRASHED, error.message)
        }
    }

    companion object {
        init {
            System.loadLibrary("lighttpd")
        }

        // NOTE: Tried to use enum, but was not able to make it work with JNI.
        const val CRASHED = "CRASHED"
        const val LAUNCHED = "LAUNCHED"
        const val TERMINATED = "TERMINATED"
        private var activeServer: Server? = null
        private const val LOGTAG = Errors.LOGTAG

        // NOTE: @JvmStatic annotation is needed to make this function
        // visible via JNI in C code.
        @JvmStatic fun onLaunchedCallback() {
            activeServer!!.signalConsumer.accept(LAUNCHED, null)
        }
    }
}
