package com.point.core.flow

object RelayRpc {

    const val KIND = "rpc.kind"

    const val ID = "rpc.id"

    const val REPLY = "rpc.reply"

    const val OBJECT = "object"

    const val CLIP_PUSH = "clip-push"

    const val CLIP_PULL = "clip-pull"

    const val CAPS = "caps"

    const val OUTBOX = "outbox"

    const val FETCH = "fetch"

    const val ACK = "ack"

    const val PHONE_CAPS = "phone-caps"

    const val SECRETS = "secrets"

    /**
     * Компьютер просит телефон сделать работу (#817).
     *
     * До этого связка была односторонней: телефон просил, компьютер делал. Обратно просьба
     * доходила до ящика и гибла — телефон вычищал ящик перед каждой своей отправкой.
     *
     * Просьба несёт `RUN_ACTION` — какое действие, — и сам объект телом письма.
     */
    const val RUN = "run"

    const val RUN_ACTION = "run.action"

    const val RUN_NAME = "run.name"

    const val RUN_MIME = "run.mime"

    /** Что вышло: телефон отвечает словами человека, а не кодом. */
    const val RUN_DONE = "run.done"

    const val RUN_FAILED = "run.failed"
}

/** Письмо — просьба сделать работу, а не ответ на наш вопрос. */
fun isRequest(meta: Map<String, String>): Boolean =
    meta[RelayRpc.KIND] != null && meta[RelayRpc.KIND] != RelayRpc.REPLY

fun isOurReply(replyMeta: Map<String, String>, requestId: String): Boolean {
    if (replyMeta[RelayRpc.KIND] != RelayRpc.REPLY) return false
    val answered = replyMeta[RelayRpc.ID] ?: return true
    return answered == requestId
}
