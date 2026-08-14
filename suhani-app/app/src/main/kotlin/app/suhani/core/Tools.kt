package app.suhani.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Port of silai/tools.py: the OpenAI-format toolset (the bake-off's "fixed"
 * configuration with date guards) + the deterministic date calculator + the
 * dispatcher over the Ledger. Descriptions are copied verbatim.
 */
object Tools {

    private fun tool(name: String, description: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", description).put("parameters", params),
        )

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        for ((k, v) in props) properties.put(k, v)
        val o = JSONObject().put("type", "object").put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) o.put("required", JSONArray(required))
        return o
    }

    private fun p(type: String, description: String? = null): JSONObject =
        JSONObject().put("type", type).also { if (description != null) it.put("description", description) }

    /** openai_toolset(with_date_guards=True) - verbatim descriptions. */
    fun openaiToolset(): JSONArray = JSONArray(
        listOf(
            tool(
                "find_client",
                "ग्राहक को नाम से ढूँढो। Fuzzy matching करता है - STT की गलत spelling भी पकड़ लेता है। " +
                    "किसी भी ग्राहक से जुड़े काम से पहले हमेशा यह चलाओ। खाली सूची मिले तो ग्राहक नई है।",
                obj("name" to p("string", "जैसा नाम सुना, वैसा ही (honorific हटाने की ज़रूरत नहीं)"), required = listOf("name")),
            ),
            tool(
                "add_client",
                "नई ग्राहक जोड़ो। सिर्फ़ तब जब find_client से कोई ना मिले और मालकिन ने पक्का कर दिया हो कि नई हैं।",
                obj(
                    "name" to p("string", "ग्राहक का नाम, हमेशा Devanagari लिपि में (जैसे: सुनीता, मीना जैन)"),
                    "phone" to p("string", "optional फ़ोन नंबर"),
                    required = listOf("name"),
                ),
            ),
            tool(
                "add_order",
                "नया ऑर्डर लिखो। मालकिन से 'लिख दूँ?' पूछ कर हाँ मिलने के बाद ही चलाओ।" +
                    " due_date: हफ़्ते के दिन का नाम (शुक्रवार/Friday) हो तो पहले resolve_due_date " +
                    "चलाओ; कल/परसों/सीधी तारीख़ calendar से।",
                obj(
                    "client_id" to p("integer"),
                    "garment" to p("string", "कपड़ा, हमेशा Devanagari लिपि में (जैसे: ब्लाउज़, साड़ी फॉल-पीको, कुर्ती)"),
                    "price" to p("number", "कुल दाम, रुपये में"),
                    "due_date" to p("string", "ISO date YYYY-MM-DD; पता ना हो तो मत भेजो"),
                    "advance" to p("number", "एडवांस मिला हो तो कितना"),
                    "notes" to p(
                        "string",
                        "मालकिन ने रंग/नाप/फ़रमाइश बोली हो तो यहाँ ज़रूर डालो, जैसे बोला वैसे ही " +
                            "(red बोला तो red)। ना बोली हो तो यह field मत भेजो।",
                    ),
                    required = listOf("client_id", "garment", "price"),
                ),
            ),
            tool(
                "update_order",
                "ऑर्डर बदलो: दाम, तारीख़, status, notes। कपड़ा दे दिया = status='delivered'। " +
                    "मालकिन 'रहने दो/कैंसिल कर दो' कहें = status='cancelled' (पैसे का हिसाब भी हट जाता है)।",
                obj(
                    "order_id" to p("integer"),
                    "garment" to p("string"),
                    "price" to p("number"),
                    "due_date" to p("string"),
                    "notes" to p("string"),
                    "status" to p("string").put("enum", JSONArray(listOf("open", "done", "delivered", "cancelled"))),
                    required = listOf("order_id"),
                ),
            ),
            tool(
                "orders_for_client",
                "एक ग्राहक के सारे खुले ऑर्डर और हर एक का बाकी पैसा (balance)।",
                obj("client_id" to p("integer"), required = listOf("client_id")),
            ),
            tool(
                "list_open_orders",
                "सारे खुले ऑर्डर एक साथ - हर एक के साथ ग्राहक, कपड़ा, दाम, तारीख़ और notes (रंग वग़ैरह)। " +
                    "जब मालकिन किसी detail से पूछें ('वो maroon वाला किसका था?') या पूरा काम देखना हो, यह चलाओ।",
                obj(),
            ),
            tool(
                "search_orders",
                "पुराने-नए सारे ऑर्डर में ढूँढो - बंद (delivered/cancelled) भी। रंग, नाप, कपड़े या नाम " +
                    "जैसे शब्दों से। जब 'वो maroon वाला किसका था?' खुले ऑर्डर में ना मिले, या दिए जा चुके / " +
                    "पुराने काम की बात हो, यह चलाओ। कुछ ना मिले तो साफ़ कहो - अंदाज़ा कभी मत लगाओ।",
                obj("query" to p("string", "ढूँढने के शब्द, जैसे 'maroon ब्लाउज़' या 'गोटा पत्ती लहंगा'"), required = listOf("query")),
            ),
            tool(
                "orders_due",
                "किसी तारीख़-सीमा में देने वाले खुले ऑर्डर। 'आज क्या देना है' = आज से आज तक।" +
                    " 'इस हफ़्ते' = आज से आने वाले रविवार तक।",
                obj(
                    "date_from" to p("string", "ISO date"),
                    "date_to" to p("string", "ISO date"),
                    required = listOf("date_from", "date_to"),
                ),
            ),
            tool(
                "check_dues",
                "किस-किस का पैसा बाकी है, कितना। ग्राहक बताई हो तो उसका, वरना सबका।",
                obj("client_id" to p("integer", "optional; ना भेजो तो सबका")),
            ),
            tool(
                "mark_paid",
                "पैसे मिले - दर्ज करो। मालकिन से पक्का करके ही चलाओ।",
                obj(
                    "client_id" to p("integer"),
                    "amount" to p("number"),
                    "order_id" to p("integer", "पता हो तो किस ऑर्डर का"),
                    "note" to p("string"),
                    required = listOf("client_id", "amount"),
                ),
            ),
            tool(
                "daily_briefing",
                "आज का सार: आज देना, कल देना, लेट हो चुके ऑर्डर, कुल बाकी पैसा।",
                obj(),
            ),
            tool(
                "remember",
                "मालकिन की निजी बात की छोटी याद लिखो (परिवार, त्योहार, सेहत, पसंद) - अगली बातचीतों में " +
                    "याद रहेगी। एक वाक्य में। ऑर्डर/पैसे यहाँ मत लिखो।",
                obj("content" to p("string", "एक छोटा वाक्य"), required = listOf("content")),
            ),
            tool(
                "forget_note",
                "कोई याद गलत/पुरानी हो जाए तो उसे हटाओ (id यादों की सूची में [id] जैसा दिखता है)।",
                obj("note_id" to p("integer"), required = listOf("note_id")),
            ),
            tool(
                "set_reminder",
                "मालकिन को किसी बात की याद दिलाने का reminder लगाओ - तय समय पर app जगेगा और तुम " +
                    "बोल कर याद दिलाओगी। दिन और समय दोनों ज़रूरी हैं - समय ना बताया हो तो पहले पूछो " +
                    "\"कब याद दिलाऊँ?\" (सुबह=08:00, दोपहर=13:00, शाम=18:00, रात=20:00)।",
                obj(
                    "content" to p("string", "किस बात की याद - छोटा वाक्य, जैसे 'सुनीता का ब्लाउज़ करना है'"),
                    "date" to p("string", "ISO तारीख़ YYYY-MM-DD (calendar/resolve_due_date से)"),
                    "time" to p("string", "24-घंटे HH:MM, जैसे 18:00"),
                    required = listOf("content", "date", "time"),
                ),
            ),
            tool(
                "list_reminders",
                "आने वाले सब reminders दिखाओ - मालकिन पूछें \"क्या-क्या याद दिलाना है?\" तो यह चलाओ।",
                obj(),
            ),
            tool(
                "cancel_reminder",
                "कोई reminder हटाओ (\"वो याद रहने दो\") - id list_reminders से।",
                obj("reminder_id" to p("integer"), required = listOf("reminder_id")),
            ),
            tool(
                "end_conversation",
                "बातचीत ख़त्म करो। मालकिन बात समेट दें (\"ठीक है अभी के लिए\", \"चलो अभी कुछ नहीं\", " +
                    "\"बस इतना ही\", \"रखती हूँ\") तो यह चलाओ और साथ के जवाब में छोटा गर्म विदा-वाक्य बोलो - " +
                    "app विदा बोलने के बाद अपने आप बंद हो जाएगा। बीच काम में कभी मत चलाओ।",
                obj(),
            ),
            tool(
                "resolve_due_date",
                "तारीख़ पक्की करने का calculator। 'अगले मंगलवार', 'दो हफ़्ते बाद', '15 अगस्त' जैसी बात को " +
                    "सही ISO तारीख़ में बदलता है। due_date भेजने से पहले इसी से तारीख़ लो।",
                obj(
                    "weekday" to p("string", "अगला आने वाला यह वार चाहिए तो")
                        .put("enum", JSONArray(Prompts.HINDI_WEEKDAYS)),
                    "days_from_today" to p("integer", "आज से इतने दिन बाद (कल=1, परसों=2, दो हफ़्ते=14)"),
                    "day_of_month" to p("integer", "महीने की तारीख़, जैसे 15"),
                    "month" to p("integer", "1-12; day_of_month के साथ"),
                ),
            ),
        ),
    )

    fun resolveDueDate(args: JSONObject, today: LocalDate = LocalDate.now(Ledger.IST)): String {
        return try {
            val d: LocalDate = when {
                args.optString("weekday").isNotEmpty() -> {
                    val target = Prompts.HINDI_WEEKDAYS.indexOf(args.getString("weekday"))
                    if (target < 0) return JSONObject().put("error", "अनजान वार").toString()
                    val todayW = today.dayOfWeek.value - 1 // Monday = 0, like Python
                    today.plusDays((Math.floorMod(target - todayW - 1, 7) + 1).toLong())
                }
                args.has("days_from_today") && !args.isNull("days_from_today") ->
                    today.plusDays(args.getLong("days_from_today"))
                args.optInt("day_of_month", 0) != 0 -> {
                    val month = if (args.optInt("month", 0) != 0) args.getInt("month") else today.monthValue
                    var d = LocalDate.of(today.year, month, args.getInt("day_of_month"))
                    if (d.isBefore(today)) d = LocalDate.of(today.year + 1, month, args.getInt("day_of_month"))
                    d
                }
                else -> return JSONObject().put("error", "कोई input नहीं मिला").toString()
            }
            JSONObject()
                .put("date", d.toString())
                .put("weekday", Prompts.HINDI_DAYS_SHORT[d.dayOfWeek.value - 1])
                .put("human", "${d.dayOfMonth} ${Prompts.HINDI_MONTHS[d.monthValue - 1]}")
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "error").toString()
        }
    }

    /** Recursively convert ledger Maps/Lists into org.json values. */
    fun toJson(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o ->
            for ((k, v) in value) o.put(k.toString(), toJson(v))
        }
        is List<*> -> JSONArray().also { a -> for (v in value) a.put(toJson(v)) }
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    /** Run one tool call; always returns a JSON string - errors go back to the model. */
    fun dispatch(ledger: Ledger, name: String, args: JSONObject): String {
        return try {
            val result: Any? = when (name) {
                "resolve_due_date" -> return resolveDueDate(args)
                "end_conversation" ->
                    mapOf("ok" to true, "note" to "विदा बोलिए, app बंद हो जाएगा")
                "set_reminder" -> ledger.addReminder(
                    args.getString("content"),
                    "${args.getString("date")}T${args.getString("time")}",
                )
                "list_reminders" -> mapOf("reminders" to ledger.listReminders())
                "cancel_reminder" -> ledger.cancelReminder(args.getLong("reminder_id"))
                "find_client" -> {
                    val matches = ledger.findClient(args.getString("name"))
                    if (matches.size == 1) {
                        ledger.addAlias((matches[0]["id"] as Number).toLong(), args.getString("name"))
                    }
                    mapOf("matches" to matches)
                }
                "add_client" -> ledger.addClient(args.getString("name"), args.optString("phone").ifEmpty { null })
                "add_order" -> ledger.addOrder(
                    clientId = args.getLong("client_id"),
                    garment = args.getString("garment"),
                    price = args.getDouble("price"),
                    dueDate = args.optString("due_date").ifEmpty { null },
                    advance = args.optDouble("advance", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    notes = args.optString("notes").ifEmpty { null },
                )
                "update_order" -> ledger.updateOrder(
                    orderId = args.getLong("order_id"),
                    garment = args.optString("garment").ifEmpty { null },
                    price = if (args.has("price") && !args.isNull("price")) args.getDouble("price") else null,
                    dueDate = args.optString("due_date").ifEmpty { null },
                    notes = args.optString("notes").ifEmpty { null },
                    status = args.optString("status").ifEmpty { null },
                )
                "orders_for_client" -> mapOf("orders" to ledger.ordersForClient(args.getLong("client_id")))
                "list_open_orders" -> mapOf("orders" to ledger.allOpenOrders())
                "search_orders" -> mapOf("orders" to ledger.searchOrders(args.getString("query")))
                "orders_due" -> mapOf("orders" to ledger.ordersDue(args.getString("date_from"), args.getString("date_to")))
                "check_dues" -> {
                    val cid = args.optLong("client_id", 0L)
                    if (cid != 0L) {
                        mapOf(
                            "client" to ledger.getClient(cid),
                            "due" to ledger.clientDue(cid),
                            "orders" to ledger.ordersForClient(cid),
                        )
                    } else {
                        mapOf("dues" to ledger.allDues())
                    }
                }
                "mark_paid" -> ledger.markPaid(
                    args.getLong("client_id"),
                    args.getDouble("amount"),
                    if (args.has("order_id") && !args.isNull("order_id")) args.getLong("order_id") else null,
                    args.optString("note").ifEmpty { null },
                )
                "daily_briefing" -> ledger.dailyBriefing()
                "remember" -> ledger.addNote(args.getString("content"))
                "forget_note" -> ledger.forgetNote(args.getLong("note_id"))
                else -> return JSONObject().put("error", "unknown tool $name").toString()
            }
            toJson(result).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString()).toString()
        }
    }
}
