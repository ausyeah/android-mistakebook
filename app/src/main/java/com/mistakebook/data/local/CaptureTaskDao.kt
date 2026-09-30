package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.domain.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureTaskDao {

    @Insert
    suspend fun insert(task: CaptureTask): Long

    @Update
    suspend fun update(task: CaptureTask)

    @Query("SELECT * FROM capture_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<CaptureTask?>

    @Query("SELECT * FROM capture_tasks WHERE id = :id")
    suspend fun findById(id: Long): CaptureTask?

    @Query("SELECT * FROM capture_tasks WHERE groupId = :groupId ORDER BY orderInGroup")
    fun observeGroup(groupId: String): Flow<List<CaptureTask>>

    @Query("SELECT * FROM capture_tasks WHERE groupId = :groupId ORDER BY orderInGroup")
    suspend fun listGroup(groupId: String): List<CaptureTask>

    @Query(
        "SELECT * FROM capture_tasks WHERE groupId IS (SELECT groupId FROM capture_tasks WHERE id = :id) ORDER BY orderInGroup"
    )
    fun observeGroupOf(id: Long): kotlinx.coroutines.flow.Flow<List<CaptureTask>>

    @Query(
        """
        SELECT * FROM capture_tasks
        WHERE status IN ('PENDING', 'UPLOADING', 'PARSING', 'LLM')
        ORDER BY COALESCE(groupId, ''), orderInGroup, id
        """
    )
    suspend fun listPending(): List<CaptureTask>

    @Query("UPDATE capture_tasks SET status = :status, errorMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TaskStatus, message: String?, now: Long)

    @Query(
        "UPDATE capture_tasks SET status = :status, errorMessage = :message, errorKind = :kind, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateFailure(
        id: Long,
        status: TaskStatus,
        message: String?,
        kind: String?,
        now: Long
    )

    @Query(
        "UPDATE capture_tasks SET status = 'PARSING', stageText = :stage, updatedAt = :now WHERE id = :id AND status != 'FAILED'"
    )
    suspend fun moveToParsing(id: Long, stage: String, now: Long)

    @Query("UPDATE capture_tasks SET stageText = :stage, updatedAt = :now WHERE id = :id")
    suspend fun updateStage(id: Long, stage: String, now: Long)

    @Query(
        """
        UPDATE capture_tasks
        SET status = 'FAILED', errorMessage = :message, updatedAt = :now
        WHERE status IN ('PENDING', 'UPLOADING', 'PARSING', 'LLM')
        """
    )
    suspend fun failUnfinished(message: String, now: Long)

    @Query("DELETE FROM capture_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM capture_tasks")
    suspend fun clearAll()
}
