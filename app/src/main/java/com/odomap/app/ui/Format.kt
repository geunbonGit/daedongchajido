package com.odomap.app.ui

import com.odomap.app.engine.LiveDrive
import com.odomap.app.model.DriveRecord
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

/** 한국 시장 앱: 날짜·시각은 서울 시각(엔진의 날 번호 exploreDayOf와 같게) — 헤드 유닛·에뮬레이터 시간대와 상관없이 */
val SEOUL: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
private val WEEKDAYS = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun cal(ms: Long): Calendar = Calendar.getInstance(SEOUL, Locale.KOREAN).apply { timeInMillis = ms }

/** 거리 숫자(km): 차 거리라 소수 한 자리(버림) — '12.4' · '415.0' */
fun kmText(m: Double): String {
    val tenths = floor(maxOf(0.0, m) / 100.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** '12.4km' */
fun kmLabel(m: Double): String = kmText(m) + "km"

/** 1000 → '1,000' */
fun grouped(n: Long): String = String.format(Locale.US, "%,d", n)

/** 시간: '3분' · '1시간 5분' */
fun durationText(ms: Long): String {
    val minutes = maxOf(1L, Math.round(ms / 60_000.0))
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0L) "${m}분" else if (m == 0L) "${h}시간" else "${h}시간 ${m}분"
}

/** 기록 시계 '0:42'(시:분) — 차는 초를 세지 않는다 */
fun clockText(ms: Long): String {
    val minutes = maxOf(0L, ms / 60_000)
    return "${minutes / 60}:${String.format(Locale.US, "%02d", minutes % 60)}"
}

/** '오후 6:42' */
fun timeOfDay(ms: Long): String {
    val c = cal(ms)
    val hour = c.get(Calendar.HOUR_OF_DAY)
    val half = if (hour < 12) "오전" else "오후"
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$half $h12:${String.format(Locale.US, "%02d", c.get(Calendar.MINUTE))}"
}

/** '10월 26일 (월)' */
fun dayLabel(ms: Long): String {
    val c = cal(ms)
    return "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일 (${WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]})"
}

/** '10월' 또는 '2025년 12월' */
fun monthLabel(ms: Long, nowMs: Long): String {
    val c = cal(ms)
    val y = c.get(Calendar.YEAR)
    return if (y == cal(nowMs).get(Calendar.YEAR)) "${c.get(Calendar.MONTH) + 1}월" else "${y}년 ${c.get(Calendar.MONTH) + 1}월"
}

/** '2026년 10월 31일' */
fun fullDate(ms: Long): String {
    val c = cal(ms)
    return "${c.get(Calendar.YEAR)}년 ${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
}

/** 드라이브 이름: '오전 8:12 드라이브'(발자취 '오후 3:03 산책') */
fun driveName(startedAt: Long): String = "${timeOfDay(startedAt)} 드라이브"

/** 서울 날짜 번호(1970-01-01 = 0) */
fun seoulDay(ms: Long): Long = Math.floorDiv(ms + SEOUL.getOffset(ms), 86_400_000L)

/** 그 주 월요일의 날 번호 */
fun weekStartDay(ms: Long): Long {
    val day = seoulDay(ms)
    // 1970-01-01은 목요일(월=0 기준 3)
    val dow = Math.floorMod(day + 3, 7L)
    return day - dow
}

/** 그 달의 번호(연·월) */
fun monthKey(ms: Long): Int {
    val c = cal(ms)
    return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
}

/**
 * 지금(앱 시계). 시연 드라이브는 원래 날짜(2026-10-26~)로 기록되어 실제 오늘보다 뒤일 수 있다 — 그러면 가장 최근 시연 시각을 오늘로 본다
 * (오늘 달린 길·이번 주가 비지 않게). 실제 기록이 더 뒤면 실제 시각.
 */
fun appNow(drives: List<DriveRecord>, live: LiveDrive?): Long {
    val real = System.currentTimeMillis()
    var demo = 0L
    for (d in drives) if (d.source.startsWith("replay:") && d.endedAt > demo) demo = d.endedAt
    if (live != null && live.startedAt > demo) demo = live.startedAt
    return maxOf(real, demo)
}
