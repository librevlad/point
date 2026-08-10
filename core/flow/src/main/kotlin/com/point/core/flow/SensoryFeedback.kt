package com.point.core.flow

interface SensoryFeedback {

    fun tap()

    fun success()

    fun failure()

    /**
     * Объект ушёл с этого устройства на соседнее (#650). Звучит вместо обычного успеха, а
     * не вместе с ним: у ухода свой смысл, и пара с прибытием на той стороне слышится
     * одним движением.
     */
    fun sent() = success()
}
