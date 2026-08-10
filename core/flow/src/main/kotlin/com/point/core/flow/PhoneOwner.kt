package com.point.core.flow

/**
 * Чей это телефон (#747).
 *
 * На почтовой наклейке отправитель и получатель стоят двумя столбцами, и номер под именем
 * отправителя принадлежит ему, а не соседу справа. Владелец, разбирая кейс: «телефон без
 * роли: чей он, отправителя или получателя, не сказано».
 *
 * Пара «имя + номер» — уже существующий примитив (#653). Здесь она берётся из страницы,
 * когда модель её не назвала: имя и номер в одном блоке — один человек.
 *
 * Правило узкое намеренно. Блок с двумя именами не отдаёт ни одного: догадка о том, кому
 * из них принадлежит номер, была бы выдумкой, а не знанием.
 */
fun AtomLayer.phoneOwners(
    phones: List<FieldCandidate>,
    names: Collection<String>,
): List<PersonContact> {
    val unnamed = phones.filter { it.person == null && it.ids.isNotEmpty() }
    if (unnamed.isEmpty() || names.isEmpty()) return emptyList()

    val blocks = blocks()
    if (blocks.size < 2) return emptyList()

    val blockById = HashMap<String, Int>()
    blocks.forEachIndexed { index, block -> block.forEach { blockById[it.id] = index } }

    fun blockOf(ids: List<String>): Int? =
        ids.map(::bareIndexId).mapNotNull(blockById::get).distinct().singleOrNull()

    val nameBlocks = names.distinct().mapNotNull { name ->
        val where = findOnPage(name).singleOrNull()?.ids?.let(::blockOf) ?: return@mapNotNull null
        name to where
    }

    return unnamed.mapNotNull { phone ->
        val where = blockOf(phone.ids) ?: return@mapNotNull null
        val neighbours = nameBlocks.filter { (_, block) -> block == where }
        val owner = neighbours.singleOrNull()?.first ?: return@mapNotNull null
        PersonContact(owner, phone.text)
    }
}
