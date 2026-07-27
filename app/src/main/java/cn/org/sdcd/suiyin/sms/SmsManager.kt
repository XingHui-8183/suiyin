package cn.org.sdcd.suiyin.sms

data class SmsMessageItem(
    val id: Long,
    val sender: String,
    val body: String,
    val time: Long,
    val isFromRemote: Boolean = true,
    /** 是否为我自己发出的回复 */
    val isOutgoing: Boolean = false
)

object SmsManager {

    private val smsList = mutableListOf<SmsMessageItem>()
    private val listeners = mutableListOf<(List<SmsMessageItem>) -> Unit>()

    fun getSmsList(): List<SmsMessageItem> = smsList.toList()

    fun addSms(sender: String, body: String, time: Long, isFromRemote: Boolean = true) {
        val item = SmsMessageItem(
            id = System.currentTimeMillis(),
            sender = sender,
            body = body,
            time = time,
            isFromRemote = isFromRemote,
            isOutgoing = !isFromRemote
        )
        smsList.add(0, item)
        notifyListeners()
    }

    fun addListener(listener: (List<SmsMessageItem>) -> Unit) {
        listeners.add(listener)
        listener(smsList.toList())
    }

    fun removeListener(listener: (List<SmsMessageItem>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val copy = smsList.toList()
        listeners.forEach { it(copy) }
    }

    fun clear() {
        smsList.clear()
        notifyListeners()
    }
}
