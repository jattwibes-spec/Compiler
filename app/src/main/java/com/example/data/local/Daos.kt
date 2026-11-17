package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtMillis DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE projectId = :projectId")
    suspend fun deleteProjectById(projectId: String)
}

@Dao
interface BuildJobDao {
    @Query("SELECT * FROM build_jobs ORDER BY startedAtMillis DESC")
    fun observeAllBuilds(): Flow<List<BuildJobEntity>>

    @Query("SELECT * FROM build_jobs WHERE projectId = :projectId ORDER BY startedAtMillis DESC")
    fun observeBuildsForProject(projectId: String): Flow<List<BuildJobEntity>>

    @Query("SELECT * FROM build_jobs WHERE buildId = :buildId LIMIT 1")
    suspend fun getBuildById(buildId: String): BuildJobEntity?

    @Query("SELECT * FROM build_jobs WHERE stageName NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'IDLE')")
    suspend fun getActiveBuilds(): List<BuildJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuild(build: BuildJobEntity)

    @Query("DELETE FROM build_jobs WHERE buildId = :buildId")
    suspend fun deleteBuildById(buildId: String)

    @Query("DELETE FROM build_jobs WHERE projectId = :projectId")
    suspend fun deleteBuildsForProject(projectId: String)
}

@Dao
interface FileBackupDao {
    @Query("SELECT * FROM file_backups WHERE projectId = :projectId ORDER BY createdAtMillis DESC")
    fun observeBackupsForProject(projectId: String): Flow<List<FileBackupEntity>>

    @Query("SELECT * FROM file_backups WHERE id = :backupId LIMIT 1")
    suspend fun getBackupById(backupId: Long): FileBackupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: FileBackupEntity): Long

    @Query("DELETE FROM file_backups WHERE projectId = :projectId")
    suspend fun deleteBackupsForProject(projectId: String)
}
