package com.codingcow.ikitty

/**
 * Android 侧把 [ImageStore] 接到同步的图片契约上。
 *
 * 做成适配器而不是让 `ImageStore` 直接实现 [SyncImageOps]：`ImageStore` 在 `:app` 里，
 * 它的职责是"从相册/相机收编图片"，而同步只关心"在不在、读出来、写进去"。
 * 让两者共用一个接口会把"图片来自哪里"和"图片怎么同步"绑死，接口一多就得两边一起改。
 */
class AndroidSyncImages(private val store: ImageStore) : SyncImageOps {
    override fun exists(imageId: String): Boolean = store.existsImage(imageId)

    override suspend fun read(imageId: String): ByteArray? = store.readImage(imageId)

    override suspend fun write(imageId: String, bytes: ByteArray): Boolean =
        store.writeSyncedImage(imageId, bytes)
}
