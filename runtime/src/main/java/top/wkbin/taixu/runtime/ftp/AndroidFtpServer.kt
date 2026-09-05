package top.wkbin.taixu.runtime.ftp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Configuration options for [AndroidFtpServer]. */
data class FtpServerConfig(
    val port: Int = 2121,
    val rootDirectory: File,
    val workspaceDirectory: File? = null,
    val sdcardDirectory: File? = null,
    val username: String = "root",
    val password: String? = null,
    val anonymousEnabled: Boolean = false,
    val readOnly: Boolean = false,
) {
    init {
        require(port in 1024..65535) { "FTP 端口必须在 1024..65535 之间" }
    }
}

/**
 * Lightweight, robust pure-Kotlin FTP server compliant with RFC 959, RFC 3659, and RFC 2428.
 * Exposes the active Linux distribution's rootfs (and optional mounts like /workspace and /sdcard)
 * directly over standard FTP for external clients (FileZilla, Windows Explorer, etc.).
 */
class AndroidFtpServer(
    private val config: FtpServerConfig,
    private val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val activeSessions = ConcurrentHashMap.newKeySet<FtpSession>()
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = bindServerSocketWithRetry(config.port)
        serverSocket = server
        onLog("FTP 服务已在端口 ${config.port} 启动")

        scope.launch {
            try {
                while (isActive && running.get()) {
                    val clientSocket = try {
                        server.accept()
                    } catch (se: SocketException) {
                        break
                    }
                    clientSocket.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
                    val session = FtpSession(clientSocket, config, onLog) { session ->
                        activeSessions.remove(session)
                    }
                    activeSessions.add(session)
                    scope.launch {
                        try {
                            session.handle()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            onLog("客户端异常: ${t.message}")
                        } finally {
                            activeSessions.remove(session)
                            session.close()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                stop()
            }
        }
    }

    private fun bindServerSocketWithRetry(port: Int): ServerSocket {
        var lastException: Throwable? = null
        val maxAttempts = 6
        val delays = listOf(0L, 100L, 200L, 400L, 800L, 1200L)

        for (attempt in 0 until maxAttempts) {
            if (delays[attempt] > 0) {
                try {
                    Thread.sleep(delays[attempt])
                } catch (_: InterruptedException) {
                    // Ignore interruption and proceed with attempt
                }
            }
            var s: ServerSocket? = null
            try {
                s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port), 50)
                return s
            } catch (e: Exception) {
                lastException = e
                runCatching { s?.close() }
                if (e is SecurityException || e is IllegalArgumentException) {
                    throw e
                }
            }
        }
        val msg = lastException?.message ?: "端口 $port 绑定失败"
        if (msg.contains("EADDRINUSE", ignoreCase = true) || msg.contains("already in use", ignoreCase = true)) {
            throw java.net.BindException("FTP 端口 $port 正在被占用或尚未完全释放，请稍后再试或更换端口。($msg)")
        } else {
            throw (lastException ?: java.net.BindException("FTP 端口 $port 启动失败：$msg"))
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val server = serverSocket
        serverSocket = null
        runCatching { server?.close() }
        activeSessions.forEach { session ->
            runCatching { session.close() }
        }
        activeSessions.clear()
        scope.cancel()
        onLog("FTP 服务已停止")
    }

    companion object {
        private const val CLIENT_SOCKET_TIMEOUT_MS = 300_000 // 5 minutes
    }
}

/** Handles a single FTP client control connection and its associated data connection. */
internal class FtpSession(
    private val socket: Socket,
    private val config: FtpServerConfig,
    private val onLog: (String) -> Unit,
    private val onClose: (FtpSession) -> Unit,
) {
    private val clientIp: String = socket.inetAddress?.hostAddress ?: "未知"
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    private var authenticated = false
    private var usernameAttempt: String? = null
    private var currentVirtualDir: String = "/"
    private var transferType: String = "I" // Binary default
    private var passiveServer: ServerSocket? = null
    private var activeDataAddress: InetSocketAddress? = null
    private var renameFrom: File? = null
    private var restOffset: Long = 0L
    private val closed = AtomicBoolean(false)

    suspend fun handle() = withContext(Dispatchers.IO) {
        sendResponse(220, "TaiXu Linux FTP Server ready.")
        onLog("[$clientIp] 客户端已连接")

        while (!closed.get()) {
            val line = try {
                reader.readLine()
            } catch (e: SocketTimeoutException) {
                sendResponse(421, "Timeout.")
                break
            } catch (e: SocketException) {
                break
            } ?: break

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val spaceIdx = trimmed.indexOf(' ')
            val command = (if (spaceIdx != -1) trimmed.substring(0, spaceIdx) else trimmed).uppercase(Locale.ROOT)
            val argument = if (spaceIdx != -1) trimmed.substring(spaceIdx + 1).trim() else ""

            processCommand(command, argument)
        }
    }

    private fun processCommand(command: String, arg: String) {
        when (command) {
            "USER" -> handleUser(arg)
            "PASS" -> handlePass(arg)
            "AUTH" -> sendResponse(500, "AUTH not supported, use plain FTP.")
            "FEAT" -> handleFeat()
            "OPTS" -> handleOpts(arg)
            "CLNT", "CSID" -> sendResponse(200, "OK")
            "MODE" -> handleMode(arg)
            "STRU" -> handleStru(arg)
            "ALLO" -> sendResponse(200, "ALLO command ignored.")
            "HELP" -> sendResponse(214, "TaiXu Linux FTP Server.")
            "SYST" -> sendResponse(215, "UNIX Type: L8")
            "NOOP" -> sendResponse(200, "NOOP ok.")
            "QUIT" -> {
                sendResponse(221, "Goodbye.")
                close()
            }
            "PWD", "XPWD" -> {
                if (requireAuth()) {
                    sendResponse(257, "\"$currentVirtualDir\" is current directory.")
                }
            }
            "CWD", "XCWD" -> handleCwd(arg)
            "CDUP", "XCUP" -> handleCdup()
            "TYPE" -> {
                if (requireAuth()) {
                    handleType(arg)
                }
            }
            "PASV" -> handlePasv()
            "EPSV" -> handleEpsv()
            "PORT" -> handlePort(arg)
            "EPRT" -> handleEprt(arg)
            "LIST" -> handleList(arg, detailed = true)
            "NLST" -> handleList(arg, detailed = false)
            "MLSD" -> handleMlsd(arg)
            "MLST" -> handleMlst(arg)
            "SIZE" -> handleSize(arg)
            "MDTM" -> handleMdtm(arg)
            "RETR" -> handleRetr(arg)
            "STOR" -> handleStor(arg, append = false)
            "APPE" -> handleStor(arg, append = true)
            "REST" -> handleRest(arg)
            "DELE" -> handleDele(arg)
            "MKD", "XMKD" -> handleMkd(arg)
            "RMD", "XRMD" -> handleRmd(arg)
            "RNFR" -> handleRnfr(arg)
            "RNTO" -> handleRnto(arg)
            "ABOR" -> sendResponse(226, "Abort successful.")
            "SITE" -> sendResponse(200, "SITE command ignored.")
            "STAT" -> sendResponse(211, "TaiXu FTP server status: OK")
            else -> sendResponse(502, "Command not implemented.")
        }
    }

    private fun requireAuth(): Boolean {
        if (!authenticated) {
            sendResponse(530, "Please login with USER and PASS.")
            return false
        }
        return true
    }

    private fun handleUser(user: String) {
        usernameAttempt = user.trim()
        if (config.anonymousEnabled && (user.equals("anonymous", ignoreCase = true) || user.equals("ftp", ignoreCase = true))) {
            sendResponse(331, "Guest login ok, send your complete e-mail address as password.")
        } else {
            sendResponse(331, "User name okay, need password.")
        }
    }

    private fun handlePass(pass: String) {
        val user = usernameAttempt?.ifBlank { null } ?: config.username.ifBlank { "root" }
        if (config.anonymousEnabled && (user.equals("anonymous", ignoreCase = true) || user.equals("ftp", ignoreCase = true))) {
            authenticated = true
            sendResponse(230, "Anonymous access granted.")
            onLog("[$clientIp] 匿名登录成功")
            return
        }

        val expectedUser = config.username.ifBlank { "root" }
        val passwordMatches = config.password.isNullOrBlank() || pass == config.password
        val userMatches = user.equals(expectedUser, ignoreCase = true) || user.equals("root", ignoreCase = true)

        if (userMatches && passwordMatches) {
            authenticated = true
            sendResponse(230, "User logged in, proceed.")
            onLog("[$clientIp] 用户 $user 认证成功")
        } else {
            authenticated = false
            sendResponse(530, "Login incorrect.")
            onLog("[$clientIp] 用户 $user 密码错误")
        }
    }

    private fun handleMode(arg: String) {
        if (arg.equals("S", ignoreCase = true) || arg.isBlank()) {
            sendResponse(200, "Mode set to S (Stream).")
        } else {
            sendResponse(504, "Mode $arg not supported, only S (Stream) supported.")
        }
    }

    private fun handleStru(arg: String) {
        if (arg.equals("F", ignoreCase = true) || arg.isBlank()) {
            sendResponse(200, "Structure set to F (File).")
        } else {
            sendResponse(504, "Structure $arg not supported, only F (File) supported.")
        }
    }

    private fun handleFeat() {
        val features = listOf(
            "211-Features:",
            " UTF8",
            " SIZE",
            " MDTM",
            " MLST type*;size*;modify*;perms*;",
            " MLSD",
            " PASV",
            " EPSV",
            " REST STREAM",
            "211 End",
        )
        sendRaw(features.joinToString("\r\n") + "\r\n")
    }

    private fun handleOpts(arg: String) {
        if (arg.uppercase(Locale.ROOT).startsWith("UTF8")) {
            sendResponse(200, "UTF8 mode enabled.")
        } else {
            sendResponse(501, "Unknown OPTS option.")
        }
    }

    private fun handleType(arg: String) {
        when (arg.uppercase(Locale.ROOT)) {
            "A", "A N" -> {
                transferType = "A"
                sendResponse(200, "Type set to A (ASCII).")
            }
            "I", "L 8", "L 7" -> {
                transferType = "I"
                sendResponse(200, "Type set to I (Binary).")
            }
            else -> sendResponse(504, "Type not supported.")
        }
    }

    private fun handleCwd(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists() && file.isDirectory) {
            currentVirtualDir = targetVirtual
            sendResponse(250, "Directory successfully changed to \"$currentVirtualDir\".")
        } else {
            sendResponse(550, "Failed to change directory.")
        }
    }

    private fun handleCdup() {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, "..")
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists() && file.isDirectory) {
            currentVirtualDir = targetVirtual
            sendResponse(250, "Directory successfully changed to \"$currentVirtualDir\".")
        } else {
            sendResponse(550, "Failed to change directory.")
        }
    }

    private fun handlePasv() {
        if (!requireAuth()) return
        closePassiveServer()
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            server.soTimeout = DATA_TIMEOUT_MS
            passiveServer = server

            val localAddr = getLocalIpAddress()
            val port = server.localPort
            val p1 = port / 256
            val p2 = port % 256
            val ipParts = localAddr.split(".").map { it.trim().toInt() }
            sendResponse(227, "Entering Passive Mode (${ipParts[0]},${ipParts[1]},${ipParts[2]},${ipParts[3]},$p1,$p2).")
        } catch (e: Throwable) {
            sendResponse(425, "Cannot open passive connection: ${e.message}")
        }
    }

    private fun handleEpsv() {
        if (!requireAuth()) return
        closePassiveServer()
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            server.soTimeout = DATA_TIMEOUT_MS
            passiveServer = server
            sendResponse(229, "Entering Extended Passive Mode (|||${server.localPort}|).")
        } catch (e: Throwable) {
            sendResponse(425, "Cannot open EPSV connection: ${e.message}")
        }
    }

    private fun handlePort(arg: String) {
        if (!requireAuth()) return
        closePassiveServer()
        val parts = arg.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 6) {
            sendResponse(501, "Illegal PORT command.")
            return
        }
        val ip = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
        val port = parts[4] * 256 + parts[5]
        activeDataAddress = InetSocketAddress(ip, port)
        sendResponse(200, "PORT command successful.")
    }

    private fun handleEprt(arg: String) {
        if (!requireAuth()) return
        closePassiveServer()
        val del = if (arg.isNotEmpty()) arg[0] else '|'
        val parts = arg.split(del).filter { it.isNotEmpty() }
        if (parts.size < 3) {
            sendResponse(501, "Illegal EPRT command.")
            return
        }
        val ip = parts[1]
        val port = parts[2].toIntOrNull() ?: run {
            sendResponse(501, "Invalid port in EPRT.")
            return
        }
        activeDataAddress = InetSocketAddress(ip, port)
        sendResponse(200, "EPRT command successful.")
    }

    private fun openDataSocket(): Socket? {
        val pasv = passiveServer
        if (pasv != null) {
            return try {
                pasv.accept().also { closePassiveServer() }
            } catch (e: Throwable) {
                closePassiveServer()
                null
            }
        }
        val active = activeDataAddress
        if (active != null) {
            return try {
                val s = Socket()
                s.connect(active, DATA_TIMEOUT_MS)
                activeDataAddress = null
                s
            } catch (e: Throwable) {
                activeDataAddress = null
                null
            }
        }
        return null
    }

    private fun closePassiveServer() {
        runCatching { passiveServer?.close() }
        passiveServer = null
    }

    private fun handleList(arg: String, detailed: Boolean) {
        if (!requireAuth()) return
        val targetPath = if (arg.startsWith("-")) "" else arg
        val targetVirtual = resolveVirtualPath(currentVirtualDir, targetPath)
        val file = resolveFile(targetVirtual)

        val dataSocket = openDataSocket()
        if (dataSocket == null) {
            sendResponse(425, "Can't open data connection.")
            return
        }

        sendResponse(150, "Opening ASCII mode data connection for file list.")
        try {
            dataSocket.use { ds ->
                val out = BufferedWriter(OutputStreamWriter(ds.getOutputStream(), Charsets.UTF_8))
                val entries = if (file == null || !file.exists()) {
                    emptyList()
                } else if (file.isDirectory) {
                    val list = file.listFiles()?.toList().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    // If listing root "/", ensure mounted virtual dirs like workspace/sdcard are visible
                    if (targetVirtual == "/") {
                        val names = list.map { it.name }.toSet()
                        val extra = mutableListOf<File>()
                        if (config.workspaceDirectory?.exists() == true && "workspace" !in names) {
                            extra.add(config.workspaceDirectory)
                        }
                        if (config.sdcardDirectory?.exists() == true && "sdcard" !in names) {
                            extra.add(config.sdcardDirectory)
                        }
                        (list + extra).sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    } else {
                        list
                    }
                } else {
                    listOf(file)
                }

                val now = System.currentTimeMillis()
                val dateFormatRecent = SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH)
                val dateFormatOlder = SimpleDateFormat("MMM dd  yyyy", Locale.ENGLISH)

                for (item in entries) {
                    if (detailed) {
                        val isDir = item.isDirectory
                        val isSymlink = Files.isSymbolicLink(item.toPath())
                        val typeChar = if (isSymlink) 'l' else if (isDir) 'd' else '-'
                        val perms = if (isDir) "rwxr-xr-x" else "rw-r--r--"
                        val size = if (isDir) 4096L else item.length()
                        val mtime = item.lastModified()
                        val dateStr = if (now - mtime < 180L * 24 * 3600 * 1000) {
                            dateFormatRecent.format(Date(mtime))
                        } else {
                            dateFormatOlder.format(Date(mtime))
                        }
                        val line = "$typeChar$perms   1 root root ${size.toString().padStart(12)} $dateStr ${item.name}"
                        out.write(line + "\r\n")
                    } else {
                        out.write(item.name + "\r\n")
                    }
                }
                out.flush()
            }
            sendResponse(226, "Transfer complete.")
        } catch (e: Throwable) {
            sendResponse(426, "Data connection failed: ${e.message}")
        }
    }

    private fun handleMlsd(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)

        val dataSocket = openDataSocket()
        if (dataSocket == null) {
            sendResponse(425, "Can't open data connection.")
            return
        }

        sendResponse(150, "Opening data connection for MLSD.")
        try {
            dataSocket.use { ds ->
                val out = BufferedWriter(OutputStreamWriter(ds.getOutputStream(), Charsets.UTF_8))
                val entries = if (file == null || !file.exists() || !file.isDirectory) {
                    emptyList()
                } else {
                    file.listFiles()?.toList().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                }
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

                for (item in entries) {
                    val isDir = item.isDirectory
                    val type = if (isDir) "dir" else "file"
                    val size = if (isDir) 0L else item.length()
                    val modify = sdf.format(Date(item.lastModified()))
                    val perms = if (isDir) "el" else "r"
                    val fact = "type=$type;size=$size;modify=$modify;perms=$perms; ${item.name}"
                    out.write(fact + "\r\n")
                }
                out.flush()
            }
            sendResponse(226, "Transfer complete.")
        } catch (e: Throwable) {
            sendResponse(426, "Data connection failed: ${e.message}")
        }
    }

    private fun handleMlst(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file == null || !file.exists()) {
            sendResponse(550, "File or directory does not exist.")
            return
        }
        val isDir = file.isDirectory
        val type = if (isDir) "dir" else "file"
        val size = if (isDir) 0L else file.length()
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val modify = sdf.format(Date(file.lastModified()))
        val fact = "type=$type;size=$size;modify=$modify;perms=${if (isDir) "el" else "r"}; $targetVirtual"

        sendRaw("250- Listing $targetVirtual\r\n $fact\r\n250 End.\r\n")
    }

    private fun handleSize(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists() && file.isFile) {
            sendResponse(213, file.length().toString())
        } else {
            sendResponse(550, "Could not get file size.")
        }
    }

    private fun handleMdtm(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists()) {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            sendResponse(213, sdf.format(Date(file.lastModified())))
        } else {
            sendResponse(550, "Could not get file modification time.")
        }
    }

    private fun handleRest(arg: String) {
        if (!requireAuth()) return
        val offset = arg.toLongOrNull()
        if (offset != null && offset >= 0) {
            restOffset = offset
            sendResponse(350, "Restarting at $offset. Send STORE or RETRIEVE to initiate transfer.")
        } else {
            sendResponse(501, "Invalid REST parameter.")
        }
    }

    private fun handleRetr(arg: String) {
        if (!requireAuth()) return
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file == null || !file.exists() || !file.isFile) {
            sendResponse(550, "File not found or is a directory.")
            restOffset = 0L
            return
        }

        val dataSocket = openDataSocket()
        if (dataSocket == null) {
            sendResponse(425, "Can't open data connection.")
            restOffset = 0L
            return
        }

        val offset = restOffset
        restOffset = 0L
        sendResponse(150, "Opening BINARY mode data connection for ${file.name} (${file.length()} bytes).")
        onLog("[$clientIp] 开始下载: $targetVirtual (偏移: $offset)")

        try {
            dataSocket.use { ds ->
                val output = BufferedOutputStream(ds.getOutputStream())
                FileInputStream(file).use { input ->
                    if (offset > 0) input.skip(offset)
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            sendResponse(226, "Transfer complete.")
            onLog("[$clientIp] 下载完成: $targetVirtual")
        } catch (e: Throwable) {
            sendResponse(426, "Data connection failed: ${e.message}")
        }
    }

    private fun handleStor(arg: String, append: Boolean) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file == null) {
            sendResponse(550, "Invalid destination path.")
            return
        }
        if (file.isDirectory) {
            sendResponse(550, "Destination is a directory.")
            return
        }

        val dataSocket = openDataSocket()
        if (dataSocket == null) {
            sendResponse(425, "Can't open data connection.")
            return
        }

        sendResponse(150, "Opening BINARY mode data connection for ${file.name}.")
        onLog("[$clientIp] 开始上传: $targetVirtual")

        try {
            file.parentFile?.mkdirs()
            dataSocket.use { ds ->
                val input = BufferedInputStream(ds.getInputStream())
                FileOutputStream(file, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            sendResponse(226, "Transfer complete.")
            onLog("[$clientIp] 上传完成: $targetVirtual (${file.length()} bytes)")
        } catch (e: Throwable) {
            sendResponse(426, "Upload failed: ${e.message}")
        }
    }

    private fun handleDele(arg: String) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists() && file.isFile) {
            if (file.delete()) {
                sendResponse(250, "File deleted successfully.")
                onLog("[$clientIp] 删除文件: $targetVirtual")
            } else {
                sendResponse(550, "Could not delete file.")
            }
        } else {
            sendResponse(550, "File not found or is a directory.")
        }
    }

    private fun handleMkd(arg: String) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && !file.exists() && file.mkdirs()) {
            sendResponse(257, "\"$targetVirtual\" directory created.")
            onLog("[$clientIp] 创建目录: $targetVirtual")
        } else {
            sendResponse(550, "Failed to create directory.")
        }
    }

    private fun handleRmd(arg: String) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        if (targetVirtual == "/") {
            sendResponse(550, "Cannot remove root directory.")
            return
        }
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists() && file.isDirectory) {
            if (file.delete()) {
                sendResponse(250, "Directory removed.")
                onLog("[$clientIp] 删除目录: $targetVirtual")
            } else {
                sendResponse(550, "Directory not empty or cannot be deleted.")
            }
        } else {
            sendResponse(550, "Directory not found.")
        }
    }

    private fun handleRnfr(arg: String) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val file = resolveFile(targetVirtual)
        if (file != null && file.exists()) {
            renameFrom = file
            sendResponse(350, "File exists, ready for destination name.")
        } else {
            renameFrom = null
            sendResponse(550, "File or directory not found.")
        }
    }

    private fun handleRnto(arg: String) {
        if (!requireAuth()) return
        if (config.readOnly) {
            sendResponse(550, "Permission denied: Server is in read-only mode.")
            return
        }
        val from = renameFrom
        if (from == null) {
            sendResponse(503, "Bad sequence of commands (RNFR first).")
            return
        }
        renameFrom = null
        val targetVirtual = resolveVirtualPath(currentVirtualDir, arg)
        val target = resolveFile(targetVirtual)
        if (target != null && !target.exists() && from.renameTo(target)) {
            sendResponse(250, "Rename successful.")
            onLog("[$clientIp] 重命名: ${from.name} -> $targetVirtual")
        } else {
            sendResponse(550, "Rename failed.")
        }
    }

    private fun resolveVirtualPath(current: String, input: String): String {
        val raw = if (input.startsWith("/")) input else "$current/$input"
        val parts = raw.split("/").filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
            } else {
                resolved.add(part)
            }
        }
        return "/" + resolved.joinToString("/")
    }

    private fun resolveFile(virtualPath: String): File? {
        val trimmed = virtualPath.trim().removePrefix("/")
        val targetFile = if (trimmed.startsWith("workspace/") || trimmed == "workspace") {
            val ws = config.workspaceDirectory ?: File(config.rootDirectory, "workspace")
            val sub = trimmed.removePrefix("workspace").removePrefix("/")
            if (sub.isEmpty()) ws else File(ws, sub)
        } else if (trimmed.startsWith("sdcard/") || trimmed == "sdcard") {
            val sd = config.sdcardDirectory ?: File("/storage/emulated/0")
            val sub = trimmed.removePrefix("sdcard").removePrefix("/")
            if (sub.isEmpty()) sd else File(sd, sub)
        } else {
            if (trimmed.isEmpty()) config.rootDirectory else File(config.rootDirectory, trimmed)
        }

        // Sandbox escape protection: ensure target is within allowed directory
        return try {
            val canonicalTarget = targetFile.canonicalFile
            val allowedRoots = listOfNotNull(
                config.rootDirectory.canonicalFile,
                config.workspaceDirectory?.canonicalFile,
                config.sdcardDirectory?.canonicalFile,
            )
            val isContained = allowedRoots.any { root ->
                canonicalTarget.absolutePath.startsWith(root.absolutePath)
            }
            if (isContained) targetFile else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getLocalIpAddress(): String {
        return runCatching {
            val localAddr = socket.localAddress
            val rawHost = localAddr?.hostAddress?.removePrefix("/") ?: ""
            val cleanHost = rawHost.substringAfterLast(":")
            if (isValidIpv4(cleanHost) && cleanHost != "0.0.0.0") {
                return@runCatching cleanHost
            }
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?.hostAddress ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun sendResponse(code: Int, message: String) {
        sendRaw("$code $message\r\n")
    }

    private fun sendRaw(raw: String) {
        try {
            if (!socket.isClosed) {
                writer.write(raw)
                writer.flush()
            }
        } catch (_: Throwable) {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closePassiveServer()
        runCatching {
            socket.setSoLinger(true, 0)
            socket.close()
        }
        onClose(this)
    }

    companion object {
        private const val DATA_TIMEOUT_MS = 30_000
    }
}
