package com.mistakebook.data.local

import androidx.room.TypeConverter
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.domain.ReviewResult
import com.mistakebook.domain.TaskStatus

/**
 * Room 枚举转换：入库一律用 name 字符串，便于跨版本迁移与排查。
 */
class Converters {

    @TypeConverter
    fun errorReasonToString(value: ErrorReason): String = value.name

    @TypeConverter
    fun stringToErrorReason(value: String): ErrorReason =
        runCatching { ErrorReason.valueOf(value) }.getOrDefault(ErrorReason.OTHER)

    @TypeConverter
    fun masteryStatusToString(value: MasteryStatus): String = value.name

    @TypeConverter
    fun stringToMasteryStatus(value: String): MasteryStatus =
        runCatching { MasteryStatus.valueOf(value) }.getOrDefault(MasteryStatus.ACTIVE)

    @TypeConverter
    fun taskStatusToString(value: TaskStatus): String = value.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus =
        runCatching { TaskStatus.valueOf(value) }.getOrDefault(TaskStatus.PENDING)

    @TypeConverter
    fun reviewResultToString(value: ReviewResult): String = value.name

    @TypeConverter
    fun stringToReviewResult(value: String): ReviewResult =
        runCatching { ReviewResult.valueOf(value) }.getOrDefault(ReviewResult.CORRECT)
}
