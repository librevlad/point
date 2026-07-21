package com.point.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import com.point.core.model.ObjectKind

/** Resolves a [com.point.core.model.Bubble.icon] key to a vector. */
fun bubbleIcon(key: String): ImageVector = when (key) {
    "share" -> Icons.Filled.Share
    "save" -> Icons.Filled.Save
    "pdf" -> Icons.Filled.PictureAsPdf
    "compress" -> Icons.Filled.Compress
    "unzip" -> Icons.Filled.FolderZip
    "translate" -> Icons.Filled.Translate
    "ai" -> Icons.Filled.AutoAwesome
    "link" -> Icons.Filled.Link
    "office" -> Icons.Filled.Article
    else -> Icons.Filled.Bolt
}

/** Icon representing the current object's kind, shown in the screen header. */
fun kindIcon(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> Icons.Filled.Image
    ObjectKind.TEXT -> Icons.Filled.Description
    ObjectKind.PDF -> Icons.Filled.PictureAsPdf
    ObjectKind.ZIP -> Icons.Filled.FolderZip
    ObjectKind.OFFICE -> Icons.Filled.Article
    ObjectKind.URL -> Icons.Filled.Link
    ObjectKind.UNKNOWN -> Icons.Filled.HelpOutline
}
