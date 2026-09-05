package top.wkbin.taixu.runtime.ftp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidFtpServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `serves standard banner and handles basic info commands`() {
        val root = tempFolder.newFolder("rootfs")
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, username = "root", password = "secretpassword"))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                val banner = reader.readLine()
                assertTrue(banner.startsWith("220 "))

                writer.write("SYST\r\n")
                writer.flush()
                val syst = reader.readLine()
                assertTrue(syst.startsWith("215 "))

                writer.write("FEAT\r\n")
                writer.flush()
                val featStart = reader.readLine()
                assertTrue(featStart.startsWith("211-Features:"))
                while (true) {
                    val line = reader.readLine()
                    if (line.startsWith("211 End")) break
                }

                writer.write("OPTS UTF8 ON\r\n")
                writer.flush()
                val opts = reader.readLine()
                assertTrue(opts.startsWith("200 "))

                writer.write("CLNT FileZilla\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("200 "))

                writer.write("MODE S\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("200 "))

                writer.write("STRU F\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("200 "))

                writer.write("ALLO 1024\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("200 "))

                writer.write("HELP\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("214 "))

                writer.write("QUIT\r\n")
                writer.flush()
                val quit = reader.readLine()
                assertTrue(quit.startsWith("221 "))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `authenticates user with password and rejects wrong password`() {
        val root = tempFolder.newFolder("rootfs")
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, username = "root", password = "correctpass"))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine() // banner

                // Test wrong password
                writer.write("USER root\r\n")
                writer.flush()
                assertEquals("331 User name okay, need password.", reader.readLine())

                writer.write("PASS wrongpass\r\n")
                writer.flush()
                assertEquals("530 Login incorrect.", reader.readLine())

                // PWD should fail before authentication
                writer.write("PWD\r\n")
                writer.flush()
                assertEquals("530 Please login with USER and PASS.", reader.readLine())

                // Test correct password
                writer.write("USER root\r\n")
                writer.flush()
                assertEquals("331 User name okay, need password.", reader.readLine())

                writer.write("PASS correctpass\r\n")
                writer.flush()
                assertEquals("230 User logged in, proceed.", reader.readLine())

                // PWD should succeed
                writer.write("PWD\r\n")
                writer.flush()
                assertEquals("257 \"/\" is current directory.", reader.readLine())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `supports anonymous login when enabled`() {
        val root = tempFolder.newFolder("rootfs")
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, anonymousEnabled = true))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine() // banner

                writer.write("USER anonymous\r\n")
                writer.flush()
                assertEquals("331 Guest login ok, send your complete e-mail address as password.", reader.readLine())

                writer.write("PASS guest@example.com\r\n")
                writer.flush()
                assertEquals("230 Anonymous access granted.", reader.readLine())

                writer.write("PWD\r\n")
                writer.flush()
                assertEquals("257 \"/\" is current directory.", reader.readLine())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `lists directory contents and downloads file via passive mode`() {
        val root = tempFolder.newFolder("rootfs")
        val testFile = File(root, "test.txt").apply { writeText("Hello TaiXu FTP!") }
        val subDir = File(root, "subdir").apply { mkdirs() }
        File(subDir, "nested.txt").apply { writeText("Nested content") }

        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, anonymousEnabled = true))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine() // banner
                writer.write("USER anonymous\r\n")
                writer.flush()
                reader.readLine()
                writer.write("PASS guest@\r\n")
                writer.flush()
                reader.readLine()

                // Test PASV - verify it returns 127.0.0.1 for local connection
                writer.write("PASV\r\n")
                writer.flush()
                val pasvResp = reader.readLine()
                assertTrue(pasvResp.startsWith("227 "))
                val (pasvIp, pasvPort) = parsePasvAddress(pasvResp)
                assertEquals("127.0.0.1", pasvIp)

                val dataSocket = Socket(pasvIp, pasvPort)
                writer.write("LIST\r\n")
                writer.flush()
                assertEquals("150 Opening ASCII mode data connection for file list.", reader.readLine())

                val listOutput = dataSocket.getInputStream().bufferedReader().readText()
                dataSocket.close()
                assertEquals("226 Transfer complete.", reader.readLine())
                assertTrue(listOutput.contains("test.txt"))
                assertTrue(listOutput.contains("subdir"))

                // Test CWD to subdir
                writer.write("CWD subdir\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("250 "))

                // Test PWD in subdir
                writer.write("PWD\r\n")
                writer.flush()
                assertEquals("257 \"/subdir\" is current directory.", reader.readLine())

                // Download nested.txt via EPSV
                writer.write("EPSV\r\n")
                writer.flush()
                val epsvResp = reader.readLine()
                assertTrue(epsvResp.startsWith("229 "))
                val epsvPort = parseEpsvPort(epsvResp)

                val retrSocket = Socket("127.0.0.1", epsvPort)
                writer.write("RETR nested.txt\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("150 "))

                val content = retrSocket.getInputStream().bufferedReader().readText()
                retrSocket.close()
                assertEquals("226 Transfer complete.", reader.readLine())
                assertEquals("Nested content", content)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handles file upload, directory creation, rename, and deletion`() {
        val root = tempFolder.newFolder("rootfs")
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, anonymousEnabled = true))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine()
                writer.write("USER anonymous\r\n")
                writer.flush()
                reader.readLine()
                writer.write("PASS guest@\r\n")
                writer.flush()
                reader.readLine()

                // MKD newdir
                writer.write("MKD myfolder\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("257 "))
                assertTrue(File(root, "myfolder").isDirectory)

                // STOR new file in myfolder
                writer.write("PASV\r\n")
                writer.flush()
                val (pasvIp, pasvPort) = parsePasvAddress(reader.readLine())
                val uploadSocket = Socket(pasvIp, pasvPort)
                writer.write("STOR myfolder/upload.txt\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("150 "))

                uploadSocket.getOutputStream().use { it.write("Uploaded data 12345".toByteArray()) }
                assertEquals("226 Transfer complete.", reader.readLine())
                assertEquals("Uploaded data 12345", File(root, "myfolder/upload.txt").readText())

                // RNFR / RNTO
                writer.write("RNFR myfolder/upload.txt\r\n")
                writer.flush()
                assertEquals("350 File exists, ready for destination name.", reader.readLine())

                writer.write("RNTO myfolder/renamed.txt\r\n")
                writer.flush()
                assertEquals("250 Rename successful.", reader.readLine())
                assertFalse(File(root, "myfolder/upload.txt").exists())
                assertTrue(File(root, "myfolder/renamed.txt").exists())

                // DELE
                writer.write("DELE myfolder/renamed.txt\r\n")
                writer.flush()
                assertEquals("250 File deleted successfully.", reader.readLine())
                assertFalse(File(root, "myfolder/renamed.txt").exists())

                // RMD
                writer.write("RMD myfolder\r\n")
                writer.flush()
                assertEquals("250 Directory removed.", reader.readLine())
                assertFalse(File(root, "myfolder").exists())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `readOnly mode rejects write and modify commands`() {
        val root = tempFolder.newFolder("rootfs")
        val file = File(root, "readonly.txt").apply { writeText("Protected") }
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, anonymousEnabled = true, readOnly = true))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine()
                writer.write("USER anonymous\r\n")
                writer.flush()
                reader.readLine()
                writer.write("PASS guest@\r\n")
                writer.flush()
                reader.readLine()

                // STOR rejected
                writer.write("STOR newfile.txt\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("550 "))

                // DELE rejected
                writer.write("DELE readonly.txt\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("550 "))
                assertTrue(file.exists())

                // MKD rejected
                writer.write("MKD newdir\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("550 "))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `prevents directory traversal beyond rootfs boundary`() {
        val root = tempFolder.newFolder("rootfs")
        val port = freePort()
        val server = AndroidFtpServer(FtpServerConfig(port = port, rootDirectory = root, anonymousEnabled = true))
        server.start()

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                reader.readLine()
                writer.write("USER anonymous\r\n")
                writer.flush()
                reader.readLine()
                writer.write("PASS guest@\r\n")
                writer.flush()
                reader.readLine()

                // CWD ../../../ should stay at /
                writer.write("CWD ../../../../../..\r\n")
                writer.flush()
                assertTrue(reader.readLine().startsWith("250 "))

                writer.write("PWD\r\n")
                writer.flush()
                assertEquals("257 \"/\" is current directory.", reader.readLine())
            }
        } finally {
            server.stop()
        }
    }

    private fun parsePasvAddress(pasvResponse: String): Pair<String, Int> {
        val start = pasvResponse.indexOf('(')
        val end = pasvResponse.indexOf(')')
        val nums = pasvResponse.substring(start + 1, end).split(",").map { it.trim().toInt() }
        val ip = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
        val port = nums[4] * 256 + nums[5]
        return ip to port
    }

    private fun parsePasvPort(pasvResponse: String): Int {
        return parsePasvAddress(pasvResponse).second
    }

    private fun parseEpsvPort(epsvResponse: String): Int {
        val start = epsvResponse.indexOf("(|||")
        val end = epsvResponse.indexOf("|)")
        return epsvResponse.substring(start + 4, end).toInt()
    }
}
