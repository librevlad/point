package com.point.data

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.provider.ContactsContract
import com.point.core.flow.ContactInserter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Экран «новый контакт» через ACTION_INSERT из application-контекста (без Activity), с
 * подставленным телефоном и почтой. Близнец [AndroidCalendarInserter]: тот же приём, только
 * тип контактов вместо календаря. Нет приложения контактов — падаем понятным словом, а не молча.
 */
class AndroidContactInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactInserter {

    override suspend fun insertContact(phone: String?, email: String?) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setType(ContactsContract.Contacts.CONTENT_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phone?.takeIf { it.isNotBlank() }?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        email?.takeIf { it.isNotBlank() }?.let { intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error("Нет приложения контактов")
        }
    }
}
