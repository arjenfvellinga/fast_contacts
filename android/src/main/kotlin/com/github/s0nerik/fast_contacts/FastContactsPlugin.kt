package com.github.s0nerik.fast_contacts

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.annotation.NonNull
import androidx.core.content.ContentResolverCompat
import androidx.lifecycle.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


/** FastContactsPlugin */
class FastContactsPlugin : FlutterPlugin, MethodCallHandler, LifecycleOwner, ViewModelStoreOwner {
    private lateinit var channel: MethodChannel
    private lateinit var contentResolver: ContentResolver
    private lateinit var handler: Handler

    private val contactsExecutor = ThreadPoolExecutor(
            4, Integer.MAX_VALUE,
            20L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>()
    )

    private val imageExecutor = ThreadPoolExecutor(
            4, Integer.MAX_VALUE,
            20L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>()
    )

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.github.s0nerik.fast_contacts")
        handler = Handler(flutterPluginBinding.applicationContext.mainLooper)
        contentResolver = flutterPluginBinding.applicationContext.contentResolver
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getContacts" -> {
                val args = call.arguments as Map<String, String>
                val type = args["type"]
                if (type == null) {
                    result.error("", "getContacts: 'type' must be specified", null)
                    return
                }
                contactsExecutor.execute {
                    withResultDispatcher(result) {
                        getContactsInfoJsonMap(TargetInfo.fromString(type))
                    }
                }
            }
            "getContactImage" -> {
                val args = call.arguments as Map<String, String>
                val contactId = args.getValue("id").toLong()
                if (args["size"] == "thumbnail") {
                    imageExecutor.execute {
                        withResultDispatcher(result) { getContactThumbnail(contactId) }
                    }
                } else {
                    imageExecutor.execute {
                        withResultDispatcher(result) { getContactImage(contactId) }
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun getLifecycle(): Lifecycle {
        val registry = LifecycleRegistry(this)
        registry.currentState = Lifecycle.State.RESUMED
        return registry
    }

    override fun getViewModelStore(): ViewModelStore {
        return ViewModelStore()
    }

    private fun getContactsInfoJsonMap(targetInfo: TargetInfo): List<Map<String, Any?>> {
        return when (targetInfo) {
            TargetInfo.PHONES -> readPhonesInfo()
            TargetInfo.EMAILS -> readEmailsInfo()
            TargetInfo.STRUCTURED_NAME -> readStructuredNameInfo()
        }.values.map(Contact::asMap)
    }

    private fun readStructuredNameInfo(): Map<Long, Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        readTargetInfo(TargetInfo.STRUCTURED_NAME) { projection, cursor ->
            val contactId = cursor.getLong(projection.indexOf(StructuredName.CONTACT_ID))
            val displayName = cursor.getString(projection.indexOf(StructuredName.DISPLAY_NAME))
                    ?: ""

            val prefix = cursor.getString(projection.indexOf(StructuredName.PREFIX)) ?: ""
            val givenName = cursor.getString(projection.indexOf(StructuredName.GIVEN_NAME)) ?: ""
            val middleName = cursor.getString(projection.indexOf(StructuredName.MIDDLE_NAME)) ?: ""
            val familyName = cursor.getString(projection.indexOf(StructuredName.FAMILY_NAME)) ?: ""
            val suffix = cursor.getString(projection.indexOf(StructuredName.SUFFIX)) ?: ""

            contacts[contactId] = Contact(
                    id = contactId.toString(),
                    displayName = displayName,
                    structuredName = StructuredName(
                            namePrefix = prefix,
                            givenName = givenName,
                            middleName = middleName,
                            familyName = familyName,
                            nameSuffix = suffix
                    )
            )
        }
        return contacts
    }

    private fun readPhonesInfo(): Map<Long, Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        readTargetInfo(TargetInfo.PHONES) { projection, cursor ->
            val contactId = cursor.getLong(projection.indexOf(Phone.CONTACT_ID))
            val displayName = cursor.getString(projection.indexOf(Phone.DISPLAY_NAME)) ?: ""
            val phone = cursor.getString(projection.indexOf(Phone.NUMBER)) ?: ""

            if (contacts.containsKey(contactId)) {
                (contacts[contactId]!!.phones as MutableList<String>).add(phone)
            } else {
                contacts[contactId] = Contact(
                        id = contactId.toString(),
                        displayName = displayName,
                        phones = mutableListOf(phone)
                )
            }
        }
        return contacts
    }

    private fun readEmailsInfo(): Map<Long, Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        readTargetInfo(TargetInfo.EMAILS) { projection, cursor ->
            val contactId = cursor.getLong(projection.indexOf(Email.CONTACT_ID))
            val displayName = cursor.getString(projection.indexOf(Email.DISPLAY_NAME)) ?: ""
            val email = cursor.getString(projection.indexOf(Email.ADDRESS)) ?: ""

            if (contacts.containsKey(contactId)) {
                (contacts[contactId]!!.emails as MutableList<String>).add(email)
            } else {
                contacts[contactId] = Contact(
                        id = contactId.toString(),
                        displayName = displayName,
                        emails = mutableListOf(email)
                )
            }
        }
        return contacts
    }

    private fun readTargetInfo(targetInfo: TargetInfo, onData: (projection: Array<String>, cursor: Cursor) -> Unit) {
        val cursor = ContentResolverCompat.query(contentResolver, CONTENT_URI[targetInfo],
                PROJECTION[targetInfo], SELECTION[targetInfo], SELECTION_ARGS[targetInfo], SORT_ORDER[targetInfo], null)
        cursor?.use {
            while (!cursor.isClosed && cursor.moveToNext()) {
                onData(PROJECTION[targetInfo]!!, cursor)
            }
        }
    }

    private fun getContactThumbnail(contactId: Long): ByteArray? {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return contentResolver.query(
                Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY),
                arrayOf(ContactsContract.Contacts.Photo.PHOTO),
                null,
                null,
                null
        )?.use { cursor ->
            if (cursor.moveToNext()) cursor.getBlob(0) else null
        }
    }

    private fun getContactImage(contactId: Long): ByteArray? {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val displayPhotoUri = Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.DISPLAY_PHOTO)

        return contentResolver.openAssetFileDescriptor(displayPhotoUri, "r")?.use { fd ->
            fd.createInputStream().use {
                it.readBytes()
            }
        }
    }

    private fun <T> withResultDispatcher(result: Result, action: () -> T) {
        try {
            val resultValue = action()
            handler.post {
                result.success(resultValue)
            }
        } catch (e: Exception) {
            handler.post {
                result.error("", e.localizedMessage, e.toString())
            }
        }
    }

    companion object {
        private val CONTENT_URI = mapOf(
                TargetInfo.PHONES to Phone.CONTENT_URI,
                TargetInfo.EMAILS to Email.CONTENT_URI,
                TargetInfo.STRUCTURED_NAME to ContactsContract.Data.CONTENT_URI
        )
        private val PROJECTION = mapOf(
                TargetInfo.PHONES to arrayOf(
                        Phone.CONTACT_ID,
                        Phone.DISPLAY_NAME,
                        Phone.NUMBER
                ),
                TargetInfo.EMAILS to arrayOf(
                        Email.CONTACT_ID,
                        Email.DISPLAY_NAME,
                        Email.ADDRESS
                ),
                TargetInfo.STRUCTURED_NAME to arrayOf(
                        StructuredName.CONTACT_ID,
                        StructuredName.DISPLAY_NAME,
                        StructuredName.PREFIX,
                        StructuredName.GIVEN_NAME,
                        StructuredName.MIDDLE_NAME,
                        StructuredName.FAMILY_NAME,
                        StructuredName.SUFFIX
                )
        )
        private val SELECTION = mapOf(
                TargetInfo.STRUCTURED_NAME to "${ContactsContract.Data.MIMETYPE} = ?"
        )
        private val SELECTION_ARGS = mapOf(
                TargetInfo.STRUCTURED_NAME to arrayOf(StructuredName.CONTENT_ITEM_TYPE)
        )
        private val SORT_ORDER = mapOf(
                TargetInfo.PHONES to "${Phone.DISPLAY_NAME} ASC",
                TargetInfo.EMAILS to "${Email.DISPLAY_NAME} ASC",
                TargetInfo.STRUCTURED_NAME to "${StructuredName.DISPLAY_NAME} ASC"
        )
    }
}

private enum class TargetInfo {
    PHONES, EMAILS, STRUCTURED_NAME;

    companion object {
        fun fromString(str: String): TargetInfo {
            return when (str) {
                "emails" -> EMAILS
                "phones" -> PHONES
                "structuredName" -> STRUCTURED_NAME
                else -> error("Wrong TargetInfo: '$str'")
            }
        }
    }
}
