package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.point.core.flow.ContactInserter
import com.point.core.flow.NewContact
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidContactInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactInserter {

    override suspend fun insertContact(contact: NewContact) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setType(ContactsContract.Contacts.CONTENT_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Всё знание о человеке едет в карточку (#679): раньше имя с визитки
        // терялось, и человек дописывал руками уже прочитанное Point.
        contact.name?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(ContactsContract.Intents.Insert.NAME, it) }
        contact.phone?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        contact.email?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        contact.address?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        contact.company?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error("На этом устройстве нет приложения контактов")
        }
    }
}
