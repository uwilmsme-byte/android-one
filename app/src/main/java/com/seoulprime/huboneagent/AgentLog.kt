package com.seoulprime.huboneagent

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object AgentLog {
    private const val MAX_LOG_BYTES = 1_000_000L
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    private var crashHandlerInstalled = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            if (!crashHandlerInstalled) {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    write("FATAL", "UncaughtException", "thread=${thread.name}", error)
                    if (previous != null) {
                        previous.uncaughtException(thread, error)
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(10)
                    }
                }
                crashHandlerInstalled = true
            }
        }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        info("Application", "process_started version=$version sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
    }

    fun info(tag: String, message: String) = write("INFO", tag, message)

    fun warning(tag: String, message: String, error: Throwable? = null) =
        write("WARN", tag, message, error)

    fun logFile(context: Context): File = synchronized(lock) {
        val directory = File(context.filesDir, "logs")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create app log directory")
        }
        val file = File(directory, "hubone-agent.log")
        if (!file.exists()) file.writeText("HUBONE Agent diagnostic log\n", Charsets.UTF_8)
        file
    }

    private fun write(level: String, tag: String, message: String, error: Throwable? = null) {
        val context = appContext ?: return
        synchronized(lock) {
            try {
                val file = logFile(context)
                if (file.length() > MAX_LOG_BYTES) {
                    val previous = File(file.parentFile, "hubone-agent.log.1")
                    if (previous.exists()) previous.delete()
                    file.renameTo(previous)
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val output = buildString {
                    append(timestamp).append(' ').append(level).append('/').append(tag)
                        .append(": ").append(message).append('\n')
                    if (error != null) {
                        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                        append(stack)
                        if (!stack.endsWith('\n')) append('\n')
                    }
                }
                FileOutputStream(file, true).use { it.write(output.toByteArray(Charsets.UTF_8)) }
            } catch (writeError: Exception) {
                Log.e("HubOneAgentLog", "Unable to write app diagnostic log", writeError)
            }
        }
    }
}
