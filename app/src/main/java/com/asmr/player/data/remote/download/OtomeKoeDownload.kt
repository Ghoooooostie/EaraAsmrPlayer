package com.asmr.player.data.remote.download

import com.asmr.player.domain.model.Album

internal fun safeFolderName(input: String): String {
    return input.trim().ifEmpty { "album" }.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

internal fun safeFileName(input: String): String {
    return input.trim().ifEmpty { "track" }.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

/**
 * 将 OtomeKoe 在线音频（HLS/m3u8）作为一张专辑下载：封面 + 单条 .aac（由下载器从 TS 分片抽取）。
 * 返回的 [EnqueueDownloadBatchResult] 由调用方负责提示给用户。
 */
suspend fun DownloadManager.enqueueOtomeKoeAudio(
    streamUrl: String,
    title: String,
    coverUrl: String,
    rjCode: String = "",
    workId: String = "",
    circle: String = "",
    cv: String = "",
    tagsCsv: String = "",
): EnqueueDownloadBatchResult {
    val folderName = safeFolderName(rjCode.ifBlank { workId }.ifBlank { title })
    val items = mutableListOf<RelativeDownloadItem>()
    val trimmedCover = coverUrl.trim()
    if (trimmedCover.startsWith("http", ignoreCase = true)) {
        val ext = trimmedCover.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.length in 2..5 } ?: "jpg"
        items += RelativeDownloadItem(url = trimmedCover, relativePath = "cover.$ext")
    }
    items += RelativeDownloadItem(
        url = streamUrl,
        relativePath = "01_${safeFileName(title.ifBlank { "OtomeKoe" })}.aac"
    )
    return enqueueBatch(
        DownloadBatchRequest(
            albumDirectoryName = folderName,
            logicalTaskKey = "album:$folderName",
            items = items,
            taskSubtitle = title,
            albumTitle = title,
            albumCircle = circle,
            albumCv = cv,
            albumTagsCsv = tagsCsv,
            albumCoverUrl = coverUrl,
            albumWorkId = workId,
            albumRjCode = rjCode,
        ),
    )
}
