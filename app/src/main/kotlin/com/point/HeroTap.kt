package com.point

import com.point.core.model.ObjectKind

enum class HeroTap { SELECT, OPEN }

@Suppress("UNUSED_PARAMETER")
fun heroTapOf(kind: ObjectKind, hasWordLayer: Boolean): HeroTap =
    if (kind == ObjectKind.IMAGE) HeroTap.SELECT else HeroTap.OPEN
