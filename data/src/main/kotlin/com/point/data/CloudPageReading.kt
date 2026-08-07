package com.point.data

import com.point.core.flow.AtomCodec
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import java.io.File
import javax.inject.Inject

class CloudPageReading @Inject constructor(
    private val store: ObjectStore,
    private val readers: FallbackAtomRecognizer,
) {

    val available: Boolean get() = readers.available

    suspend fun read(obj: PointObject): Map<String, String> {
        val layer = readers.read(obj)
        val ref = store.newScratchFile("cloud-atoms.tsv")
        File(ref.value).writeText(AtomCodec.encode(layer))
        return mapOf(META_CLOUD_ATOMS_REF to ref.value)
    }
}
