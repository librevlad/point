package com.point.core.flow

/**
 * Куда ехать (#772).
 *
 * У почтовой наклейки два адреса, и они не равноправны: склад отправления в шапке — не то
 * место, куда добираться человеку. «Построить маршрут» вело в «ОДЕСА ПОСИЛКОВИЙ», пока место
 * бралось первым похожим на топоним, а посылка ехала в «с.Бритівка, Відділення №1».
 *
 * Роль получателя Point к этому моменту уже знает. Новых сущностей не нужно: место, стоящее
 * в одном блоке с получателем, и есть назначение — блоки страницы (#764, #768) эту
 * принадлежность уже дают.
 *
 * Правило узкое намеренно, как и у телефона (#747): если в блоке получателя мест несколько
 * или получателя на странице не видно, выбор не делается. Догадка о том, какое из двух мест
 * имелось в виду, была бы выдумкой, а маршрут не туда — хуже маршрута, которого нет.
 */
fun AtomLayer.placeOfReceiver(places: List<FieldCandidate>, receiver: String?): FieldCandidate? {
    if (receiver.isNullOrBlank()) return null

    val grounded = places.filter { it.ids.isNotEmpty() }
    if (grounded.size < 2) return null

    val blocks = blocks()
    if (blocks.size < 2) return null

    val blockById = HashMap<String, Int>()
    blocks.forEachIndexed { index, block -> block.forEach { blockById[it.id] = index } }

    fun blockOf(ids: List<String>): Int? =
        ids.map(::bareIndexId).mapNotNull(blockById::get).distinct().singleOrNull()

    val where = findOnPage(receiver).singleOrNull()?.ids?.let(::blockOf) ?: return null

    return grounded.filter { blockOf(it.ids) == where }.singleOrNull()
}
