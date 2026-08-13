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

}

fun isOurReply(replyMeta: Map<String, String>, requestId: String): Boolean {
    if (replyMeta[RelayRpc.KIND] != RelayRpc.REPLY) return false
    val answered = replyMeta[RelayRpc.ID] ?: return true
    return answered == requestId
}
