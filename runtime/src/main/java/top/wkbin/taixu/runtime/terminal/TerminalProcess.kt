package top.wkbin.taixu.runtime.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import top.wkbin.taixu.runtime.shell.InteractiveLaunchSpec

/** Native rendering remains explicit; tests can use a process without starting a PTY. */
interface TerminalProcess {
    val session: TerminalSession
    val isAlive: Boolean
    fun finish()
    fun write(data: ByteArray)
}

fun interface TerminalProcessFactory {
    fun create(launch: InteractiveLaunchSpec, client: TerminalSessionClientRouter): TerminalProcess
}

class TermuxTerminalProcessFactory : TerminalProcessFactory {
    override fun create(launch: InteractiveLaunchSpec, client: TerminalSessionClientRouter): TerminalProcess {
        Log.i("TaiXuTerminal", "createSession cwd=${launch.workingDirectory} argv0=${launch.executable} " +
            "argc=${launch.arguments.size} env=${launch.environment.size}")
        val terminal = TerminalSession(launch.executable, launch.workingDirectory, launch.arguments,
            launch.environment, 2000, client)
        return object : TerminalProcess {
            override val session = terminal
            override val isAlive get() = terminal.isRunning
            override fun finish() = terminal.finishIfRunning()
            override fun write(data: ByteArray) = terminal.write(data, 0, data.size)
        }
    }
}
