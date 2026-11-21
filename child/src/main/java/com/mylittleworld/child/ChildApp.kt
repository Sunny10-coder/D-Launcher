package com.mylittleworld.child

import android.app.Application
import android.util.Log
import com.mylittleworld.child.core.ChildContainer

class ChildApp : Application() {

    lateinit var container: ChildContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ChildContainer(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("ChildApp", "Uncaught error on ${thread.name}", error)
            // The launcher must always be recoverable: remember the crash so the
            // next launch can show a friendly recovery hint, then defer to the
            // platform default handler (which restarts the HOME activity).
            runCatching { container.recordCrash(error.javaClass.simpleName) }
            previous?.uncaughtException(thread, error)
        }
    }
}
