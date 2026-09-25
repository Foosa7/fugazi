package com.fugazi.app.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A habit plus its *meta*: why it matters, what it's for, and what a lapse means at
 * each length. The meta is the point — it's what lets a model (or future-you) reason
 * about a miss in your own terms instead of just counting it.
 *
 * Stored as one JSON line per version in habits.jsonl. Editing appends a new version
 * with the same [id] and a higher [v], so a change in your "why" is itself history.
 */
@Serializable
data class Habit(
    val id: String,
    val v: Int = 1,
    val name: String,
    val kind: Kind = Kind.DO,
    val target: Target = Target(),
    /** Free text: when in the day this usually happens ("morning", "after dinner"). */
    val `when`: String = "",
    val why: String = "",
    val goal: String = "",
    /** Ordered mild → serious. See [Rung]. */
    val ladder: List<Rung> = emptyList(),
    /** Asked when the habit is going well. Null uses a default. */
    val winAsk: String? = null,
    /** Mark this habit done automatically from passive signals. Null = manual only. */
    val auto: AutoRule? = null,
    /** yyyy-MM-dd, the day the habit started counting. */
    val created: String,
    val archived: Boolean = false,
    /** Wall-clock time this version was written. */
    val t: String = "",
)

/**
 * A habit the phone can see for itself, so it doesn't depend on you remembering to tap.
 * All of them read Health Connect (Fitbit, Galaxy Watch…).
 */
@Serializable
data class AutoRule(val type: AutoType, val minutes: Int)

@Serializable
enum class AutoType {
    /** Done when the first unlock came at least [AutoRule.minutes] after waking. */
    @SerialName("wake_no_phone") WAKE_NO_PHONE,

    /** Done when you woke within [AutoRule.minutes] of your usual (median) wake time. */
    @SerialName("wake_on_schedule") WAKE_ON_SCHEDULE,

    /** Done when the day had a continuous walk of at least [AutoRule.minutes] (steps or a walk session). */
    @SerialName("walk") WALK,
}

@Serializable
enum class Kind {
    /** Something you want to do: floss, walk, vitamin B. Lapse = days since last done. */
    @SerialName("do") DO,

    /** Something you want less of: doomscrolling. Lapse = slips within a recent window. */
    @SerialName("avoid") AVOID,
}

/** "[times] per [days] days". Daily is 1/1; talk to a stranger twice a week is 2/7. */
@Serializable
data class Target(val times: Int = 1, val days: Int = 1)

/**
 * One step of the ladder.
 *
 * DO: reached when [days] days have passed without it being done (skipped days don't count).
 * AVOID: reached when there are at least [slips] slip-days within the last [days] days.
 *
 * A rung with no [ask] is just a label shown on the habit ("3 days — fine"). A rung with an
 * [ask] fires once per lapse and asks you that question; [likely] are one-tap answers you
 * wrote in advance.
 */
@Serializable
data class Rung(
    val days: Int,
    val meaning: String = "",
    val ask: String? = null,
    val likely: List<String> = emptyList(),
    val slips: Int = 1,
)

/**
 * Everything that happens, appended to events.jsonl. [date] is the local day the event is
 * *about* (you can mark yesterday done today); [t] is when it was written.
 */
@Serializable
data class Event(
    val t: String,
    val habit: String,
    val type: EventType,
    val date: String? = null,
    /** For triggers/answers: which ladder rung (index), or -1 for a win. */
    val rung: Int? = null,
    /** For win triggers: the days-clean / days-on-pace milestone it celebrates. */
    val days: Int? = null,
    val text: String? = null,
    /** "auto" when written from passive signals rather than by you. */
    val src: String? = null,
)

@Serializable
enum class EventType {
    @SerialName("done") DONE,
    @SerialName("undone") UNDONE,
    @SerialName("slip") SLIP,
    @SerialName("unslip") UNSLIP,
    /** Not done, on purpose, with a reason (sick, travelling). Doesn't count as a lapse. */
    @SerialName("skip") SKIP,
    /** A ladder rung or a win fired. */
    @SerialName("trigger") TRIGGER,
    /** Your reply to a trigger. */
    @SerialName("answer") ANSWER,
    /** Trigger closed without a reply. */
    @SerialName("dismiss") DISMISS,
    /** What an auto rule saw on a day it didn't mark done — kept so the pattern is readable later. */
    @SerialName("observed") OBSERVED,
}

const val WIN_RUNG = -1
const val DEFAULT_WIN_ASK = "This is going well. What's making it work?"

fun nowStamp(): String =
    java.time.LocalDateTime.now().withNano(0).toString()

const val SRC_AUTO = "auto"
