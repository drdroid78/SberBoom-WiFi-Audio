import ru.drdroid78.sberboom.audio.StarPtpClient

fun main() {
    val sync = StarPtpClient.packSync(0x0102030405060708L)
    check(sync.size == 11)
    check(sync[0].toInt() and 0xFF == 0x10)
    check(sync[1].toInt() == 0 && sync[2].toInt() == 0)
    check(sync.sliceArray(3..10).contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))

    val req = StarPtpClient.packDelayReq(0x1234)
    check(req.contentEquals(byteArrayOf(0x11, 0x34, 0x12)))

    val resp = StarPtpClient.packDelayResp(0x1234, 0x0102030405060708L)
    check(resp.size == 11)
    check(resp[0].toInt() and 0xFF == 0x12)
    check(resp[1].toInt() and 0xFF == 0x34)
    check(resp[2].toInt() and 0xFF == 0x12)
    check(resp.sliceArray(3..10).contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))

    println("StarPTP codec tests: 3 passed")
}
