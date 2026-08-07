package com.point.core.model

@JvmInline
value class ObjectKind private constructor(val name: String) {

    override fun toString(): String = name

    companion object {
        val IMAGE = ObjectKind("IMAGE")
        val TEXT = ObjectKind("TEXT")
        val PDF = ObjectKind("PDF")
        val ZIP = ObjectKind("ZIP")

        val OFFICE = ObjectKind("OFFICE")
        val URL = ObjectKind("URL")

        val AUDIO = ObjectKind("AUDIO")

        val COLLECTION = ObjectKind("COLLECTION")
        val UNKNOWN = ObjectKind("UNKNOWN")

        val entries: List<ObjectKind> =
            listOf(IMAGE, TEXT, PDF, ZIP, OFFICE, URL, AUDIO, COLLECTION, UNKNOWN)

        fun valueOf(name: String): ObjectKind {
            require(name.isNotBlank()) { "ObjectKind name must not be blank" }
            return entries.firstOrNull { it.name == name } ?: ObjectKind(name)
        }

        fun of(name: String): ObjectKind = valueOf(name)

        val fileBacked: Set<ObjectKind> = entries.toSet() - COLLECTION
    }
}

val ObjectKind.isFileBacked: Boolean get() = this in ObjectKind.fileBacked
