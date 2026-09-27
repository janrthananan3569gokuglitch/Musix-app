package com.example

import android.app.Application
import android.system.Os

class VaultApplication : Application() {
    companion object {
        init {
            silenceMesaLogs()
        }

        private fun silenceMesaLogs() {
            try {
                Os.setenv("MESA_LOG_FILE", "/dev/null", true)
                Os.setenv("MESA_LOG_LEVEL", "none", true)
                Os.setenv("MESA_DEBUG", "silent", true)
                Os.setenv("LIBGL_DEBUG", "quiet", true)
                Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
                Os.setenv("EGL_LOG_LEVEL", "fatal", true)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        silenceMesaLogs()
    }
}
