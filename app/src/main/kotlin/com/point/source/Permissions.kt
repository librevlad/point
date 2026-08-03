package com.point.source

/** Чего не хватает источнику из того, что он просил. Уже выданное не спрашивается повторно. */
fun missingPermissions(required: List<String>, granted: Set<String>): List<String> =
    required.filterNot { it in granted }
