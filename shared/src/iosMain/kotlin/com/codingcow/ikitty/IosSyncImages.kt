package com.codingcow.ikitty

/**
 * iOS 侧把 [IosImageStore] 接到同步的图片契约上。
 *
 * 与 Android 侧同样的理由：图片存储的职责是"收编与编码图片"，同步只关心
 * "在不在、读出来、写进去"。用适配器把两者分开，任何一边改接口都不会牵动另一边。
 */
class IosSyncImages(private val store: IosImageStore) : SyncImageOps {
    override fun exists(imageId: String): Boolean = store.exists(imageId)

    override suspend fun read(imageId: String): ByteArray? = store.read(imageId)

    override suspend fun write(imageId: String, bytes: ByteArray): Boolean = store.write(imageId, bytes)
}
