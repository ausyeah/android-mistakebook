package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.Question
import kotlinx.coroutines.flow.Flow

/**
 * 错题查询。
 *
 * 约定：statusName / reasonName 传枚举 name（与 TypeConverter 写入库中的值一致），
 * subjectFilter 传 0 表示不限学科，keyword 传空串表示不搜索。
 */
@Dao
interface QuestionDao {

    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL
          AND (:statusName = '' OR status = :statusName)
          AND (:subjectFilter = 0 OR subjectId = :subjectFilter)
          AND (:notebookFilter = 0 OR notebookId = :notebookFilter)
          AND (:reasonName = '' OR errorReason = :reasonName)
          AND (:keyword = '' OR stem LIKE '%' || :keyword || '%')
        ORDER BY updatedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observePage(
        statusName: String,
        subjectFilter: Long,
        notebookFilter: Long,
        reasonName: String,
        keyword: String,
        limit: Int,
        offset: Int
    ): Flow<List<Question>>

    @Query(
        """
        SELECT COUNT(*) FROM questions
        WHERE deletedAt IS NULL
          AND (:statusName = '' OR status = :statusName)
          AND (:subjectFilter = 0 OR subjectId = :subjectFilter)
          AND (:notebookFilter = 0 OR notebookId = :notebookFilter)
          AND (:reasonName = '' OR errorReason = :reasonName)
          AND (:keyword = '' OR stem LIKE '%' || :keyword || '%')
        """
    )
    fun observeFilteredCount(
        statusName: String,
        subjectFilter: Long,
        notebookFilter: Long,
        reasonName: String,
        keyword: String
    ): Flow<Int>

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: Long): Flow<Question?>

    // 待复习列表（首页「待复习」筛选与通知共用）
    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        ORDER BY nextReviewAt
        """
    )
    fun observeDue(today: Long): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun findById(id: Long): Question?

    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        ORDER BY nextReviewAt
        LIMIT :limit
        """
    )
    suspend fun dueForReview(today: Long, limit: Int): List<Question>

    @Query(
        """
        SELECT COUNT(*) FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        """
    )
    fun observeDueCount(today: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND subjectId = :subjectId")
    fun observeCountBySubject(subjectId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND errorReason = :reasonName")
    fun observeCountByReason(reasonName: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND subjectId = :subjectId")
    suspend fun countBySubject(subjectId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND errorReason = :reasonName")
    suspend fun countByReason(reasonName: String): Int

    @Insert
    suspend fun insert(question: Question): Long

    @Update
    suspend fun update(question: Question)

    @Query("UPDATE questions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE questions SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("SELECT * FROM questions WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun listDeletedBefore(before: Long): List<Question>

    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<Long>)
}
