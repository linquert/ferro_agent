package dev.ferro.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ToolAttachmentKind
import dev.ferro.contracts.ToolAttachmentRef
import dev.ferro.core.ModelAttachmentResolver
import java.io.File
import java.io.FileOutputStream

internal class ScreenArtifactStore(context: Context) {
    private val root = File(context.filesDir, "artifacts/screens").apply { mkdirs() }

    fun writePng(observationId: String, bitmap: Bitmap): ToolAttachmentRef {
        val file = fileFor(observationId)
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Failed to encode screenshot" }
            stream.fd.sync()
        }
        return ToolAttachmentRef(
            kind = ToolAttachmentKind.IMAGE,
            uri = "$SCHEME$observationId.png",
            mediaType = "image/png",
        )
    }

    fun resolve(ref: ToolAttachmentRef): File? {
        if (ref.kind != ToolAttachmentKind.IMAGE || ref.mediaType != "image/png") return null
        if (!ref.uri.startsWith(SCHEME)) return null
        val name = ref.uri.removePrefix(SCHEME)
        if (!SAFE_NAME.matches(name)) return null
        return File(root, name).takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES }
    }

    private fun fileFor(observationId: String): File {
        require(SAFE_ID.matches(observationId)) { "Invalid observation ID" }
        return File(root, "$observationId.png")
    }

    private companion object {
        const val SCHEME = "ferro-screen://"
        const val MAX_IMAGE_BYTES = 16L * 1024L * 1024L
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
        val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,100}\\.png")
    }
}

class AndroidModelAttachmentResolver(context: Context) : ModelAttachmentResolver {
    private val store = ScreenArtifactStore(context.applicationContext)

    override suspend fun resolve(attachment: ToolAttachmentRef): ModelImageInput? {
        val file = store.resolve(attachment) ?: return null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return ModelImageInput(
            imageUrl = "data:${attachment.mediaType};base64,$encoded",
        )
    }
}

class AndroidScreenArtifactReader(context: Context) {
    private val store = ScreenArtifactStore(context.applicationContext)

    fun readThumbnail(attachment: ToolAttachmentRef, maxDimensionPx: Int = 960): Bitmap? {
        require(maxDimensionPx > 0) { "Maximum dimension must be positive" }
        val file = store.resolve(attachment) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimensionPx || bounds.outHeight / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }
}
