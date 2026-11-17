package np.com.manjultamrakar.peephole.ide

import np.com.manjultamrakar.peephole.ide.adb.AdbClient
import np.com.manjultamrakar.peephole.ide.adb.AdbDevice
import kotlin.test.Test
import kotlin.test.assertEquals

class AdbParsingTest {
    @Test
    fun parsesDevicesList() {
        val out = """
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R5CT1234ABC            unauthorized usb:1-1 transport_id:3
            192.168.1.20:5555      offline transport_id:4

        """.trimIndent()
        assertEquals(
            listOf(
                AdbDevice("emulator-5554", "device", "sdk_gphone64_arm64"),
                AdbDevice("R5CT1234ABC", "unauthorized", null),
                AdbDevice("192.168.1.20:5555", "offline", null),
            ),
            AdbClient.parseDevices(out),
        )
    }

    @Test
    fun findsInspectorSockets() {
        val out = """
            Num       RefCount Protocol Flags    Type St Inode Path
            0000000000000000: 00000002 00000000 00010000 0001 01 23456 @peephole_com.example.app
            0000000000000000: 00000002 00000000 00010000 0001 01 23457 @peephole_com.example.app:sync
            0000000000000000: 00000003 00000000 00000000 0001 03 23458 @peephole_com.example.app
            0000000000000000: 00000002 00000000 00010000 0001 01 11111 @webview_devtools_remote_123
            0000000000000000: 00000002 00000000 00010000 0001 01 11112 /dev/socket/zygote
        """.trimIndent()
        assertEquals(listOf("com.example.app", "com.example.app:sync"), AdbClient.parseInspectorSockets(out))
    }
}
