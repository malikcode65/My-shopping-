package com.jarvis.agent.tools.impl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.safety.SensitiveInputGuard
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Contact(val name: String, val number: String)

/** Resolves names (and remembered nicknames like "Ammi") to phone numbers. */
object Contacts {
    /** Set by the memory module: maps a nickname to the real contact name, if known. */
    @Volatile var nicknameResolver: (suspend (String) -> String?)? = null

    fun require(context: Context, permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$permission not granted")
        }
    }

    fun search(context: Context, query: String, limit: Int = 8): List<Contact> {
        require(context, Manifest.permission.READ_CONTACTS)
        val out = LinkedHashMap<String, Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${query.trim()}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val key = normalize(number)
                if (key !in out) out[key] = Contact(name, number)
            }
        }
        return out.values.toList()
    }

    fun nameForNumber(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun normalize(number: String) = number.filter { it.isDigit() || it == '+' }.takeLast(10)

    fun looksLikeNumber(s: String) = s.count { it.isDigit() } >= 5 && s.all { it.isDigit() || it in "+-() " }

    sealed class Resolution {
        data class One(val contact: Contact) : Resolution()
        data class Many(val options: List<Contact>) : Resolution()
        data class None(val query: String) : Resolution()
    }

    suspend fun resolve(context: Context, who: String): Resolution {
        val q = who.trim()
        if (looksLikeNumber(q)) return Resolution.One(Contact(nameForNumber(context, q) ?: q, q))
        val realName = runCatching { nicknameResolver?.invoke(q) }.getOrNull()
        val matches = search(context, realName ?: q)
        val exact = matches.filter { it.name.equals(realName ?: q, ignoreCase = true) }
        return when {
            exact.size == 1 -> Resolution.One(exact[0])
            matches.size == 1 -> Resolution.One(matches[0])
            matches.isEmpty() -> Resolution.None(q)
            else -> Resolution.Many(matches)
        }
    }

    fun describe(r: Resolution): String = when (r) {
        is Resolution.One -> "${r.contact.name} (${r.contact.number})"
        is Resolution.Many -> "AMBIGUOUS"
        is Resolution.None -> "UNKNOWN"
    }

    fun failure(r: Resolution): ToolResult? = when (r) {
        is Resolution.One -> null
        is Resolution.Many -> ToolResult.error(
            "AMBIGUOUS_CONTACT: several contacts match: " + r.options.joinToString("; ") { "${it.name} ${it.number}" } +
                ". Use ask_user to ask which one, then call again with the number.",
        )
        is Resolution.None -> ToolResult.error("CONTACT_NOT_FOUND: no contact matches '${r.query}'. Ask the user.")
    }
}

class SearchContactsTool : BaseTool(
    "search_contacts", "Find contacts and phone numbers by name.", RiskLevel.LOW, readOnly = true,
    { string("query", "Name or part of a name") },
) {
    override fun describe(input: JsonObject) = "Search contacts for ${input.str("query")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val q = input.str("query").orEmpty()
        val alias = runCatching { Contacts.nicknameResolver?.invoke(q) }.getOrNull()
        val found = Contacts.search(ctx.app, alias ?: q)
        val prefix = if (alias != null) "('$q' is your nickname for '$alias')\n" else ""
        return ToolResult.ok(prefix + found.joinToString("\n") { "${it.name}: ${it.number}" }.ifEmpty { "No contacts found" })
    }
}

class MakeCallTool : BaseTool(
    "make_call", "Place a phone call to a contact name, nickname or number.", RiskLevel.MEDIUM,
    schemaBlock = { string("to", "Contact name, nickname or phone number") },
) {
    override fun describe(input: JsonObject) = "Call ${input.str("to")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val r = Contacts.resolve(ctx.app, input.str("to").orEmpty())
        return RiskAssessment(RiskLevel.MEDIUM, "Call ${Contacts.describe(r)}")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.CALL_PHONE)
        val r = Contacts.resolve(ctx.app, input.str("to").orEmpty())
        Contacts.failure(r)?.let { return it }
        val c = (r as Contacts.Resolution.One).contact
        ctx.app.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Calling ${c.name} (${c.number}).")
    }
}

class SendSmsTool : BaseTool(
    "send_sms", "Send an SMS text message. The user sees the exact text and confirms before it is sent.", RiskLevel.MEDIUM,
    schemaBlock = {
        string("to", "Contact name, nickname or phone number")
        string("message", "Exact message text")
    },
) {
    override fun describe(input: JsonObject) = "SMS to ${input.str("to")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val r = Contacts.resolve(ctx.app, input.str("to").orEmpty())
        return RiskAssessment(RiskLevel.MEDIUM, "Send SMS to ${Contacts.describe(r)}", input.str("message").orEmpty())
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.SEND_SMS)
        val message = input.str("message").orEmpty()
        SensitiveInputGuard.checkOutgoingText(message)?.let {
            return ToolResult.error("BLOCKED_SECRET: $it Jarvis never sends secrets. Use hand_off_to_user if the user wants to send it.")
        }
        val r = Contacts.resolve(ctx.app, input.str("to").orEmpty())
        Contacts.failure(r)?.let { return it }
        val c = (r as Contacts.Resolution.One).contact
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.app.getSystemService(SmsManager::class.java) ?: return ToolResult.error("SMS not available")
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(c.number, null, parts, null, null)
        return ToolResult.ok("SMS sent to ${c.name} (${c.number}).")
    }
}

class ReadSmsTool : BaseTool(
    "read_sms", "Read recent SMS messages from the inbox, optionally from one sender.", RiskLevel.LOW, readOnly = true,
    {
        string("from", "Optional contact name or number to filter by", required = false)
        integer("limit", "How many (default 5, max 20)", required = false, min = 1, max = 20)
        boolean("unread_only", "Only unread messages", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Read SMS" + (input.str("from")?.let { " from $it" } ?: "")

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.READ_SMS)
        val limit = (input.int("limit") ?: 5).coerceIn(1, 20)
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        input.str("from")?.let { from ->
            val numbers = if (Contacts.looksLikeNumber(from)) listOf(from) else Contacts.search(ctx.app, from).map { it.number }
            if (numbers.isEmpty()) return ToolResult.error("CONTACT_NOT_FOUND: $from")
            selection += "(" + numbers.joinToString(" OR ") { "${Telephony.Sms.ADDRESS} LIKE ?" } + ")"
            args += numbers.map { "%" + Contacts.normalize(it) }
        }
        if (input.bool("unread_only") == true) selection += "${Telephony.Sms.READ} = 0"
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH)
        val out = StringBuilder()
        ctx.app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection.joinToString(" AND ").ifEmpty { null },
            args.toTypedArray().ifEmpty { null },
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                val addr = c.getString(0).orEmpty()
                val who = Contacts.nameForNumber(ctx.app, addr) ?: addr
                out.append("[${fmt.format(Date(c.getLong(2)))}] $who: ${c.getString(1).orEmpty()}\n")
                n++
            }
        }
        return ToolResult.untrusted("sms", out.toString().ifEmpty { "No messages found" })
    }
}
