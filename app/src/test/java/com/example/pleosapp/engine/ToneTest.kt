package com.example.pleosapp.engine

import org.junit.Assert.assertFalse
import org.junit.Test

/** 슬픔과 죄책감으로 붙잡지 않고, 없는 사람을 말하지 않습니다(방향 보고서 7장). */
class ToneTest {

    private val banned = listOf("슬퍼", "슬프", "외로", "미안", "보고 싶", "서운", "버리", "버렸", "떠나", "잊", "죽", "왜 안", "혼자", "못 왔", "안 탔", "빠졌", "기다리고 있")

    private val family = FamilyState().adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_LEFT).adopt(SpiritPreset.SOM, SeatSlot.REAR_RIGHT)
    private val names = family.spirits.map { it.preset.displayName }

    private val spoken = listOf(
        HomeSpeech.PET, HomeSpeech.HELLO, HomeSpeech.FIRST_JAR, HomeSpeech.FIRST_ADOPT, HomeSpeech.INVITE, HomeSpeech.WAITING, HomeSpeech.PLAYING, HomeSpeech.MORNING, HomeSpeech.DAY, HomeSpeech.EVENING,
        HomeSpeech.NIGHT, HomeSpeech.RAIN, HomeSpeech.SNOW, HomeSpeech.FOG, HomeSpeech.DIZZY, HomeSpeech.GIFT, HomeSpeech.GIFT_NIGHT,
        HomeSpeech.WASHER, HomeSpeech.FUEL, HomeSpeech.CHARGE, HomeSpeech.FED,
        FamilySpeech.THANKS_DEFAULT, FamilySpeech.ASK_DEFAULT, FamilySpeech.GATHERED, FamilySpeech.BESIDE,
        FamilySpeech.woke(names), FamilySpeech.askBelt(names), FamilySpeech.thanks(names),
        FamilySpeech.sendOff(names.first()), FamilySpeech.welcomeBack(names), FamilySpeech.brought(names.first(), "도토리"),
    ) + HomeSpeech.POKES + HomeSpeech.SKY_POKE + SpiritPhrases.ALL + CareItem.entries.map { HomeSpeech.careThanks(it) } +
        CareItem.entries.flatMap { item -> CareStatus.entries.map { HomeSpeech.careFeeling(item, it) } } +
        Reward.entries.map { HomeSpeech.rewardLine(it) } + RewardKind.entries.map { it.teaser } +
        Moment.entries.flatMap { listOf(it.title, it.hint) } + Badge.entries.map { it.hint } +
        Badge.entries.map { HomeSpeech.likenessLine(it) } + FriendSkill.entries.mapNotNull { FamilySpeech.learned(names.first(), it) } +
        (0 until 4).map { GiftState(notes = it).nextNote() }

    @Test
    fun `대사에 슬픔과 죄책감이 없다`() {
        spoken.forEach { line ->
            banned.forEach { word -> assertFalse("$word 금지: $line", word in line) }
        }
    }

    @Test
    fun `일기는 안 탄 정령을 말하지 않는다`() {
        val trip = TripSummary(1, 600_000, 1_000f, 1, 0, 1, 0, seats = setOf(SeatSlot.REAR_LEFT))
        val diary = DiaryTemplate.lines(trip, PetState(), family).joinToString(" ")
        assertFalse(diary, "도토리" in diary)
    }

    @Test
    fun `혼자 탄 날의 일기는 정령을 말하지 않는다`() {
        val trip = TripSummary(1, 600_000, 1_000f, 1, 0, 1, 0)
        val diary = DiaryTemplate.lines(trip, PetState(), family).joinToString(" ")
        names.forEach { assertFalse(diary, it in diary) }
    }
}
