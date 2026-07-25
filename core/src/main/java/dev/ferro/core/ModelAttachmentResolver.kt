package dev.ferro.core

import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ToolAttachmentRef

fun interface ModelAttachmentResolver {
    suspend fun resolve(attachment: ToolAttachmentRef): ModelImageInput?

    companion object {
        val NONE = ModelAttachmentResolver { null }
    }
}
