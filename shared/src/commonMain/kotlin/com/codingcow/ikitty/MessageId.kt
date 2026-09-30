package com.codingcow.ikitty

import kotlin.random.Random

/**
 * 消息身份：时间前缀 + 随机后缀的 UUIDv4 形态字符串。
 *
 * 为什么不用纯随机：同步是"云端权威、本地为缓存"，两台设备的本地消息可能在一次同步里
 * 被一起推上去，服务端按到达顺序分配序号。纯随机会让同一秒内产生的消息在日志顺序与
 * 云端顺序之间来回抖动；带时间前缀让"离线期间产生的消息"至少有个稳定的相对次序。
 *
 * 为什么不用纯时间/UUIDv7：单调性要靠共享时钟或额外状态，而消息 id 的要求只是**唯一**。
 * 随机后缀（122 bit）已经把碰撞概率压到可以忽略，多要一个单调计数器只会多一处要同步的状态。
 *
 * 格式是标准 UUID 形态（8-4-4-4-12）：长度固定、只用 hex 与连字符，作为 JSONL 字段、
 * D1 主键的一部分都是安全的。
 */
fun newMessageId(now: Long = 0L, random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    // 前 6 字节放毫秒时间戳的低 48 位，与 UUIDv1/v7 的"时间在前"惯例一致。
    // now 为 0 时保留随机值：这个函数不依赖时钟可用。
    if (now != 0L) {
        for (index in 0 until 6) {
            bytes[index] = ((now shr (8 * (5 - index))) and 0xFF).toByte()
        }
    }
    // 版本位与变体位：让它在任何 UUID 解析器里都是合法的 v4。
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    val hex = hexLower(bytes)
    return buildString(36) {
        append(hex, 0, 8).append('-')
        append(hex, 8, 12).append('-')
        append(hex, 12, 16).append('-')
        append(hex, 16, 20).append('-')
        append(hex, 20, 32)
    }
}
