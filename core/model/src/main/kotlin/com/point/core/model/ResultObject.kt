package com.point.core.model

data class ResultObject(
    val type: ObjectKind,
    val mime: String,
    val uri: ObjectRef,
    val metadata: Map<String, String> = emptyMap(),

    /**
     * Каким путём вещь получена (#264, #1127).
     *
     * По умолчанию — [Provenance.RULE]: скан, страницы, сжатие, QR и озвучка сделаны
     * механикой устройства. Тот, кто зовёт модель, говорит об этом сам — иначе объект,
     * сочинённый сервисом, стоял бы в Graph наравне с посчитанным на месте.
     */
    val provenance: Provenance = Provenance.RULE,
)
