package com.codingcow.ikitty

/**
 * 一次城市级定位。
 *
 * 目前只有 IP 一个来源，所以精度就是"大概在哪个城市"：手机走运营商 NAT 时
 * 出口 IP 可能落在很远的城市，开着 VPN 时拿到的就是 VPN 的位置。
 * 它只能当背景信息用，不能当成真实位置来报。
 */
data class Place(
    val city: String = "",
    val region: String = "",
    val country: String = "",
    val fetchedAt: Long = 0L
) {
    /** 短地名：优先城市，其次是省份，最后是国家。 */
    val display: String get() = city.ifBlank { region }.ifBlank { country }

    val isEmpty: Boolean get() = display.isBlank()
}

/**
 * 定位来源的契约。
 *
 * 现在只有 IP 一种实现；以后要接系统定位，实现这个接口就行，聊天逻辑不用改。
 *
 * [cached] 刻意不发网络请求：发消息的关键路径上永远不能等定位，
 * 拿不到就只是少一行背景信息。
 */
interface LocationSource {

    /** 最近一次结果，可能为 null。 */
    fun cached(): Place?

    /** 缓存是否还没过期。 */
    fun isFresh(now: Long): Boolean

    /** 刷新一次。失败时保留旧结果，不抛异常。 */
    suspend fun refresh(now: Long)
}
