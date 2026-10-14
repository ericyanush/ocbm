package zeno.gmccpa

/** Routes the imported receiver's diagnostics through Carlink's persistent rolling log. */
object ProbeLog {
    const val TAG = "NETPROBE"

    class Logger internal constructor(
        private val delegate: com.carlink.logging.ProbeLog.Logger?,
    ) {
        fun i(msg: String) { delegate?.i(msg) }
        fun w(msg: String) { delegate?.w(msg) }
        fun e(msg: String) { delegate?.e(msg) }
    }

    fun sub(name: String): Logger = Logger(com.carlink.logging.ProbeLog.sub("CPRX_${name.uppercase()}"))

    fun silent(): Logger = Logger(null)

    fun raw(msg: String) = com.carlink.logging.ProbeLog.raw("[CPRX ] $msg")

    fun banner(msg: String) = com.carlink.logging.ProbeLog.banner("CPRX: $msg")
}
