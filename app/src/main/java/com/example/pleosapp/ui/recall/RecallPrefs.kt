package com.example.pleosapp.ui.recall

import android.content.Context
import com.example.pleosapp.R
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.PetState
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.engine.RecallSettings
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.engine.TogetherState
import com.example.pleosapp.ui.spirit.SpiritSprites

/**
 * 회상의 설정과 이미 본 것. 사람이 고른 값과 화면에서만 쓰는 값이라 원장과 따로 둡니다.
 * 마지막으로 본 날 수도 여기 둡니다. 긴 원장을 읽는 동안 집이 처음으로 돌아가 보이지 않게 합니다.
 */
class RecallPrefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("recall", Context.MODE_PRIVATE)

    var off: Boolean
        get() = store.getBoolean("off", false)
        set(value) = store.edit().putBoolean("off", value).apply()

    var hideDates: Boolean
        get() = store.getBoolean("hideDates", false)
        set(value) = store.edit().putBoolean("hideDates", value).apply()

    val settings: RecallSettings get() = RecallSettings(off, hideDates)

    /** debug 빌드에서 고른 달. 앱을 다시 띄워도 영상을 찍는 동안 유지됩니다. 0이면 실제 달입니다. */
    var previewMonth: Int
        get() = store.getInt("previewMonth", 0)
        set(value) = store.edit().putInt("previewMonth", value).apply()

    val seen: Set<String> get() = store.getStringSet("seen", emptySet()).orEmpty()

    /** 그림책을 본 날. 같은 날 다음 책을 권하지 않습니다. */
    val lastSeenDay: Long get() = store.getLong("lastSeenDay", -1L)

    /** 한 줄 회상을 꺼낸 날. 하루 한 번입니다. */
    var lastLineDay: Long
        get() = store.getLong("lastLineDay", -1L)
        set(value) = store.edit().putLong("lastLineDay", value).apply()

    val lastDays: Int get() = store.getInt("lastDays", 0)

    val lastStage: Growth.Stage get() = Growth.Stage.entries[store.getInt("lastStage", 0).coerceIn(0, Growth.Stage.entries.lastIndex)]

    /** 다 읽고 덮은 그림책. 홈이 새 사진을 흔들며 걸어 줍니다. 한 번 꺼내면 지워집니다. */
    fun takeHung(): RecallId? {
        val key = store.getString("hung", null) ?: return null
        store.edit().remove("hung").apply()
        return RecallId.parse(key)
    }

    fun markHung(id: RecallId) = store.edit().putString("hung", id.key).apply()

    fun markSeen(id: RecallId, nowMs: Long) {
        store.edit()
            .putStringSet("seen", HashSet(seen) + id.key)
            .putLong("lastSeenDay", TimeTogether.dayOf(nowMs))
            .apply()
    }

    fun waiting(state: TogetherState, nowMs: Long): RecallId? = TimeTogether.waiting(state, settings, seen, lastSeenDay, nowMs)

    /** 지금 상태를 기억해 둡니다. 값이 그대로면 쓰지 않습니다. */
    fun rememberAge(pet: PetState) {
        val days = pet.together.days
        val stage = pet.stage.ordinal
        if (days > lastDays || stage > lastStage.ordinal) {
            store.edit().putInt("lastDays", maxOf(days, lastDays)).putInt("lastStage", maxOf(stage, lastStage.ordinal)).apply()
        }
    }

    fun clear() = store.edit().clear().apply()
}

/** 그림 이름 목록에서 앱에 있는 첫 그림을 고릅니다. 새 그림은 넣기만 하면 앞에서 잡힙니다. */
object RecallArt {
    fun of(context: Context, names: List<String>): Int =
        names.firstNotNullOfOrNull { name -> SpiritSprites.named(context, name).takeIf { it != 0 } } ?: R.drawable.page_home
}
