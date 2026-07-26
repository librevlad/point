package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File

/**
 * Desktop side-effects behind seams (same invariant as the phone): the pairs below
 * stay JVM-pure and unit-testable; AWT lives only in the implementations wired in Main.
 */
fun interface SystemOpener { fun open(file: File) }
fun interface FileRevealer { fun reveal(file: File) }
fun interface TextClipboard { fun copy(text: String) }
fun interface SaveTarget { fun pickAndSave(file: File): String? }

class PcOpenCapability : Capability {
    override val id = CapabilityId("pc-open")
    override val icon = "open"
    override val meta = CapabilityMeta(priority = 10)
    override fun label(state: ObjectState) = "Открыть"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcOpenRealizer(private val opener: SystemOpener) : Realizer {
    override val capabilityId = CapabilityId("pc-open")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            opener.open(File(input.uri.value))
            ActionResult.Done("Открыто")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
}

class PcRevealCapability : Capability {
    override val id = CapabilityId("pc-reveal")
    override val icon = "folder"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Показать в папке"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcRevealRealizer(private val revealer: FileRevealer) : Realizer {
    override val capabilityId = CapabilityId("pc-reveal")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            revealer.reveal(File(input.uri.value))
            ActionResult.Done("Папка открыта")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось показать", recoverable = true) }
}

class PcCopyCapability : Capability {
    override val id = CapabilityId("pc-copy")
    override val icon = "copy"
    override val meta = CapabilityMeta(priority = 15)
    override fun label(state: ObjectState) = "Копировать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = state
}

class PcCopyRealizer(private val clipboard: TextClipboard) : Realizer {
    override val capabilityId = CapabilityId("pc-copy")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            clipboard.copy(File(input.uri.value).readText())
            ActionResult.Done("Скопировано в буфер")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось скопировать", recoverable = true) }
}

class PcSaveAsCapability : Capability {
    override val id = CapabilityId("pc-save-as")
    override val icon = "save"
    override val meta = CapabilityMeta(priority = 30)
    override fun label(state: ObjectState) = "Сохранить в…"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcSaveAsRealizer(private val target: SaveTarget) : Realizer {
    override val capabilityId = CapabilityId("pc-save-as")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val saved = target.pickAndSave(File(input.uri.value))
                ?: return ActionResult.Done("Отменено")
            ActionResult.Done("Сохранено: $saved")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось сохранить", recoverable = true) }
}
